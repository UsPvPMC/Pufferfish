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
        return new NioEventLoopGroup(0, (new ThreadFactoryBuilder()).setNameFormat("Netty Client IO #%d").setDaemon(true).setUncaughtExceptionHandler(new net.minecraft.DefaultUncaughtExceptionHandlerWithName(LOGGER)).build()); // Paper
    });
    public static final LazyLoadedValue<EpollEventLoopGroup> NETWORK_EPOLL_WORKER_GROUP = new LazyLoadedValue<>(() -> {
        return new EpollEventLoopGroup(0, (new ThreadFactoryBuilder()).setNameFormat("Netty Epoll Client IO #%d").setDaemon(true).setUncaughtExceptionHandler(new net.minecraft.DefaultUncaughtExceptionHandlerWithName(LOGGER)).build()); // Paper
    });
    public static final LazyLoadedValue<DefaultEventLoopGroup> LOCAL_WORKER_GROUP = new LazyLoadedValue<>(() -> {
        return new DefaultEventLoopGroup(0, (new ThreadFactoryBuilder()).setNameFormat("Netty Local Client IO #%d").setDaemon(true).setUncaughtExceptionHandler(new net.minecraft.DefaultUncaughtExceptionHandlerWithName(LOGGER)).build()); // Paper
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
    private static boolean enableExplicitFlush = Boolean.getBoolean("paper.explicit-flush");
    // Optimize network
    public boolean isPending = true;
    public boolean queueImmunity = false;
    public ConnectionProtocol protocol;
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
        protocol = state; // Paper
        this.channel.attr(Connection.ATTRIBUTE_PROTOCOL).set(state);
        this.channel.attr(BundlerInfo.BUNDLER_PROVIDER).set(state);
        this.channel.config().setAutoRead(true);
        Connection.LOGGER.debug("Enabled auto read");
    }

    public void channelInactive(ChannelHandlerContext channelhandlercontext) {
        this.disconnect(Component.translatable("disconnect.endOfStream"));
    }

    public void exceptionCaught(ChannelHandlerContext channelhandlercontext, Throwable throwable) {
        // Paper start
        if (throwable instanceof io.netty.handler.codec.EncoderException && throwable.getCause() instanceof PacketEncoder.PacketTooLargeException) {
            if (((PacketEncoder.PacketTooLargeException) throwable.getCause()).getPacket().packetTooLarge(this)) {
                return;
            } else {
                throwable = throwable.getCause();
            }
        }
        // Paper end
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
        if (net.minecraft.server.MinecraftServer.getServer().isDebugging()) io.papermc.paper.util.TraceUtil.printStackTrace(throwable); // Spigot // Paper
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
    // Paper start
    public @Nullable net.minecraft.server.level.ServerPlayer getPlayer() {
        if (packetListener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl serverGamePacketListener) {
            return serverGamePacketListener.player;
        } else {
            return null;
        }
    }
    private static class InnerUtil { // Attempt to hide these methods from ProtocolLib so it doesn't accidently pick them up.
        private static java.util.List<Packet> buildExtraPackets(Packet packet) {
            java.util.List<Packet> extra = packet.getExtraPackets();
            if (extra == null || extra.isEmpty()) {
                return null;
            }
            java.util.List<Packet> ret = new java.util.ArrayList<>(1 + extra.size());
            buildExtraPackets0(extra, ret);
            return ret;
        }

        private static void buildExtraPackets0(java.util.List<Packet> extraPackets, java.util.List<Packet> into) {
            for (Packet extra : extraPackets) {
                into.add(extra);
                java.util.List<Packet> extraExtra = extra.getExtraPackets();
                if (extraExtra != null && !extraExtra.isEmpty()) {
                    buildExtraPackets0(extraExtra, into);
                }
            }
        }
        // Paper start
        private static boolean canSendImmediate(Connection networkManager, Packet<?> packet) {
            return networkManager.isPending || networkManager.protocol != ConnectionProtocol.PLAY ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundKeepAlivePacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerChatPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundClearTitlesPacket ||
                packet instanceof net.minecraft.network.protocol.game.ClientboundBossEventPacket;
        }
        // Paper end
    }
    // Paper end

    public void send(Packet<?> packet) {
        this.send(packet, (PacketSendListener) null);
    }

    public void send(Packet<?> packet, @Nullable PacketSendListener callbacks) {
        // Paper start - handle oversized packets better
        boolean connected = this.isConnected();
        if (!connected && !preparing) {
            return; // Do nothing
        }
        packet.onPacketDispatch(getPlayer());
        if (connected && (InnerUtil.canSendImmediate(this, packet) || (
            io.papermc.paper.util.MCUtil.isMainThread() && packet.isReady() && this.queue.isEmpty() &&
            (packet.getExtraPackets() == null || packet.getExtraPackets().isEmpty())
        ))) {
            this.sendPacket(packet, callbacks);
            return;
        }
        // write the packets to the queue, then flush - antixray hooks there already
        java.util.List<Packet> extraPackets = InnerUtil.buildExtraPackets(packet);
        boolean hasExtraPackets = extraPackets != null && !extraPackets.isEmpty();
        if (!hasExtraPackets) {
            this.queue.add(new Connection.PacketHolder(packet, callbacks));
        } else {
            java.util.List<Connection.PacketHolder> packets = new java.util.ArrayList<>(1 + extraPackets.size());
            packets.add(new Connection.PacketHolder(packet, null)); // delay the future listener until the end of the extra packets

            for (int i = 0, len = extraPackets.size(); i < len;) {
                Packet extra = extraPackets.get(i);
                boolean end = ++i == len;
                packets.add(new Connection.PacketHolder(extra, end ? callbacks : null)); // append listener to the end
            }
            this.queue.addAll(packets); // atomic
        }
        this.flushQueue();
        // Paper end
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

        // Paper start
        net.minecraft.server.level.ServerPlayer player = getPlayer();
        if (!isConnected()) {
            packet.onPacketDispatchFinish(player, null);
            return;
        }

        try {
            // Paper end
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
        // Paper start
        if (packet.hasFinishListener()) {
            channelfuture.addListener((ChannelFutureListener) channelFuture -> packet.onPacketDispatchFinish(player, channelFuture));
        }
        // Paper end

        channelfuture.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        // Paper start
        } catch (Exception e) {
            LOGGER.error("NetworkException: " + player, e);
            disconnect(Component.translatable("disconnect.genericReason", "Internal Exception: " + e.getMessage()));
            packet.onPacketDispatchFinish(player, null);
        }
        // Paper end
    }

    private ConnectionProtocol getCurrentProtocol() {
        return (ConnectionProtocol) this.channel.attr(Connection.ATTRIBUTE_PROTOCOL).get();
    }

    // Paper start - rewrite this to be safer if ran off main thread
    private boolean flushQueue() { // void -> boolean
        if (!isConnected()) {
            return true;
        }
        if (io.papermc.paper.util.MCUtil.isMainThread()) {
            return processQueue();
        } else if (isPending) {
            // Should only happen during login/status stages
            synchronized (this.queue) {
                return this.processQueue();
            }
        }
        return false;
    }
    private boolean processQueue() {
        try { // Paper - add pending task queue
        if (this.queue.isEmpty()) return true;
        // If we are on main, we are safe here in that nothing else should be processing queue off main anymore
        // But if we are not on main due to login/status, the parent is synchronized on packetQueue
        java.util.Iterator<PacketHolder> iterator = this.queue.iterator();
        while (iterator.hasNext()) {
            PacketHolder queued = iterator.next(); // poll -> peek

            // Fix NPE (Spigot bug caused by handleDisconnection())
            if (queued == null) {
                return true;
            }

            // Paper start - checking isConsumed flag and skipping packet sending
            if (queued.isConsumed()) {
                continue;
            }
            // Paper end - checking isConsumed flag and skipping packet sending

            Packet<?> packet = queued.packet;
            if (!packet.isReady()) {
                return false;
            } else {
                iterator.remove();
                if (queued.tryMarkConsumed()) { // Paper - try to mark isConsumed flag for de-duplicating packet
                    this.sendPacket(packet, queued.listener);
                }
            }
        }
        return true;
        } finally { // Paper start - add pending task queue
            Runnable r;
            while ((r = this.pendingTasks.poll()) != null) {
                this.channel.eventLoop().execute(r);
            }
        } // Paper end - add pending task queue
    }
    // Paper end

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
            if (enableExplicitFlush) this.channel.eventLoop().execute(() -> this.channel.flush()); // Paper - we don't need to explicit flush here, but allow opt in incase issues are found to a better version
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

    // Paper start
    public void clearPacketQueue() {
        net.minecraft.server.level.ServerPlayer player = getPlayer();
        queue.forEach(queuedPacket -> {
            Packet<?> packet = queuedPacket.packet;
            if (packet.hasFinishListener()) {
                packet.onPacketDispatchFinish(player, null);
            }
        });
        queue.clear();
    }
    // Paper end
    public void disconnect(Component disconnectReason) {
        // Spigot Start
        this.preparing = false;
        clearPacketQueue(); // Paper
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
                //Connection.LOGGER.warn("handleDisconnection() called twice"); // Paper - Do not log useless message
            } else {
                this.disconnectionHandled = true;
                if (this.getDisconnectedReason() != null) {
                    this.getPacketListener().onDisconnect(this.getDisconnectedReason());
                } else if (this.getPacketListener() != null) {
                    this.getPacketListener().onDisconnect(Component.translatable("multiplayer.disconnect.generic"));
                }
                clearPacketQueue(); // Paper
                // Paper start - Add PlayerConnectionCloseEvent
                final PacketListener packetListener = this.getPacketListener();
                if (packetListener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl) {
                    /* Player was logged in */
                    final net.minecraft.server.network.ServerGamePacketListenerImpl playerConnection = (net.minecraft.server.network.ServerGamePacketListenerImpl) packetListener;
                    new com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent(playerConnection.player.getUUID(),
                        playerConnection.player.getScoreboardName(), ((java.net.InetSocketAddress)address).getAddress(), false).callEvent();
                } else if (packetListener instanceof net.minecraft.server.network.ServerLoginPacketListenerImpl) {
                    /* Player is login stage */
                    final net.minecraft.server.network.ServerLoginPacketListenerImpl loginListener = (net.minecraft.server.network.ServerLoginPacketListenerImpl) packetListener;
                    switch (loginListener.state) {
                        case READY_TO_ACCEPT:
                        case DELAY_ACCEPT:
                        case ACCEPTED:
                            final com.mojang.authlib.GameProfile profile = loginListener.gameProfile; /* Should be non-null at this stage */
                            new com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent(profile.getId(), profile.getName(),
                                ((java.net.InetSocketAddress)address).getAddress(), false).callEvent();
                    }
                }
                // Paper end
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

        // Paper start - isConsumed flag for the connection
        private java.util.concurrent.atomic.AtomicBoolean isConsumed = new java.util.concurrent.atomic.AtomicBoolean(false);

        public boolean tryMarkConsumed() {
            return isConsumed.compareAndSet(false, true);
        }

        public boolean isConsumed() {
            return isConsumed.get();
        }
        // Paper end - isConsumed flag for the connection

        public PacketHolder(Packet<?> packet, @Nullable PacketSendListener callbacks) {
            this.packet = packet;
            this.listener = callbacks;
        }
    }
}
