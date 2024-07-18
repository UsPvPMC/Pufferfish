package net.minecraft.network.protocol;

import com.mojang.logging.LogUtils;
import net.minecraft.network.PacketListener;
import org.slf4j.Logger;

// CraftBukkit start
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.thread.BlockableEventLoop;

public class PacketUtils {

    private static final Logger LOGGER = LogUtils.getLogger();

    public PacketUtils() {}

    public static <T extends PacketListener> void ensureRunningOnSameThread(Packet<T> packet, T listener, ServerLevel world) throws RunningOnDifferentThreadException {
        PacketUtils.ensureRunningOnSameThread(packet, listener, (BlockableEventLoop) world.getServer());
    }

    public static <T extends PacketListener> void ensureRunningOnSameThread(Packet<T> packet, T listener, BlockableEventLoop<?> engine) throws RunningOnDifferentThreadException {
        if (!engine.isSameThread()) {
            engine.executeIfPossible(() -> {
                if (MinecraftServer.getServer().hasStopped() || (listener instanceof ServerGamePacketListenerImpl && ((ServerGamePacketListenerImpl) listener).processedDisconnect)) return; // CraftBukkit, MC-142590
                if (listener.isAcceptingMessages()) {
                    try {
                        packet.handle(listener);
                    } catch (Exception exception) {
                        if (listener.shouldPropagateHandlingExceptions()) {
                            throw exception;
                        }

                        PacketUtils.LOGGER.error("Failed to handle packet {}, suppressing error", packet, exception);
                    }
                } else {
                    PacketUtils.LOGGER.debug("Ignoring packet due to disconnection: {}", packet);
                }

            });
            throw RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD;
            // CraftBukkit start - SPIGOT-5477, MC-142590
        } else if (MinecraftServer.getServer().hasStopped() || (listener instanceof ServerGamePacketListenerImpl && ((ServerGamePacketListenerImpl) listener).processedDisconnect)) {
            throw RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD;
            // CraftBukkit end
        }
    }
}
