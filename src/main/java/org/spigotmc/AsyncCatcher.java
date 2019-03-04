package org.spigotmc;

import net.minecraft.server.MinecraftServer;

public class AsyncCatcher
{

    public static boolean enabled = true;

    public static void catchOp(String reason)
    {
        if ( (AsyncCatcher.enabled || io.papermc.paper.util.TickThread.STRICT_THREAD_CHECKS) && Thread.currentThread() != MinecraftServer.getServer().serverThread ) // Paper
        {
            throw new IllegalStateException( "Asynchronous " + reason + "!" );
        }
    }
}
