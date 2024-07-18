package net.minecraft.network.chat;

import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerPlayer;

@FunctionalInterface
public interface ChatDecorator {
    ChatDecorator PLAIN = (sender, message) -> {
        return CompletableFuture.completedFuture(message);
    };

    CompletableFuture<Component> decorate(@Nullable ServerPlayer sender, Component message);
}
