package net.minecraft.network.protocol.game;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;

public class ClientboundSetTitleTextPacket implements Packet<ClientGamePacketListener> {
    private final Component text;
    public net.kyori.adventure.text.Component adventure$text; // Paper

    public ClientboundSetTitleTextPacket(Component title) {
        this.text = title;
    }

    public ClientboundSetTitleTextPacket(FriendlyByteBuf buf) {
        this.text = buf.readComponent();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        // Paper start
        if (this.adventure$text != null) {
            buf.writeComponent(this.adventure$text);
        } else
        // Paper end
        buf.writeComponent(this.text);
    }

    @Override
    public void handle(ClientGamePacketListener listener) {
        listener.setTitleText(this);
    }

    public Component getText() {
        return this.text;
    }
}
