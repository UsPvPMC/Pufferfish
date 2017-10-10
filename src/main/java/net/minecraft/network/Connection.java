package net.minecraft.network;

import com.google.common.collect.Queues;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.logging.LogUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.TimeoutException;
import io.netty.util.AttributeKey;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import javax.annotation.Nullable;
import javax.crypto.Cipher;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.util.LazyLoadedValue;
import net.minecraft.util.Mth;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

public class Connection extends SimpleChannelInboundHandler<Packet<?>> {

    private static final float AVERAGE_PACKETS_SMOOTHING = 0.75F;
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final Marker ROOT_MARKER = MarkerFactory.getMarker("NETWORK");
    public static final Marker PACKET_MARKER = (Marker) Util.make(MarkerFactory.getMarker("NETWORK_PACKETS"), (marker) -> {
        marker.add(Connection.ROOT_MARKER);
    });
    public static final Marker PACKET_RECEIVED_MARKER = (Marker) Util.make(MarkerFactory.getMarker("PACKET_RECEIVED"), (marker) -> {
        marker.add(Connection.PACKET_MARKER);
    });
    public static final Marker PACKET_SENT_MARKER = (Marker) Util.make(MarkerFactory.getMarker("PACKET_SENT"), (marker) -> {
        marker.add(Connection.PACKET_MARKER);
    });
    public static final AttributeKey<ConnectionProtocol> ATTRIBUTE_PROTOCOL = AttributeKey.valueOf("protocol");
    public static final LazyLoadedValue<NioEventLoopGroup> NETWORK_WORKER_GROUP = new LazyLoadedValue<>(() -> {
        return new NioEventLoopGroup(0, (new ThreadFactoryBuilder()).setNameFormat("Netty Client IO #%d").setDaemon(true).build());
    });
    public static final LazyLoadedValue<EpollEventLoopGroup> NETWORK_EPOLL_WORKER_GROUP = new LazyLoadedValue<>(() -> {
        return new EpollEventLoopGroup(0, (new ThreadFactoryBuilder()).setNameFormat("Netty Epoll Client IO #%d").setDaemon(true).build());
    });
    public static final LazyLoadedValue<DefaultEventLoopGroup> LOCAL_WORKER_GROUP = new LazyLoadedValue<>(() -> {
        return new DefaultEventLoopGroup(0, (new ThreadFactoryBuilder()).setNameFormat("Netty Local Client IO #%d").setDaemon(true).build());
    });
    private final PacketFlow receiving;
    private final Queue<Connection.PacketHolder> queue = Queues.newConcurrentLinkedQueue();
    public Channel channel;
    public SocketAddress address;
    // Spigot Start
    public java.util.UUID spoofedUUID;
    public com.mojang.authlib.properties.Property[] spoofedProfile;
    public boolean preparing = true;
    // Spigot End
    private PacketListener packetListener;
    private Component disconnectedReason;
    private boolean encrypted;
    private boolean disconnectionHandled;
    private int receivedPackets;
    private int sentPackets;
    private float averageReceivedPackets;
    private float averageSentPackets;
    private int tickCount;
    private boolean handlingFault;
    public String hostname = ""; // CraftBukkit - add field
    // Paper start - add pending task queue
    private final Queue<Runnable> pendingTasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
    public void execute(final Runnable run) {
        if (this.channel == null || !this.channel.isRegistered()) {
            run.run();
            return;
        }
        final boolean queue = !this.queue.isEmpty();
        if (!queue) {
            this.channel.eventLoop().execute(run);
        } else {
            this.pendingTasks.add(run);
            if (this.queue.isEmpty()) {
                // something flushed async, dump tasks now
                Runnable r;
                while ((r = this.pendingTasks.poll()) != null) {
                    this.channel.eventLoop().execute(r);
                }
            }
        }
    }
    // Paper end - add pending task queue
    // Paper start - NetworkClient implementation
    public int protocolVersion;
    public java.net.InetSocketAddress virtualHost;
    // Paper end

    public Connection(PacketFlow side) {
        this.receiving = side;
    }

    public void channelActive(ChannelHandlerContext channelhandlercontext) throws Exception {
        super.channelActive(channelhandlercontext);
        this.channel = channelhandlercontext.channel();
        this.address = this.channel.remoteAddress();
        // Spigot Start
        this.preparing = false;
        // Spigot End

        try {
            this.setProtocol(ConnectionProtocol.HANDSHAKING);
        } catch (Throwable throwable) {
            Connection.LOGGER.error(LogUtils.FATAL_MARKER, "Failed to change protocol to handshake", throwable);
        }

    }

