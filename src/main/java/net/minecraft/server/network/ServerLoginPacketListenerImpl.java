package net.minecraft.server.network;

import com.google.common.primitives.Ints;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.logging.LogUtils;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.PrivateKey;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import net.minecraft.DefaultUncaughtExceptionHandler;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ServerLoginPacketListener;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import net.minecraft.util.RandomSource;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;

// CraftBukkit start
import org.bukkit.craftbukkit.util.Waitable;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerPreLoginEvent;
// CraftBukkit end

public class ServerLoginPacketListenerImpl implements ServerLoginPacketListener, TickablePacketListener {

    private static final AtomicInteger UNIQUE_THREAD_ID = new AtomicInteger(0);
    static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_TICKS_BEFORE_LOGIN = 600;
    private static final RandomSource RANDOM = RandomSource.create();
    private final byte[] challenge;
    final MinecraftServer server;
    public final Connection connection;
    public ServerLoginPacketListenerImpl.State state;
    private int tick;
    public @Nullable
    GameProfile gameProfile;
    private final String serverId;
    @Nullable
    private ServerPlayer delayedAcceptPlayer;
    public boolean iKnowThisMayNotBeTheBestIdeaButPleaseDisableUsernameValidation = false; // Paper - username validation overriding

    public ServerLoginPacketListenerImpl(MinecraftServer server, Connection connection) {
        this.state = ServerLoginPacketListenerImpl.State.HELLO;
        this.serverId = "";
        this.server = server;
        this.connection = connection;
        this.challenge = Ints.toByteArray(ServerLoginPacketListenerImpl.RANDOM.nextInt());
    }

    @Override
    public void tick() {
        // Paper start - Do not allow logins while the server is shutting down
        if (!MinecraftServer.getServer().isRunning()) {
            this.disconnect(org.bukkit.craftbukkit.util.CraftChatMessage.fromString(org.spigotmc.SpigotConfig.restartMessage)[0]);
            return;
        }
        // Paper end
        if (this.state == ServerLoginPacketListenerImpl.State.READY_TO_ACCEPT) {
            // Paper start - prevent logins to be processed even though disconnect was called
            if (connection.isConnected()) {
                this.handleAcceptedLogin();
            }
            // Paper end
        } else if (this.state == ServerLoginPacketListenerImpl.State.DELAY_ACCEPT) {
            ServerPlayer entityplayer = this.server.getPlayerList().getPlayer(this.gameProfile.getId());

            if (entityplayer == null) {
                this.state = ServerLoginPacketListenerImpl.State.READY_TO_ACCEPT;
                this.placeNewPlayer(this.delayedAcceptPlayer);
                this.delayedAcceptPlayer = null;
            }
        }

        if (this.tick++ == 600) {
            this.disconnect(Component.translatable("multiplayer.disconnect.slow_login"));
        }

    }

    // CraftBukkit start
    @Deprecated
    public void disconnect(String s) {
        this.disconnect(org.bukkit.craftbukkit.util.CraftChatMessage.fromString(s, true)[0]); // Paper - Fix hex colors not working in some kick messages
    }
    // CraftBukkit end

    @Override
    public boolean isAcceptingMessages() {
        return this.connection.isConnected();
    }

    public void disconnect(Component reason) {
        try {
            ServerLoginPacketListenerImpl.LOGGER.info("Disconnecting {}: {}", this.getUserName(), reason.getString());
            this.connection.send(new ClientboundLoginDisconnectPacket(reason));
            this.connection.disconnect(reason);
        } catch (Exception exception) {
            ServerLoginPacketListenerImpl.LOGGER.error("Error whilst disconnecting player", exception);
        }

    }

