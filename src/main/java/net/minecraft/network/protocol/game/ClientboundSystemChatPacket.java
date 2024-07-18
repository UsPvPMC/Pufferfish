// mc-dev import
package net.minecraft.network.protocol.game;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;

// Spigot start
public record ClientboundSystemChatPacket(String content, boolean overlay) implements Packet<ClientGamePacketListener> {

    public ClientboundSystemChatPacket(Component content, boolean overlay) {
        this(Component.Serializer.toJson(content), overlay);
    }

    public ClientboundSystemChatPacket(net.md_5.bungee.api.chat.BaseComponent[] content, boolean overlay) {
        this(net.md_5.bungee.chat.ComponentSerializer.toString(content), overlay);
    }
    // Spigot end

    public ClientboundSystemChatPacket(FriendlyByteBuf buf) {
        this(buf.readComponent(), buf.readBoolean());
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(this.content, 262144); // Spigot
        buf.writeBoolean(this.overlay);
    }

    public void handle(ClientGamePacketListener listener) {
        listener.handleSystemChat(this);
    }

    @Override
    public boolean isSkippable() {
        return true;
    }
}