    public void setProtocol(ConnectionProtocol state) {
        this.channel.attr(Connection.ATTRIBUTE_PROTOCOL).set(state);
        this.channel.attr(BundlerInfo.BUNDLER_PROVIDER).set(state);
        this.channel.config().setAutoRead(true);
        Connection.LOGGER.debug("Enabled auto read");
    }

    public void channelInactive(ChannelHandlerContext channelhandlercontext) {
        this.disconnect(Component.translatable("disconnect.endOfStream"));
    }

    public void exceptionCaught(ChannelHandlerContext channelhandlercontext, Throwable throwable) {
        if (throwable instanceof SkipPacketException) {
            Connection.LOGGER.debug("Skipping packet due to errors", throwable.getCause());
        } else {
            boolean flag = !this.handlingFault;

            this.handlingFault = true;
            if (this.channel.isOpen()) {
                if (throwable instanceof TimeoutException) {
                    Connection.LOGGER.debug("Timeout", throwable);
                    this.disconnect(Component.translatable("disconnect.timeout"));
                } else {
                    MutableComponent ichatmutablecomponent = Component.translatable("disconnect.genericReason", "Internal Exception: " + throwable);

                    if (flag) {
                        Connection.LOGGER.debug("Failed to sent packet", throwable);
                        ConnectionProtocol enumprotocol = this.getCurrentProtocol();
                        Packet<?> packet = enumprotocol == ConnectionProtocol.LOGIN ? new ClientboundLoginDisconnectPacket(ichatmutablecomponent) : new ClientboundDisconnectPacket(ichatmutablecomponent);

                        this.send((Packet) packet, PacketSendListener.thenRun(() -> {
                            this.disconnect(ichatmutablecomponent);
                        }));
                        this.setReadOnly();
                    } else {
                        Connection.LOGGER.debug("Double fault", throwable);
                        this.disconnect(ichatmutablecomponent);
                    }
                }

            }
        }
        if (net.minecraft.server.MinecraftServer.getServer().isDebugging()) throwable.printStackTrace(); // Spigot
    }

    protected void channelRead0(ChannelHandlerContext channelhandlercontext, Packet<?> packet) {
        if (this.channel.isOpen()) {
            try {
                Connection.genericsFtw(packet, this.packetListener);
            } catch (RunningOnDifferentThreadException cancelledpackethandleexception) {
                ;
            } catch (RejectedExecutionException rejectedexecutionexception) {
                this.disconnect(Component.translatable("multiplayer.disconnect.server_shutdown"));
            } catch (ClassCastException classcastexception) {
                Connection.LOGGER.error("Received {} that couldn't be processed", packet.getClass(), classcastexception);
                this.disconnect(Component.translatable("multiplayer.disconnect.invalid_packet"));
            }

            ++this.receivedPackets;
        }

    }

    private static <T extends PacketListener> void genericsFtw(Packet<T> packet, PacketListener listener) {
        packet.handle((T) listener); // CraftBukkit - decompile error
    }

    public void setListener(PacketListener listener) {
        Validate.notNull(listener, "packetListener", new Object[0]);
        this.packetListener = listener;
    }

    public void send(Packet<?> packet) {
        this.send(packet, (PacketSendListener) null);
    }

    public void send(Packet<?> packet, @Nullable PacketSendListener callbacks) {
        if (this.isConnected()) {
            this.flushQueue();
            this.sendPacket(packet, callbacks);
        } else {
            this.queue.add(new Connection.PacketHolder(packet, callbacks));
        }

    }

    private void sendPacket(Packet<?> packet, @Nullable PacketSendListener callbacks) {
        ConnectionProtocol enumprotocol = ConnectionProtocol.getProtocolForPacket(packet);
        ConnectionProtocol enumprotocol1 = this.getCurrentProtocol();

        ++this.sentPackets;
        if (enumprotocol1 != enumprotocol) {
            if (enumprotocol == null) {
                throw new IllegalStateException("Encountered packet without set protocol: " + packet);
            }

            Connection.LOGGER.debug("Disabled auto read");
            this.channel.config().setAutoRead(false);
        }

        if (this.channel.eventLoop().inEventLoop()) {
            this.doSendPacket(packet, callbacks, enumprotocol, enumprotocol1);
        } else {
            this.channel.eventLoop().execute(() -> {
                this.doSendPacket(packet, callbacks, enumprotocol, enumprotocol1);
            });
        }

    }