    private static final java.util.concurrent.ExecutorService authenticatorPool = java.util.concurrent.Executors.newCachedThreadPool(new com.google.common.util.concurrent.ThreadFactoryBuilder().setNameFormat("User Authenticator #%d").setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER)).build()); // Paper - Cache authenticator threads

    // Spigot start
    public void initUUID()
    {
        UUID uuid;
        if ( connection.spoofedUUID != null )
        {
            uuid = connection.spoofedUUID;
        } else
        {
            uuid = UUIDUtil.createOfflinePlayerUUID( this.gameProfile.getName() );
        }

        this.gameProfile = new GameProfile( uuid, this.gameProfile.getName() );

        if (connection.spoofedProfile != null)
        {
            for ( com.mojang.authlib.properties.Property property : connection.spoofedProfile )
            {
                if ( !ServerHandshakePacketListenerImpl.PROP_PATTERN.matcher( property.getName() ).matches() ) continue;
                this.gameProfile.getProperties().put( property.getName(), property );
            }
        }
    }

    public void handleAcceptedLogin() {
        if (!this.server.usesAuthentication()) {
            // this.gameProfile = this.createFakeProfile(this.gameProfile); // Spigot - Moved to initUUID
            // Spigot end
        }

        // CraftBukkit start - fire PlayerLoginEvent
        ServerPlayer s = this.server.getPlayerList().canPlayerLogin(this, this.gameProfile);

        if (s == null) {
            // this.disconnect(ichatbasecomponent);
            // CraftBukkit end
        } else {
            this.state = ServerLoginPacketListenerImpl.State.ACCEPTED;
            if (this.server.getCompressionThreshold() >= 0 && !this.connection.isMemoryConnection()) {
                this.connection.send(new ClientboundLoginCompressionPacket(this.server.getCompressionThreshold()), PacketSendListener.thenRun(() -> {
                    this.connection.setupCompression(this.server.getCompressionThreshold(), true);
                }));
            }

            this.connection.send(new ClientboundGameProfilePacket(this.gameProfile));
            ServerPlayer entityplayer = this.server.getPlayerList().getPlayer(this.gameProfile.getId());

            try {
                ServerPlayer entityplayer1 = this.server.getPlayerList().getPlayerForLogin(this.gameProfile, s); // CraftBukkit - add player reference

                if (entityplayer != null) {
                    this.state = ServerLoginPacketListenerImpl.State.DELAY_ACCEPT;
                    this.delayedAcceptPlayer = entityplayer1;
                } else {
                    this.placeNewPlayer(entityplayer1);
                }
            } catch (Exception exception) {
                ServerLoginPacketListenerImpl.LOGGER.error("Couldn't place player in world", exception);
                MutableComponent ichatmutablecomponent = Component.translatable("multiplayer.disconnect.invalid_player_data");
                // Paper start
                if (MinecraftServer.getServer().isDebugging()) {
                    io.papermc.paper.util.TraceUtil.printStackTrace(exception);
                }
                // Paper end

                this.connection.send(new ClientboundDisconnectPacket(ichatmutablecomponent));
                this.connection.disconnect(ichatmutablecomponent);
            }
        }

    }

    private void placeNewPlayer(ServerPlayer player) {
        this.server.getPlayerList().placeNewPlayer(this.connection, player);
    }

    @Override
    public void onDisconnect(Component reason) {
        ServerLoginPacketListenerImpl.LOGGER.info("{} lost connection: {}", this.getUserName(), reason.getString());
    }

    public String getUserName() {
        // Paper start
        String ip = io.papermc.paper.configuration.GlobalConfiguration.get().logging.logPlayerIpAddresses ? String.valueOf(this.connection.getRemoteAddress()) : "<ip address withheld>";
        return this.gameProfile != null ? this.gameProfile + " (" + ip + ")" : String.valueOf(ip);
        // Paper end
    }

    // Paper start - validate usernames
    public static boolean validateUsername(String in) {
        if (in == null || in.isEmpty() || in.length() > 16) {
            return false;
        }

        for (int i = 0, len = in.length(); i < len; ++i) {
            char c = in.charAt(i);

            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || (c == '_' || c == '.')) {
                continue;
            }

            return false;
        }

        return true;
    }
    // Paper end - validate usernames

    @Override
    public void handleHello(ServerboundHelloPacket packet) {
        Validate.validState(this.state == ServerLoginPacketListenerImpl.State.HELLO, "Unexpected hello packet", new Object[0]);
        Validate.validState(ServerLoginPacketListenerImpl.isValidUsername(packet.name()), "Invalid characters in username", new Object[0]);
        // Paper start - validate usernames
        if (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.isProxyOnlineMode() && io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.performUsernameValidation) {
            if (!this.iKnowThisMayNotBeTheBestIdeaButPleaseDisableUsernameValidation && !validateUsername(packet.name())) {
                ServerLoginPacketListenerImpl.this.disconnect("Failed to verify username!");
                return;
            }
        }
        // Paper end - validate usernames
        GameProfile gameprofile = this.server.getSingleplayerProfile();

        if (gameprofile != null && packet.name().equalsIgnoreCase(gameprofile.getName())) {
            this.gameProfile = gameprofile;
            this.state = ServerLoginPacketListenerImpl.State.READY_TO_ACCEPT;
        } else {
            this.gameProfile = new GameProfile((UUID) null, packet.name());
            if (this.server.usesAuthentication() && !this.connection.isMemoryConnection()) {
                this.state = ServerLoginPacketListenerImpl.State.KEY;
                this.connection.send(new ClientboundHelloPacket("", this.server.getKeyPair().getPublic().getEncoded(), this.challenge));
            } else {
                // Spigot start
            // Paper start - Cache authenticator threads
            authenticatorPool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            ServerLoginPacketListenerImpl.this.initUUID();
                            new LoginHandler().fireEvents();
                        } catch (Exception ex) {
                            ServerLoginPacketListenerImpl.this.disconnect("Failed to verify username!");
                            server.server.getLogger().log(java.util.logging.Level.WARNING, "Exception verifying " + ServerLoginPacketListenerImpl.this.gameProfile.getName(), ex);
                        }
                    }
            });
            // Paper end
                // Spigot end
            }

        }
    }

    public static boolean isValidUsername(String name) {
        return name.chars().filter((i) -> {
            return i <= 32 || i >= 127;
        }).findAny().isEmpty();
    }

    @Override
    public void handleKey(ServerboundKeyPacket packet) {
        Validate.validState(this.state == ServerLoginPacketListenerImpl.State.KEY, "Unexpected key packet", new Object[0]);

        final String s;

        try {
            PrivateKey privatekey = this.server.getKeyPair().getPrivate();

            if (!packet.isChallengeValid(this.challenge, privatekey)) {
                throw new IllegalStateException("Protocol error");
            }

            SecretKey secretkey = packet.getSecretKey(privatekey);
            // Paper start
//            Cipher cipher = Crypt.getCipher(2, secretkey);
//            Cipher cipher1 = Crypt.getCipher(1, secretkey);
            // Paper end

            s = (new BigInteger(Crypt.digestData("", this.server.getKeyPair().getPublic(), secretkey))).toString(16);
            this.state = ServerLoginPacketListenerImpl.State.AUTHENTICATING;
            this.connection.setupEncryption(secretkey); // Paper
        } catch (CryptException cryptographyexception) {
            throw new IllegalStateException("Protocol error", cryptographyexception);
        }

        // Paper start - Cache authenticator threads
        authenticatorPool.execute(new Runnable() {
            public void run() {
                GameProfile gameprofile = ServerLoginPacketListenerImpl.this.gameProfile;

                try {
                    ServerLoginPacketListenerImpl.this.gameProfile = ServerLoginPacketListenerImpl.this.server.getSessionService().hasJoinedServer(new GameProfile((UUID) null, gameprofile.getName()), s, this.getAddress());
                    if (ServerLoginPacketListenerImpl.this.gameProfile != null) {
                        // CraftBukkit start - fire PlayerPreLoginEvent
                        if (!ServerLoginPacketListenerImpl.this.connection.isConnected()) {
                            return;
                        }

                        new LoginHandler().fireEvents();
                    } else if (ServerLoginPacketListenerImpl.this.server.isSingleplayer()) {
                        ServerLoginPacketListenerImpl.LOGGER.warn("Failed to verify username but will let them in anyway!");
                        ServerLoginPacketListenerImpl.this.gameProfile = gameprofile;
                        ServerLoginPacketListenerImpl.this.state = ServerLoginPacketListenerImpl.State.READY_TO_ACCEPT;
                    } else {
                        ServerLoginPacketListenerImpl.this.disconnect(Component.translatable("multiplayer.disconnect.unverified_username"));
                        ServerLoginPacketListenerImpl.LOGGER.error("Username '{}' tried to join with an invalid session", gameprofile.getName());
                    }
                } catch (AuthenticationUnavailableException authenticationunavailableexception) {
                    if (ServerLoginPacketListenerImpl.this.server.isSingleplayer()) {
                        ServerLoginPacketListenerImpl.LOGGER.warn("Authentication servers are down but will let them in anyway!");
                        ServerLoginPacketListenerImpl.this.gameProfile = gameprofile;
                        ServerLoginPacketListenerImpl.this.state = ServerLoginPacketListenerImpl.State.READY_TO_ACCEPT;
                    } else {
                        ServerLoginPacketListenerImpl.this.disconnect(io.papermc.paper.adventure.PaperAdventure.asVanilla(io.papermc.paper.configuration.GlobalConfiguration.get().messages.kick.authenticationServersDown)); // Paper
                        ServerLoginPacketListenerImpl.LOGGER.error("Couldn't verify username because servers are unavailable");
                    }
                    // CraftBukkit start - catch all exceptions
                } catch (Exception exception) {
                    ServerLoginPacketListenerImpl.this.disconnect("Failed to verify username!");
                    server.server.getLogger().log(java.util.logging.Level.WARNING, "Exception verifying " + gameprofile.getName(), exception);
                    // CraftBukkit end
                }

            }

            @Nullable
            private InetAddress getAddress() {
                SocketAddress socketaddress = ServerLoginPacketListenerImpl.this.connection.getRemoteAddress();

                return ServerLoginPacketListenerImpl.this.server.getPreventProxyConnections() && socketaddress instanceof InetSocketAddress ? ((InetSocketAddress) socketaddress).getAddress() : null;
            }
        });
        // Paper end
    }

    // Spigot start
    public class LoginHandler {

        public void fireEvents() throws Exception {
                        String playerName = ServerLoginPacketListenerImpl.this.gameProfile.getName();
                        java.net.InetAddress address = ((java.net.InetSocketAddress) ServerLoginPacketListenerImpl.this.connection.getRemoteAddress()).getAddress();
                        java.net.InetAddress rawAddress = ((java.net.InetSocketAddress) connection.channel.remoteAddress()).getAddress(); // Paper
                        java.util.UUID uniqueId = ServerLoginPacketListenerImpl.this.gameProfile.getId();
                        final org.bukkit.craftbukkit.CraftServer server = ServerLoginPacketListenerImpl.this.server.server;

                        // Paper start
                        com.destroystokyo.paper.profile.PlayerProfile profile = com.destroystokyo.paper.profile.CraftPlayerProfile.asBukkitMirror(ServerLoginPacketListenerImpl.this.gameProfile);
                        AsyncPlayerPreLoginEvent asyncEvent = new AsyncPlayerPreLoginEvent(playerName, address, rawAddress, uniqueId, profile); // Paper - add rawAddress
                        server.getPluginManager().callEvent(asyncEvent);
                        profile = asyncEvent.getPlayerProfile();
                        profile.complete(true); // Paper - setPlayerProfileAPI
                        gameProfile = com.destroystokyo.paper.profile.CraftPlayerProfile.asAuthlibCopy(profile);
                        playerName = gameProfile.getName();
                        uniqueId = gameProfile.getId();
                        // Paper end

                        if (PlayerPreLoginEvent.getHandlerList().getRegisteredListeners().length != 0) {
                            final PlayerPreLoginEvent event = new PlayerPreLoginEvent(playerName, address, uniqueId);
                            if (asyncEvent.getResult() != PlayerPreLoginEvent.Result.ALLOWED) {
                                event.disallow(asyncEvent.getResult(), asyncEvent.kickMessage()); // Paper - Adventure
                            }
                            Waitable<PlayerPreLoginEvent.Result> waitable = new Waitable<PlayerPreLoginEvent.Result>() {
                                @Override
                                protected PlayerPreLoginEvent.Result evaluate() {
                                    server.getPluginManager().callEvent(event);
                                    return event.getResult();
                                }};

                            ServerLoginPacketListenerImpl.this.server.processQueue.add(waitable);
                            if (waitable.get() != PlayerPreLoginEvent.Result.ALLOWED) {
                                ServerLoginPacketListenerImpl.this.disconnect(io.papermc.paper.adventure.PaperAdventure.asVanilla(event.kickMessage())); // Paper - Adventure
                                return;
                            }
                        } else {
                            if (asyncEvent.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                                ServerLoginPacketListenerImpl.this.disconnect(io.papermc.paper.adventure.PaperAdventure.asVanilla(asyncEvent.kickMessage())); // Paper - Adventure
                                return;
                            }
                        }
                        // CraftBukkit end
                        ServerLoginPacketListenerImpl.LOGGER.info("UUID of player {} is {}", ServerLoginPacketListenerImpl.this.gameProfile.getName(), ServerLoginPacketListenerImpl.this.gameProfile.getId());
                        ServerLoginPacketListenerImpl.this.state = ServerLoginPacketListenerImpl.State.READY_TO_ACCEPT;
        }
    }
    // Spigot end

    public void handleCustomQueryPacket(ServerboundCustomQueryPacket packet) {
        this.disconnect(Component.translatable("multiplayer.disconnect.unexpected_query_response"));
    }

    protected GameProfile createFakeProfile(GameProfile profile) {
        UUID uuid = UUIDUtil.createOfflinePlayerUUID(profile.getName());

        return new GameProfile(uuid, profile.getName());
    }

    public static enum State {

        HELLO, KEY, AUTHENTICATING, NEGOTIATING, READY_TO_ACCEPT, DELAY_ACCEPT, ACCEPTED;

        private State() {}
    }
}
