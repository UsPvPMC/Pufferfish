package net.minecraft.network.protocol.game;

import com.google.common.base.MoreObjects;
import com.mojang.authlib.GameProfile;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.Optionull;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

public class ClientboundPlayerInfoUpdatePacket implements Packet<ClientGamePacketListener> {
    private final EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions;
    private final List<ClientboundPlayerInfoUpdatePacket.Entry> entries;

    public ClientboundPlayerInfoUpdatePacket(EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions, Collection<ServerPlayer> players) {
        this.actions = actions;
        this.entries = players.stream().map(ClientboundPlayerInfoUpdatePacket.Entry::new).toList();
    }

    public ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action action, ServerPlayer player) {
        this.actions = EnumSet.of(action);
        this.entries = List.of(new ClientboundPlayerInfoUpdatePacket.Entry(player));
    }

    public static ClientboundPlayerInfoUpdatePacket createPlayerInitializing(Collection<ServerPlayer> players) {
        EnumSet<ClientboundPlayerInfoUpdatePacket.Action> enumSet = EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME);
        return new ClientboundPlayerInfoUpdatePacket(enumSet, players);
    }

    public ClientboundPlayerInfoUpdatePacket(FriendlyByteBuf buf) {
        this.actions = buf.readEnumSet(ClientboundPlayerInfoUpdatePacket.Action.class);
        this.entries = buf.readList((buf2) -> {
            ClientboundPlayerInfoUpdatePacket.EntryBuilder entryBuilder = new ClientboundPlayerInfoUpdatePacket.EntryBuilder(buf2.readUUID());

            for(ClientboundPlayerInfoUpdatePacket.Action action : this.actions) {
                action.reader.read(entryBuilder, buf2);
            }

            return entryBuilder.build();
        });
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeEnumSet(this.actions, ClientboundPlayerInfoUpdatePacket.Action.class);
        buf.writeCollection(this.entries, (buf2, entry) -> {
            buf2.writeUUID(entry.profileId());

            for(ClientboundPlayerInfoUpdatePacket.Action action : this.actions) {
                action.writer.write(buf2, entry);
            }

        });
    }

    @Override
    public void handle(ClientGamePacketListener listener) {
        listener.handlePlayerInfoUpdate(this);
    }

    public EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions() {
        return this.actions;
    }

    public List<ClientboundPlayerInfoUpdatePacket.Entry> entries() {
        return this.entries;
    }

    public List<ClientboundPlayerInfoUpdatePacket.Entry> newEntries() {
        return this.actions.contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) ? this.entries : List.of();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("actions", this.actions).add("entries", this.entries).toString();
    }

    public static enum Action {
        ADD_PLAYER((serialized, buf) -> {
            GameProfile gameProfile = new GameProfile(serialized.profileId, buf.readUtf(16));
            gameProfile.getProperties().putAll(buf.readGameProfileProperties());
            serialized.profile = gameProfile;
        }, (buf, entry) -> {
            buf.writeUtf(entry.profile().getName(), 16);
            buf.writeGameProfileProperties(entry.profile().getProperties());
        }),
        INITIALIZE_CHAT((serialized, buf) -> {
            serialized.chatSession = buf.readNullable(RemoteChatSession.Data::read);
        }, (buf, entry) -> {
            buf.writeNullable(entry.chatSession, RemoteChatSession.Data::write);
        }),
        UPDATE_GAME_MODE((serialized, buf) -> {
            serialized.gameMode = GameType.byId(buf.readVarInt());
        }, (buf, entry) -> {
            buf.writeVarInt(entry.gameMode().getId());
        }),
        UPDATE_LISTED((serialized, buf) -> {
            serialized.listed = buf.readBoolean();
        }, (buf, entry) -> {
            buf.writeBoolean(entry.listed());
        }),
        UPDATE_LATENCY((serialized, buf) -> {
            serialized.latency = buf.readVarInt();
        }, (buf, entry) -> {
            buf.writeVarInt(entry.latency());
        }),
        UPDATE_DISPLAY_NAME((serialized, buf) -> {
            serialized.displayName = buf.readNullable(FriendlyByteBuf::readComponent);
        }, (buf, entry) -> {
            buf.writeNullable(entry.displayName(), FriendlyByteBuf::writeComponent);
        });

        final ClientboundPlayerInfoUpdatePacket.Action.Reader reader;
        final ClientboundPlayerInfoUpdatePacket.Action.Writer writer;

        private Action(ClientboundPlayerInfoUpdatePacket.Action.Reader reader, ClientboundPlayerInfoUpdatePacket.Action.Writer writer) {
            this.reader = reader;
            this.writer = writer;
        }

        public interface Reader {
            void read(ClientboundPlayerInfoUpdatePacket.EntryBuilder serialized, FriendlyByteBuf buf);
        }

        public interface Writer {
            void write(FriendlyByteBuf buf, ClientboundPlayerInfoUpdatePacket.Entry entry);
        }
    }

    public static record Entry(UUID profileId, GameProfile profile, boolean listed, int latency, GameType gameMode, @Nullable Component displayName, @Nullable RemoteChatSession.Data chatSession) {
        Entry(ServerPlayer player) {
            this(player.getUUID(), player.getGameProfile(), true, player.latency, player.gameMode.getGameModeForPlayer(), player.getTabListDisplayName(), Optionull.map(player.getChatSession(), RemoteChatSession::asData));
        }
    }

    static class EntryBuilder {
        final UUID profileId;
        GameProfile profile;
        boolean listed;
        int latency;
        GameType gameMode = GameType.DEFAULT_MODE;
        @Nullable
        Component displayName;
        @Nullable
        RemoteChatSession.Data chatSession;

        EntryBuilder(UUID profileId) {
            this.profileId = profileId;
            this.profile = new GameProfile(profileId, (String)null);
        }

        ClientboundPlayerInfoUpdatePacket.Entry build() {
            return new ClientboundPlayerInfoUpdatePacket.Entry(this.profileId, this.profile, this.listed, this.latency, this.gameMode, this.displayName, this.chatSession);
        }
    }
}