    private void doSendPacket(Packet<?> packet, @Nullable PacketSendListener callbacks, ConnectionProtocol packetState, ConnectionProtocol currentState) {
        if (packetState != currentState) {
            this.setProtocol(packetState);
        }

        ChannelFuture channelfuture = this.channel.writeAndFlush(packet);

        if (callbacks != null) {
            channelfuture.addListener((future) -> {
                if (future.isSuccess()) {
                    callbacks.onSuccess();
                } else {
                    Packet<?> packet1 = callbacks.onFailure();

                    if (packet1 != null) {
                        ChannelFuture channelfuture1 = this.channel.writeAndFlush(packet1);

                        channelfuture1.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                    }
                }

            });
        }

        channelfuture.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    private ConnectionProtocol getCurrentProtocol() {
        return (ConnectionProtocol) this.channel.attr(Connection.ATTRIBUTE_PROTOCOL).get();
    }

    private void flushQueue() {
        try { // Paper - add pending task queue
        if (this.channel != null && this.channel.isOpen()) {
            Queue queue = this.queue;

            synchronized (this.queue) {
                Connection.PacketHolder networkmanager_queuedpacket;

                while ((networkmanager_queuedpacket = (Connection.PacketHolder) this.queue.poll()) != null) {
                    this.sendPacket(networkmanager_queuedpacket.packet, networkmanager_queuedpacket.listener);
                }

            }
        }
        } finally { // Paper start - add pending task queue
            Runnable r;
            while ((r = this.pendingTasks.poll()) != null) {
                this.channel.eventLoop().execute(r);
            }
        } // Paper end - add pending task queue
    }

    public void tick() {
        this.flushQueue();
        PacketListener packetlistener = this.packetListener;

        if (packetlistener instanceof TickablePacketListener) {
            TickablePacketListener tickablepacketlistener = (TickablePacketListener) packetlistener;

            tickablepacketlistener.tick();
        }

        if (!this.isConnected() && !this.disconnectionHandled) {
            this.handleDisconnection();
        }

        if (this.channel != null) {
            this.channel.flush();
        }

        if (this.tickCount++ % 20 == 0) {
            this.tickSecond();
        }

    }

    protected void tickSecond() {
        this.averageSentPackets = Mth.lerp(0.75F, (float) this.sentPackets, this.averageSentPackets);
        this.averageReceivedPackets = Mth.lerp(0.75F, (float) this.receivedPackets, this.averageReceivedPackets);
        this.sentPackets = 0;
        this.receivedPackets = 0;
    }

    public SocketAddress getRemoteAddress() {
        return this.address;
    }

    public void disconnect(Component disconnectReason) {
        // Spigot Start
        this.preparing = false;
        // Spigot End
        if (this.channel.isOpen()) {
            this.channel.close(); // We can't wait as this may be called from an event loop.
            this.disconnectedReason = disconnectReason;
        }

    }

    public boolean isMemoryConnection() {
        return this.channel instanceof LocalChannel || this.channel instanceof LocalServerChannel;
    }

    public PacketFlow getReceiving() {
        return this.receiving;
    }

    public PacketFlow getSending() {
        return this.receiving.getOpposite();
    }

    public static Connection connectToServer(InetSocketAddress address, boolean useEpoll) {
        final Connection networkmanager = new Connection(PacketFlow.CLIENTBOUND);
        Class oclass;
        LazyLoadedValue lazyinitvar;

        if (Epoll.isAvailable() && useEpoll) {
            oclass = EpollSocketChannel.class;
            lazyinitvar = Connection.NETWORK_EPOLL_WORKER_GROUP;
        } else {
            oclass = NioSocketChannel.class;
            lazyinitvar = Connection.NETWORK_WORKER_GROUP;
        }

        ((Bootstrap) ((Bootstrap) ((Bootstrap) (new Bootstrap()).group((EventLoopGroup) lazyinitvar.get())).handler(new ChannelInitializer<Channel>() {
            protected void initChannel(Channel channel) {
                try {
                    channel.config().setOption(ChannelOption.TCP_NODELAY, true);
                } catch (ChannelException channelexception) {
                    ;
                }

                ChannelPipeline channelpipeline = channel.pipeline().addLast("timeout", new ReadTimeoutHandler(30));

                Connection.configureSerialization(channelpipeline, PacketFlow.CLIENTBOUND);
                channelpipeline.addLast("packet_handler", networkmanager);
            }
        })).channel(oclass)).connect(address.getAddress(), address.getPort()).syncUninterruptibly();
        return networkmanager;
    }

    public static void configureSerialization(ChannelPipeline pipeline, PacketFlow side) {
        PacketFlow enumprotocoldirection1 = side.getOpposite();

        pipeline.addLast("splitter", new Varint21FrameDecoder()).addLast("decoder", new PacketDecoder(side)).addLast("prepender", new Varint21LengthFieldPrepender()).addLast("encoder", new PacketEncoder(enumprotocoldirection1)).addLast("unbundler", new PacketBundleUnpacker(enumprotocoldirection1)).addLast("bundler", new PacketBundlePacker(side));
    }

    public static Connection connectToLocalServer(SocketAddress address) {
        final Connection networkmanager = new Connection(PacketFlow.CLIENTBOUND);

        ((Bootstrap) ((Bootstrap) ((Bootstrap) (new Bootstrap()).group((EventLoopGroup) Connection.LOCAL_WORKER_GROUP.get())).handler(new ChannelInitializer<Channel>() {
            protected void initChannel(Channel channel) {
                ChannelPipeline channelpipeline = channel.pipeline();

                channelpipeline.addLast("packet_handler", networkmanager);
            }
        })).channel(LocalChannel.class)).connect(address).syncUninterruptibly();
        return networkmanager;
    }

    public void setEncryptionKey(Cipher decryptionCipher, Cipher encryptionCipher) {
        this.encrypted = true;
        this.channel.pipeline().addBefore("splitter", "decrypt", new CipherDecoder(decryptionCipher));
        this.channel.pipeline().addBefore("prepender", "encrypt", new CipherEncoder(encryptionCipher));
    }

    public boolean isEncrypted() {
        return this.encrypted;
    }

    public boolean isConnected() {
        return this.channel != null && this.channel.isOpen();
    }

    public boolean isConnecting() {
        return this.channel == null;
    }

    public PacketListener getPacketListener() {
        return this.packetListener;
    }

    @Nullable
    public Component getDisconnectedReason() {
        return this.disconnectedReason;
    }

    public void setReadOnly() {
        this.channel.config().setAutoRead(false);
    }

    public void setupCompression(int compressionThreshold, boolean rejectsBadPackets) {
        if (compressionThreshold >= 0) {
            if (this.channel.pipeline().get("decompress") instanceof CompressionDecoder) {
                ((CompressionDecoder) this.channel.pipeline().get("decompress")).setThreshold(compressionThreshold, rejectsBadPackets);
            } else {
                this.channel.pipeline().addBefore("decoder", "decompress", new CompressionDecoder(compressionThreshold, rejectsBadPackets));
            }

            if (this.channel.pipeline().get("compress") instanceof CompressionEncoder) {
                ((CompressionEncoder) this.channel.pipeline().get("compress")).setThreshold(compressionThreshold);
            } else {
                this.channel.pipeline().addBefore("encoder", "compress", new CompressionEncoder(compressionThreshold));
            }
        } else {
            if (this.channel.pipeline().get("decompress") instanceof CompressionDecoder) {
                this.channel.pipeline().remove("decompress");
            }

            if (this.channel.pipeline().get("compress") instanceof CompressionEncoder) {
                this.channel.pipeline().remove("compress");
            }
        }

    }

    public void handleDisconnection() {
        if (this.channel != null && !this.channel.isOpen()) {
            if (this.disconnectionHandled) {
                Connection.LOGGER.warn("handleDisconnection() called twice");
            } else {
                this.disconnectionHandled = true;
                if (this.getDisconnectedReason() != null) {
                    this.getPacketListener().onDisconnect(this.getDisconnectedReason());
                } else if (this.getPacketListener() != null) {
                    this.getPacketListener().onDisconnect(Component.translatable("multiplayer.disconnect.generic"));
                }
                this.queue.clear(); // Free up packet queue.
            }

        }
    }

    public float getAverageReceivedPackets() {
        return this.averageReceivedPackets;
    }

    public float getAverageSentPackets() {
        return this.averageSentPackets;
    }

    private static class PacketHolder {

        final Packet<?> packet;
        @Nullable
        final PacketSendListener listener;

        public PacketHolder(Packet<?> packet, @Nullable PacketSendListener callbacks) {
            this.packet = packet;
            this.listener = callbacks;
        }
    }
}
