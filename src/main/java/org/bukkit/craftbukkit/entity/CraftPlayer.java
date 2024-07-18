package org.bukkit.craftbukkit.entity;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.BaseEncoding;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.shorts.ShortArraySet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundCustomChatCompletionsPacket;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDelayPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.UserWhiteListEntry;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang.Validate;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Effect;
import org.bukkit.GameMode;
import org.bukkit.Instrument;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Note;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.WeatherType;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.serialization.DelegateDeserialization;
import org.bukkit.conversations.Conversation;
import org.bukkit.conversations.ConversationAbandonedEvent;
import org.bukkit.conversations.ManuallyAbandonedConversationCanceller;
import org.bukkit.craftbukkit.CraftEffect;
import org.bukkit.craftbukkit.CraftEquipmentSlot;
import org.bukkit.craftbukkit.CraftOfflinePlayer;
import org.bukkit.craftbukkit.CraftParticle;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftSound;
import org.bukkit.craftbukkit.CraftStatistic;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.CraftWorldBorder;
import org.bukkit.craftbukkit.advancement.CraftAdvancement;
import org.bukkit.craftbukkit.advancement.CraftAdvancementProgress;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.bukkit.craftbukkit.block.CraftSign;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.conversations.ConversationTracker;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.map.CraftMapView;
import org.bukkit.craftbukkit.map.RenderData;
import org.bukkit.craftbukkit.profile.CraftPlayerProfile;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboard;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerHideEntityEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerRespawnEvent.RespawnReason;
import org.bukkit.event.player.PlayerShowEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerUnregisterChannelEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView.Property;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapView;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.StandardMessenger;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.scoreboard.Scoreboard;

import net.md_5.bungee.api.chat.BaseComponent; // Spigot

@DelegateDeserialization(CraftOfflinePlayer.class)
public class CraftPlayer extends CraftHumanEntity implements Player {
    private long firstPlayed = 0;
    private long lastPlayed = 0;
    private boolean hasPlayedBefore = false;
    private final ConversationTracker conversationTracker = new ConversationTracker();
    private final Set<String> channels = new HashSet<String>();
    private final Map<UUID, Set<WeakReference<Plugin>>> invertedVisibilityEntities = new HashMap<>();
    private static final WeakHashMap<Plugin, WeakReference<Plugin>> pluginWeakReferences = new WeakHashMap<>();
    private int hash = 0;
    private double health = 20;
    private boolean scaledHealth = false;
    private double healthScale = 20;
    private CraftWorldBorder clientWorldBorder = null;
    private BorderChangeListener clientWorldBorderListener = this.createWorldBorderListener();

    public CraftPlayer(CraftServer server, ServerPlayer entity) {
        super(server, entity);

        this.firstPlayed = System.currentTimeMillis();
    }

    public GameProfile getProfile() {
        return this.getHandle().getGameProfile();
    }

    @Override
    public boolean isOp() {
        return server.getHandle().isOp(this.getProfile());
    }

    @Override
    public void setOp(boolean value) {
        if (value == this.isOp()) return;

        if (value) {
            server.getHandle().op(this.getProfile());
        } else {
            server.getHandle().deop(this.getProfile());
        }

        perm.recalculatePermissions();
    }

    @Override
    public boolean isOnline() {
        return server.getPlayer(getUniqueId()) != null;
    }

    @Override
    public PlayerProfile getPlayerProfile() {
        return new CraftPlayerProfile(this.getProfile());
    }

    @Override
    public InetSocketAddress getAddress() {
        if (this.getHandle().connection == null) return null;

        SocketAddress addr = this.getHandle().connection.getRemoteAddress();
        if (addr instanceof InetSocketAddress) {
            return (InetSocketAddress) addr;
        } else {
            return null;
        }
    }

    @Override
    public double getEyeHeight(boolean ignorePose) {
        if (ignorePose) {
            return 1.62D;
        } else {
            return getEyeHeight();
        }
    }

    @Override
    public void sendRawMessage(String message) {
        if (this.getHandle().connection == null) return;

        for (Component component : CraftChatMessage.fromString(message)) {
            this.getHandle().sendSystemMessage(component);
        }
    }

    @Override
    public void sendRawMessage(UUID sender, String message) {
        if (this.getHandle().connection == null) return;

        for (Component component : CraftChatMessage.fromString(message)) {
            this.getHandle().sendSystemMessage(component);
        }
    }

    @Override
    public void sendMessage(String message) {
        if (!this.conversationTracker.isConversingModaly()) {
            this.sendRawMessage(message);
        }
    }

    @Override
    public void sendMessage(String... messages) {
        for (String message : messages) {
            this.sendMessage(message);
        }
    }

    @Override
    public void sendMessage(UUID sender, String message) {
        if (!this.conversationTracker.isConversingModaly()) {
            this.sendRawMessage(sender, message);
        }
    }

    @Override
    public void sendMessage(UUID sender, String... messages) {
        for (String message : messages) {
            this.sendMessage(sender, message);
        }
    }

    @Override
    public String getDisplayName() {
        return this.getHandle().displayName;
    }

    @Override
    public void setDisplayName(final String name) {
        this.getHandle().displayName = name == null ? getName() : name;
    }

    @Override
    public String getPlayerListName() {
        return this.getHandle().listName == null ? getName() : CraftChatMessage.fromComponent(this.getHandle().listName);
    }

    @Override
    public void setPlayerListName(String name) {
        if (name == null) {
            name = getName();
        }
        this.getHandle().listName = name.equals(getName()) ? null : CraftChatMessage.fromStringOrNull(name);
        for (ServerPlayer player : (List<ServerPlayer>) server.getHandle().players) {
            if (player.getBukkitEntity().canSee(this)) {
                player.connection.send(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, this.getHandle()));
            }
        }
    }

    private Component playerListHeader;
    private Component playerListFooter;

    @Override
    public String getPlayerListHeader() {
        return (this.playerListHeader == null) ? null : CraftChatMessage.fromComponent(playerListHeader);
    }

    @Override
    public String getPlayerListFooter() {
        return (this.playerListFooter == null) ? null : CraftChatMessage.fromComponent(playerListFooter);
    }

    @Override
    public void setPlayerListHeader(String header) {
        this.playerListHeader = CraftChatMessage.fromStringOrNull(header, true);
        this.updatePlayerListHeaderFooter();
    }

    @Override
    public void setPlayerListFooter(String footer) {
        this.playerListFooter = CraftChatMessage.fromStringOrNull(footer, true);
        this.updatePlayerListHeaderFooter();
    }

    @Override
    public void setPlayerListHeaderFooter(String header, String footer) {
        this.playerListHeader = CraftChatMessage.fromStringOrNull(header, true);
        this.playerListFooter = CraftChatMessage.fromStringOrNull(footer, true);
        this.updatePlayerListHeaderFooter();
    }

    private void updatePlayerListHeaderFooter() {
        if (this.getHandle().connection == null) return;

        ClientboundTabListPacket packet = new ClientboundTabListPacket((this.playerListHeader == null) ? Component.empty() : this.playerListHeader, (this.playerListFooter == null) ? Component.empty() : this.playerListFooter);
        this.getHandle().connection.send(packet);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof OfflinePlayer)) {
            return false;
        }
        OfflinePlayer other = (OfflinePlayer) obj;
        if ((this.getUniqueId() == null) || (other.getUniqueId() == null)) {
            return false;
        }

        boolean uuidEquals = this.getUniqueId().equals(other.getUniqueId());
        boolean idEquals = true;

        if (other instanceof CraftPlayer) {
            idEquals = this.getEntityId() == ((CraftPlayer) other).getEntityId();
        }

        return uuidEquals && idEquals;
    }

    @Override
    public void kickPlayer(String message) {
        org.spigotmc.AsyncCatcher.catchOp("player kick"); // Spigot
        if (this.getHandle().connection == null) return;

        this.getHandle().connection.disconnect(message == null ? "" : message);
    }

    @Override
    public void setCompassTarget(Location loc) {
        if (this.getHandle().connection == null) return;

        // Do not directly assign here, from the packethandler we'll assign it.
        this.getHandle().connection.send(new ClientboundSetDefaultSpawnPositionPacket(new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()), loc.getYaw()));
    }

    @Override
    public Location getCompassTarget() {
        return this.getHandle().compassTarget;
    }

    @Override
    public void chat(String msg) {
        if (this.getHandle().connection == null) return;

        this.getHandle().connection.chat(msg, PlayerChatMessage.system(msg), false);
    }

    @Override
    public boolean performCommand(String command) {
        return server.dispatchCommand(this, command);
    }

    @Override
    public void playNote(Location loc, byte instrument, byte note) {
        if (this.getHandle().connection == null) return;

        String instrumentName = null;
        switch (instrument) {
        case 0:
            instrumentName = "harp";
            break;
        case 1:
            instrumentName = "basedrum";
            break;
        case 2:
            instrumentName = "snare";
            break;
        case 3:
            instrumentName = "hat";
            break;
        case 4:
            instrumentName = "bass";
            break;
        case 5:
            instrumentName = "flute";
            break;
        case 6:
            instrumentName = "bell";
            break;
        case 7:
            instrumentName = "guitar";
            break;
        case 8:
            instrumentName = "chime";
            break;
        case 9:
            instrumentName = "xylophone";
            break;
        }

        float f = (float) Math.pow(2.0D, (note - 12.0D) / 12.0D);
        this.getHandle().connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(CraftSound.getSoundEffect("block.note_block." + instrumentName)), net.minecraft.sounds.SoundSource.RECORDS, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), 3.0f, f, this.getHandle().getRandom().nextLong()));
    }

    @Override
    public void playNote(Location loc, Instrument instrument, Note note) {
        if (this.getHandle().connection == null) return;

        String instrumentName = null;
        switch (instrument.ordinal()) {
            case 0:
                instrumentName = "harp";
                break;
            case 1:
                instrumentName = "basedrum";
                break;
            case 2:
                instrumentName = "snare";
                break;
            case 3:
                instrumentName = "hat";
                break;
            case 4:
                instrumentName = "bass";
                break;
            case 5:
                instrumentName = "flute";
                break;
            case 6:
                instrumentName = "bell";
                break;
            case 7:
                instrumentName = "guitar";
                break;
            case 8:
                instrumentName = "chime";
                break;
            case 9:
                instrumentName = "xylophone";
                break;
            case 10:
                instrumentName = "iron_xylophone";
                break;
            case 11:
                instrumentName = "cow_bell";
                break;
            case 12:
                instrumentName = "didgeridoo";
                break;
            case 13:
                instrumentName = "bit";
                break;
            case 14:
                instrumentName = "banjo";
                break;
            case 15:
                instrumentName = "pling";
                break;
            case 16:
                instrumentName = "xylophone";
                break;
        }
        float f = (float) Math.pow(2.0D, (note.getId() - 12.0D) / 12.0D);
        this.getHandle().connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(CraftSound.getSoundEffect("block.note_block." + instrumentName)), net.minecraft.sounds.SoundSource.RECORDS, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), 3.0f, f, this.getHandle().getRandom().nextLong()));
    }

    @Override
    public void playSound(Location loc, Sound sound, float volume, float pitch) {
        this.playSound(loc, sound, org.bukkit.SoundCategory.MASTER, volume, pitch);
    }

    @Override
    public void playSound(Location loc, String sound, float volume, float pitch) {
        this.playSound(loc, sound, org.bukkit.SoundCategory.MASTER, volume, pitch);
    }

    @Override
    public void playSound(Location loc, Sound sound, org.bukkit.SoundCategory category, float volume, float pitch) {
        if (loc == null || sound == null || category == null || this.getHandle().connection == null) return;

        ClientboundSoundPacket packet = new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(CraftSound.getSoundEffect(sound)), net.minecraft.sounds.SoundSource.valueOf(category.name()), loc.getX(), loc.getY(), loc.getZ(), volume, pitch, this.getHandle().getRandom().nextLong());
        this.getHandle().connection.send(packet);
    }

    @Override
    public void playSound(Location loc, String sound, org.bukkit.SoundCategory category, float volume, float pitch) {
        if (loc == null || sound == null || category == null || this.getHandle().connection == null) return;

        ClientboundSoundPacket packet = new ClientboundSoundPacket(Holder.direct(SoundEvent.createVariableRangeEvent(new ResourceLocation(sound))), net.minecraft.sounds.SoundSource.valueOf(category.name()), loc.getX(), loc.getY(), loc.getZ(), volume, pitch, this.getHandle().getRandom().nextLong());
        this.getHandle().connection.send(packet);
    }

    @Override
    public void playSound(org.bukkit.entity.Entity entity, Sound sound, float volume, float pitch) {
        this.playSound(entity, sound, org.bukkit.SoundCategory.MASTER, volume, pitch);
    }

    @Override
    public void playSound(org.bukkit.entity.Entity entity, String sound, float volume, float pitch) {
        this.playSound(entity, sound, org.bukkit.SoundCategory.MASTER, volume, pitch);
    }

    @Override
    public void playSound(org.bukkit.entity.Entity entity, Sound sound, org.bukkit.SoundCategory category, float volume, float pitch) {
        if (!(entity instanceof CraftEntity craftEntity) || sound == null || category == null || this.getHandle().connection == null) return;

        ClientboundSoundEntityPacket packet = new ClientboundSoundEntityPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(CraftSound.getSoundEffect(sound)), net.minecraft.sounds.SoundSource.valueOf(category.name()), craftEntity.getHandle(), volume, pitch, this.getHandle().getRandom().nextLong());
        this.getHandle().connection.send(packet);
    }

    @Override
    public void playSound(org.bukkit.entity.Entity entity, String sound, org.bukkit.SoundCategory category, float volume, float pitch) {
        if (!(entity instanceof CraftEntity craftEntity) || sound == null || category == null || this.getHandle().connection == null) return;

        ClientboundSoundEntityPacket packet = new ClientboundSoundEntityPacket(Holder.direct(SoundEvent.createVariableRangeEvent(new ResourceLocation(sound))), net.minecraft.sounds.SoundSource.valueOf(category.name()), craftEntity.getHandle(), volume, pitch, this.getHandle().getRandom().nextLong());
        this.getHandle().connection.send(packet);
    }

    @Override
    public void stopSound(Sound sound) {
        this.stopSound(sound, null);
    }

    @Override
    public void stopSound(String sound) {
        this.stopSound(sound, null);
    }

    @Override
    public void stopSound(Sound sound, org.bukkit.SoundCategory category) {
        this.stopSound(sound.getKey().getKey(), category);
    }

    @Override
    public void stopSound(String sound, org.bukkit.SoundCategory category) {
        if (this.getHandle().connection == null) return;

        this.getHandle().connection.send(new ClientboundStopSoundPacket(new ResourceLocation(sound), category == null ? net.minecraft.sounds.SoundSource.MASTER : net.minecraft.sounds.SoundSource.valueOf(category.name())));
    }

    @Override
    public void stopSound(org.bukkit.SoundCategory category) {
        if (this.getHandle().connection == null) return;

        this.getHandle().connection.send(new ClientboundStopSoundPacket(null, net.minecraft.sounds.SoundSource.valueOf(category.name())));
    }

    @Override
    public void stopAllSounds() {
        if (this.getHandle().connection == null) return;

        this.getHandle().connection.send(new ClientboundStopSoundPacket(null, null));
    }

    @Override
    public void playEffect(Location loc, Effect effect, int data) {
        if (this.getHandle().connection == null) return;

        int packetData = effect.getId();
        ClientboundLevelEventPacket packet = new ClientboundLevelEventPacket(packetData, new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()), data, false);
        this.getHandle().connection.send(packet);
    }

    @Override
    public <T> void playEffect(Location loc, Effect effect, T data) {
        if (data != null) {
            Validate.isTrue(effect.getData() != null && effect.getData().isAssignableFrom(data.getClass()), "Wrong kind of data for this effect!");
        } else {
            // Special case: the axis is optional for ELECTRIC_SPARK
            Validate.isTrue(effect.getData() == null || effect == Effect.ELECTRIC_SPARK, "Wrong kind of data for this effect!");
        }

        int datavalue = CraftEffect.getDataValue(effect, data);
        this.playEffect(loc, effect, datavalue);
    }

    @Override
    public boolean breakBlock(Block block) {
        Preconditions.checkArgument(block != null, "Block cannot be null");
        Preconditions.checkArgument(block.getWorld().equals(getWorld()), "Cannot break blocks across worlds");

        return this.getHandle().gameMode.destroyBlock(new BlockPos(block.getX(), block.getY(), block.getZ()));
    }

    @Override
    public void sendBlockChange(Location loc, Material material, byte data) {
        if (this.getHandle().connection == null) return;

        ClientboundBlockUpdatePacket packet = new ClientboundBlockUpdatePacket(new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()), CraftMagicNumbers.getBlock(material, data));
        this.getHandle().connection.send(packet);
    }

    @Override
    public void sendBlockChange(Location loc, BlockData block) {
        if (this.getHandle().connection == null) return;

        ClientboundBlockUpdatePacket packet = new ClientboundBlockUpdatePacket(new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()), ((CraftBlockData) block).getState());
        this.getHandle().connection.send(packet);
    }

    @Override
    public void sendBlockChanges(Collection<BlockState> blocks, boolean suppressLightUpdates) {
        Preconditions.checkArgument(blocks != null, "blocks must not be null");

        if (this.getHandle().connection == null || blocks.isEmpty()) {
            return;
        }

        Map<SectionPos, ChunkSectionChanges> changes = new HashMap<>();
        for (BlockState state : blocks) {
            CraftBlockState cstate = (CraftBlockState) state;
            BlockPos blockPosition = cstate.getPosition();

            // The coordinates of the chunk section in which the block is located, aka chunk x, y, and z
            SectionPos sectionPosition = SectionPos.of(blockPosition);

            // Push the block change position and block data to the final change map
            ChunkSectionChanges sectionChanges = changes.computeIfAbsent(sectionPosition, (ignore) -> new ChunkSectionChanges());

            sectionChanges.positions().add(SectionPos.sectionRelativePos(blockPosition));
            sectionChanges.blockData().add(cstate.getHandle());
        }

        // Construct the packets using the data allocated above and send then to the players
        for (Map.Entry<SectionPos, ChunkSectionChanges> entry : changes.entrySet()) {
            ChunkSectionChanges chunkChanges = entry.getValue();
            ClientboundSectionBlocksUpdatePacket packet = new ClientboundSectionBlocksUpdatePacket(entry.getKey(), chunkChanges.positions(), chunkChanges.blockData().toArray(net.minecraft.world.level.block.state.BlockState[]::new), suppressLightUpdates);
            this.getHandle().connection.send(packet);
        }
    }

    private record ChunkSectionChanges(ShortSet positions, List<net.minecraft.world.level.block.state.BlockState> blockData) {

        public ChunkSectionChanges() {
            this(new ShortArraySet(), new ArrayList<>());
        }
    }

    @Override
    public void sendBlockDamage(Location loc, float progress) {
        this.sendBlockDamage(loc, progress, getEntityId());
    }

    @Override
    public void sendBlockDamage(Location loc, float progress, org.bukkit.entity.Entity source) {
        Preconditions.checkArgument(source != null, "source must not be null");
        this.sendBlockDamage(loc, progress, source.getEntityId());
    }

    @Override
    public void sendBlockDamage(Location loc, float progress, int sourceId) {
        Preconditions.checkArgument(loc != null, "loc must not be null");
        Preconditions.checkArgument(progress >= 0.0 && progress <= 1.0, "progress must be between 0.0 and 1.0 (inclusive)");

        if (this.getHandle().connection == null) return;

        int stage = (int) (9 * progress); // There are 0 - 9 damage states
        if (progress == 0.0F) {
            stage = -1; // The protocol states that any other value will reset the damage, which this API promises
        }

        ClientboundBlockDestructionPacket packet = new ClientboundBlockDestructionPacket(sourceId, new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()), stage);
        this.getHandle().connection.send(packet);
    }

    @Override
    public void sendSignChange(Location loc, String[] lines) {
        this.sendSignChange(loc, lines, DyeColor.BLACK);
    }

    @Override
    public void sendSignChange(Location loc, String[] lines, DyeColor dyeColor) {
        this.sendSignChange(loc, lines, dyeColor, false);
    }

    @Override
    public void sendSignChange(Location loc, String[] lines, DyeColor dyeColor, boolean hasGlowingText) {
        if (this.getHandle().connection == null) {
            return;
        }

        if (lines == null) {
            lines = new String[4];
        }

        Validate.notNull(loc, "Location can not be null");
        Validate.notNull(dyeColor, "DyeColor can not be null");
        if (lines.length < 4) {
            throw new IllegalArgumentException("Must have at least 4 lines");
        }

        Component[] components = CraftSign.sanitizeLines(lines);
        SignBlockEntity sign = new SignBlockEntity(new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()), Blocks.OAK_SIGN.defaultBlockState());
        sign.setColor(net.minecraft.world.item.DyeColor.byId(dyeColor.getWoolData()));
        sign.setHasGlowingText(hasGlowingText);
        for (int i = 0; i < components.length; i++) {
            sign.setMessage(i, components[i]);
        }

        this.getHandle().connection.send(sign.getUpdatePacket());
    }

    @Override
    public void sendEquipmentChange(LivingEntity entity, EquipmentSlot slot, ItemStack item) {
        this.sendEquipmentChange(entity, Map.of(slot, item));
    }

    @Override
    public void sendEquipmentChange(LivingEntity entity, Map<EquipmentSlot, ItemStack> items) {
        Preconditions.checkArgument(entity != null, "entity must not be null");
        Preconditions.checkArgument(items != null, "items must not be null");

        if (this.getHandle().connection == null) {
            return;
        }

        List<Pair<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack>> equipment = new ArrayList<>(items.size());
        for (Map.Entry<EquipmentSlot, ItemStack> entry : items.entrySet()) {
            EquipmentSlot slot = entry.getKey();
            Preconditions.checkArgument(slot != null, "Cannot set null EquipmentSlot");

            equipment.add(new Pair<>(CraftEquipmentSlot.getNMS(slot), CraftItemStack.asNMSCopy(entry.getValue())));
        }

        this.getHandle().connection.send(new ClientboundSetEquipmentPacket(entity.getEntityId(), equipment));
    }

    @Override
    public WorldBorder getWorldBorder() {
        return this.clientWorldBorder;
    }

    @Override
    public void setWorldBorder(WorldBorder border) {
        CraftWorldBorder craftBorder = (CraftWorldBorder) border;

        if (border != null && !craftBorder.isVirtual() && !craftBorder.getWorld().equals(getWorld())) {
            throw new UnsupportedOperationException("Cannot set player world border to that of another world");
        }

        // Nullify the old client-sided world border listeners so that calls to it will not affect this player
        if (this.clientWorldBorder != null) {
            this.clientWorldBorder.getHandle().removeListener(clientWorldBorderListener);
        }

        net.minecraft.world.level.border.WorldBorder newWorldBorder;
        if (craftBorder == null || !craftBorder.isVirtual()) {
            this.clientWorldBorder = null;
            newWorldBorder = ((CraftWorldBorder) getWorld().getWorldBorder()).getHandle();
        } else {
            this.clientWorldBorder = craftBorder;
            this.clientWorldBorder.getHandle().addListener(clientWorldBorderListener);
            newWorldBorder = this.clientWorldBorder.getHandle();
        }

        // Send all world border update packets to the player
        ServerGamePacketListenerImpl connection = this.getHandle().connection;
        connection.send(new ClientboundSetBorderSizePacket(newWorldBorder));
        connection.send(new ClientboundSetBorderLerpSizePacket(newWorldBorder));
        connection.send(new ClientboundSetBorderCenterPacket(newWorldBorder));
        connection.send(new ClientboundSetBorderWarningDelayPacket(newWorldBorder));
        connection.send(new ClientboundSetBorderWarningDistancePacket(newWorldBorder));
    }

    private BorderChangeListener createWorldBorderListener() {
        return new BorderChangeListener() {
            @Override
            public void onBorderSizeSet(net.minecraft.world.level.border.WorldBorder border, double size) {
                CraftPlayer.this.getHandle().connection.send(new ClientboundSetBorderSizePacket(border));
            }

            @Override
            public void onBorderSizeLerping(net.minecraft.world.level.border.WorldBorder border, double fromSize, double toSize, long time) {
                CraftPlayer.this.getHandle().connection.send(new ClientboundSetBorderLerpSizePacket(border));
            }

            @Override
            public void onBorderCenterSet(net.minecraft.world.level.border.WorldBorder border, double centerX, double centerZ) {
                CraftPlayer.this.getHandle().connection.send(new ClientboundSetBorderCenterPacket(border));
            }

            @Override
            public void onBorderSetWarningTime(net.minecraft.world.level.border.WorldBorder border, int warningTime) {
                CraftPlayer.this.getHandle().connection.send(new ClientboundSetBorderWarningDelayPacket(border));
            }

            @Override
            public void onBorderSetWarningBlocks(net.minecraft.world.level.border.WorldBorder border, int warningBlockDistance) {
                CraftPlayer.this.getHandle().connection.send(new ClientboundSetBorderWarningDistancePacket(border));
            }

            @Override
            public void onBorderSetDamagePerBlock(net.minecraft.world.level.border.WorldBorder border, double damagePerBlock) {} // NO OP

            @Override
            public void onBorderSetDamageSafeZOne(net.minecraft.world.level.border.WorldBorder border, double safeZoneRadius) {} // NO OP
        };
    }

    public boolean hasClientWorldBorder() {
        return this.clientWorldBorder != null;
    }

    @Override
    public void sendMap(MapView map) {
        if (this.getHandle().connection == null) return;

        RenderData data = ((CraftMapView) map).render(this);
        Collection<MapDecoration> icons = new ArrayList<MapDecoration>();
        for (MapCursor cursor : data.cursors) {
            if (cursor.isVisible()) {
                icons.add(new MapDecoration(MapDecoration.Type.byIcon(cursor.getRawType()), cursor.getX(), cursor.getY(), cursor.getDirection(), CraftChatMessage.fromStringOrNull(cursor.getCaption())));
            }
        }

        ClientboundMapItemDataPacket packet = new ClientboundMapItemDataPacket(map.getId(), map.getScale().getValue(), map.isLocked(), icons, new MapItemSavedData.MapPatch(0, 0, 128, 128, data.buffer));
        this.getHandle().connection.send(packet);
    }

    @Override
    public void sendHurtAnimation(float yaw) {
        if (this.getHandle().connection == null) {
            return;
        }

        /*
         * Vanilla degrees state that 0 = left, 90 = front, 180 = right, and 270 = behind.
         * This makes no sense. We'll add 90 to it so that 0 = front, clockwise from there.
         */
        float actualYaw = yaw + 90;
        this.getHandle().connection.send(new ClientboundHurtAnimationPacket(getEntityId(), actualYaw));
    }

    @Override
    public void addCustomChatCompletions(Collection<String> completions) {
        this.sendCustomChatCompletionPacket(completions, ClientboundCustomChatCompletionsPacket.Action.ADD);
    }

    @Override
    public void removeCustomChatCompletions(Collection<String> completions) {
        this.sendCustomChatCompletionPacket(completions, ClientboundCustomChatCompletionsPacket.Action.REMOVE);
    }

    @Override
    public void setCustomChatCompletions(Collection<String> completions) {
        this.sendCustomChatCompletionPacket(completions, ClientboundCustomChatCompletionsPacket.Action.SET);
    }

    private void sendCustomChatCompletionPacket(Collection<String> completions, ClientboundCustomChatCompletionsPacket.Action action) {
        if (this.getHandle().connection == null) return;

        ClientboundCustomChatCompletionsPacket packet = new ClientboundCustomChatCompletionsPacket(action, new ArrayList<>(completions));
        this.getHandle().connection.send(packet);
    }

    @Override
    public void setRotation(float yaw, float pitch) {
        throw new UnsupportedOperationException("Cannot set rotation of players. Consider teleporting instead.");
    }

    @Override
    public boolean teleport(Location location, PlayerTeleportEvent.TeleportCause cause) {
        Preconditions.checkArgument(location != null, "location");
        Preconditions.checkArgument(location.getWorld() != null, "location.world");
        location.checkFinite();

        ServerPlayer entity = this.getHandle();

        if (this.getHealth() == 0 || entity.isRemoved()) {
            return false;
        }

        if (entity.connection == null) {
           return false;
        }

        if (entity.isVehicle()) {
            return false;
        }

        // From = Players current Location
        Location from = this.getLocation();
        // To = Players new Location if Teleport is Successful
        Location to = location;
        // Create & Call the Teleport Event.
        PlayerTeleportEvent event = new PlayerTeleportEvent(this, from, to, cause);
        server.getPluginManager().callEvent(event);

        // Return False to inform the Plugin that the Teleport was unsuccessful/cancelled.
        if (event.isCancelled()) {
            return false;
        }

        // If this player is riding another entity, we must dismount before teleporting.
        entity.stopRiding();

        // SPIGOT-5509: Wakeup, similar to riding
        if (this.isSleeping()) {
            this.wakeup(false);
        }

        // Update the From Location
        from = event.getFrom();
        // Grab the new To Location dependent on whether the event was cancelled.
        to = event.getTo();
        // Grab the To and From World Handles.
        ServerLevel fromWorld = ((CraftWorld) from.getWorld()).getHandle();
        ServerLevel toWorld = ((CraftWorld) to.getWorld()).getHandle();

        // Close any foreign inventory
        if (this.getHandle().containerMenu != this.getHandle().inventoryMenu) {
            this.getHandle().closeContainer();
        }

        // Check if the fromWorld and toWorld are the same.
        if (fromWorld == toWorld) {
            entity.connection.teleport(to);
        } else {
            // The respawn reason should never be used if the passed location is non null.
            server.getHandle().respawn(entity, toWorld, true, to, true, null);
        }
        return true;
    }

    @Override
    public void setSneaking(boolean sneak) {
        this.getHandle().setShiftKeyDown(sneak);
    }

    @Override
    public boolean isSneaking() {
        return this.getHandle().isShiftKeyDown();
    }

    @Override
    public boolean isSprinting() {
        return this.getHandle().isSprinting();
    }

    @Override
    public void setSprinting(boolean sprinting) {
        this.getHandle().setSprinting(sprinting);
    }

    @Override
    public void loadData() {
        server.getHandle().playerIo.load(this.getHandle());
    }

    @Override
    public void saveData() {
        server.getHandle().playerIo.save(this.getHandle());
    }

    @Deprecated
    @Override
    public void updateInventory() {
        this.getHandle().containerMenu.sendAllDataToRemote();
    }

    @Override
    public void setSleepingIgnored(boolean isSleeping) {
        this.getHandle().fauxSleeping = isSleeping;
        ((CraftWorld) getWorld()).getHandle().updateSleepingPlayerList();
    }

    @Override
    public boolean isSleepingIgnored() {
        return this.getHandle().fauxSleeping;
    }

    @Override
    public Location getBedSpawnLocation() {
        ServerLevel world = this.getHandle().server.getLevel(this.getHandle().getRespawnDimension());
        BlockPos bed = this.getHandle().getRespawnPosition();

        if (world != null && bed != null) {
            Optional<Vec3> spawnLoc = net.minecraft.world.entity.player.Player.findRespawnPositionAndUseSpawnBlock(world, bed, this.getHandle().getRespawnAngle(), this.getHandle().isRespawnForced(), true);
            if (spawnLoc.isPresent()) {
                Vec3 vec = spawnLoc.get();
                return new Location(world.getWorld(), vec.x, vec.y, vec.z, this.getHandle().getRespawnAngle(), 0);
            }
        }
        return null;
    }

    @Override
    public void setBedSpawnLocation(Location location) {
        this.setBedSpawnLocation(location, false);
    }

    @Override
    public void setBedSpawnLocation(Location location, boolean override) {
        if (location == null) {
            this.getHandle().setRespawnPosition(null, null, 0.0F, override, false);
        } else {
            this.getHandle().setRespawnPosition(((CraftWorld) location.getWorld()).getHandle().dimension(), new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ()), location.getYaw(), override, false);
        }
    }

    @Override
    public Location getBedLocation() {
        Preconditions.checkState(isSleeping(), "Not sleeping");

        BlockPos bed = this.getHandle().getRespawnPosition();
        return new Location(getWorld(), bed.getX(), bed.getY(), bed.getZ());
    }

    @Override
    public boolean hasDiscoveredRecipe(NamespacedKey recipe) {
        Preconditions.checkArgument(recipe != null, "recipe cannot be null");
        return this.getHandle().getRecipeBook().contains(CraftNamespacedKey.toMinecraft(recipe));
    }

    @Override
    public Set<NamespacedKey> getDiscoveredRecipes() {
        ImmutableSet.Builder<NamespacedKey> bukkitRecipeKeys = ImmutableSet.builder();
        this.getHandle().getRecipeBook().known.forEach(key -> bukkitRecipeKeys.add(CraftNamespacedKey.fromMinecraft(key)));
        return bukkitRecipeKeys.build();
    }

    @Override
    public void incrementStatistic(Statistic statistic) {
        CraftStatistic.incrementStatistic(this.getHandle().getStats(), statistic);
    }

    @Override
    public void decrementStatistic(Statistic statistic) {
        CraftStatistic.decrementStatistic(this.getHandle().getStats(), statistic);
    }

    @Override
    public int getStatistic(Statistic statistic) {
        return CraftStatistic.getStatistic(this.getHandle().getStats(), statistic);
    }

    @Override
    public void incrementStatistic(Statistic statistic, int amount) {
        CraftStatistic.incrementStatistic(this.getHandle().getStats(), statistic, amount);
    }

    @Override
    public void decrementStatistic(Statistic statistic, int amount) {
        CraftStatistic.decrementStatistic(this.getHandle().getStats(), statistic, amount);
    }

    @Override
    public void setStatistic(Statistic statistic, int newValue) {
        CraftStatistic.setStatistic(this.getHandle().getStats(), statistic, newValue);
    }

    @Override
    public void incrementStatistic(Statistic statistic, Material material) {
        CraftStatistic.incrementStatistic(this.getHandle().getStats(), statistic, material);
    }

    @Override
    public void decrementStatistic(Statistic statistic, Material material) {
        CraftStatistic.decrementStatistic(this.getHandle().getStats(), statistic, material);
    }

    @Override
    public int getStatistic(Statistic statistic, Material material) {
        return CraftStatistic.getStatistic(this.getHandle().getStats(), statistic, material);
    }

    @Override
    public void incrementStatistic(Statistic statistic, Material material, int amount) {
        CraftStatistic.incrementStatistic(this.getHandle().getStats(), statistic, material, amount);
    }

    @Override
    public void decrementStatistic(Statistic statistic, Material material, int amount) {
        CraftStatistic.decrementStatistic(this.getHandle().getStats(), statistic, material, amount);
    }

    @Override
    public void setStatistic(Statistic statistic, Material material, int newValue) {
        CraftStatistic.setStatistic(this.getHandle().getStats(), statistic, material, newValue);
    }

    @Override
    public void incrementStatistic(Statistic statistic, EntityType entityType) {
        CraftStatistic.incrementStatistic(this.getHandle().getStats(), statistic, entityType);
    }

    @Override
    public void decrementStatistic(Statistic statistic, EntityType entityType) {
        CraftStatistic.decrementStatistic(this.getHandle().getStats(), statistic, entityType);
    }

    @Override
    public int getStatistic(Statistic statistic, EntityType entityType) {
        return CraftStatistic.getStatistic(this.getHandle().getStats(), statistic, entityType);
    }

    @Override
    public void incrementStatistic(Statistic statistic, EntityType entityType, int amount) {
        CraftStatistic.incrementStatistic(this.getHandle().getStats(), statistic, entityType, amount);
    }

    @Override
    public void decrementStatistic(Statistic statistic, EntityType entityType, int amount) {
        CraftStatistic.decrementStatistic(this.getHandle().getStats(), statistic, entityType, amount);
    }

    @Override
    public void setStatistic(Statistic statistic, EntityType entityType, int newValue) {
        CraftStatistic.setStatistic(this.getHandle().getStats(), statistic, entityType, newValue);
    }

    @Override
    public void setPlayerTime(long time, boolean relative) {
        this.getHandle().timeOffset = time;
        this.getHandle().relativeTime = relative;
    }

    @Override
    public long getPlayerTimeOffset() {
        return this.getHandle().timeOffset;
    }

    @Override
    public long getPlayerTime() {
        return this.getHandle().getPlayerTime();
    }

    @Override
    public boolean isPlayerTimeRelative() {
        return this.getHandle().relativeTime;
    }

    @Override
    public void resetPlayerTime() {
        this.setPlayerTime(0, true);
    }

    @Override
    public void setPlayerWeather(WeatherType type) {
        this.getHandle().setPlayerWeather(type, true);
    }

    @Override
    public WeatherType getPlayerWeather() {
        return this.getHandle().getPlayerWeather();
    }

    @Override
    public void resetPlayerWeather() {
        this.getHandle().resetPlayerWeather();
    }

    @Override
    public boolean isBanned() {
        return server.getBanList(BanList.Type.NAME).isBanned(getName());
    }

    @Override
    public boolean isWhitelisted() {
        return server.getHandle().getWhiteList().isWhiteListed(this.getProfile());
    }

    @Override
    public void setWhitelisted(boolean value) {
        if (value) {
            server.getHandle().getWhiteList().add(new UserWhiteListEntry(this.getProfile()));
        } else {
            server.getHandle().getWhiteList().remove(this.getProfile());
        }
    }

    @Override
    public void setGameMode(GameMode mode) {
        if (this.getHandle().connection == null) return;

        if (mode == null) {
            throw new IllegalArgumentException("Mode cannot be null");
        }

        this.getHandle().setGameMode(GameType.byId(mode.getValue()));
    }

    @Override
    public GameMode getGameMode() {
        return GameMode.getByValue(this.getHandle().gameMode.getGameModeForPlayer().getId());
    }

    @Override
    public GameMode getPreviousGameMode() {
        GameType previousGameMode = this.getHandle().gameMode.getPreviousGameModeForPlayer();

        return (previousGameMode == null) ? null : GameMode.getByValue(previousGameMode.getId());
    }

    @Override
    public void giveExp(int exp) {
        this.getHandle().giveExperiencePoints(exp);
    }

    @Override
    public void giveExpLevels(int levels) {
        this.getHandle().giveExperienceLevels(levels);
    }

    @Override
    public float getExp() {
        return this.getHandle().experienceProgress;
    }

    @Override
    public void setExp(float exp) {
        Preconditions.checkArgument(exp >= 0.0 && exp <= 1.0, "Experience progress must be between 0.0 and 1.0 (%s)", exp);
        this.getHandle().experienceProgress = exp;
        this.getHandle().lastSentExp = -1;
    }

    @Override
    public int getLevel() {
        return this.getHandle().experienceLevel;
    }

    @Override
    public void setLevel(int level) {
        Preconditions.checkArgument(level >= 0, "Experience level must not be negative (%s)", level);
        this.getHandle().experienceLevel = level;
        this.getHandle().lastSentExp = -1;
    }

    @Override
    public int getTotalExperience() {
        return this.getHandle().totalExperience;
    }

    @Override
    public void setTotalExperience(int exp) {
        Preconditions.checkArgument(exp >= 0, "Total experience points must not be negative (%s)", exp);
        this.getHandle().totalExperience = exp;
    }

    @Override
    public void sendExperienceChange(float progress) {
        this.sendExperienceChange(progress, this.getLevel());
    }

    @Override
    public void sendExperienceChange(float progress, int level) {
        Preconditions.checkArgument(progress >= 0.0 && progress <= 1.0, "Experience progress must be between 0.0 and 1.0 (%s)", progress);
        Preconditions.checkArgument(level >= 0, "Experience level must not be negative (%s)", level);

        if (this.getHandle().connection == null) {
            return;
        }

        ClientboundSetExperiencePacket packet = new ClientboundSetExperiencePacket(progress, this.getTotalExperience(), level);
        this.getHandle().connection.send(packet);
    }

    @Nullable
    private static WeakReference<Plugin> getPluginWeakReference(@Nullable Plugin plugin) {
        return (plugin == null) ? null : CraftPlayer.pluginWeakReferences.computeIfAbsent(plugin, WeakReference::new);
    }

    @Override
    @Deprecated
    public void hidePlayer(Player player) {
        this.hideEntity0(null, player);
    }

    @Override
    public void hidePlayer(Plugin plugin, Player player) {
        this.hideEntity(plugin, player);
    }

    @Override
    public void hideEntity(Plugin plugin, org.bukkit.entity.Entity entity) {
        Validate.notNull(plugin, "Plugin cannot be null");
        Validate.isTrue(plugin.isEnabled(), "Plugin attempted to hide player while disabled");

        this.hideEntity0(plugin, entity);
    }

    private void hideEntity0(@Nullable Plugin plugin, org.bukkit.entity.Entity entity) {
        Validate.notNull(entity, "hidden entity cannot be null");
        if (this.getHandle().connection == null) return;
        if (this.equals(entity)) return;

        boolean shouldHide;
        if (entity.isVisibleByDefault()) {
            shouldHide = this.addInvertedVisibility(plugin, entity);
        } else {
            shouldHide = this.removeInvertedVisibility(plugin, entity);
        }

        if (shouldHide) {
            this.untrackAndHideEntity(entity);
        }
    }

    private boolean addInvertedVisibility(@Nullable Plugin plugin, org.bukkit.entity.Entity entity) {
        Set<WeakReference<Plugin>> invertedPlugins = this.invertedVisibilityEntities.get(entity.getUniqueId());
        if (invertedPlugins != null) {
            // Some plugins are already inverting the entity. Just mark that this
            // plugin wants the entity inverted too and end.
            invertedPlugins.add(CraftPlayer.getPluginWeakReference(plugin));
            return false;
        }
        invertedPlugins = new HashSet<>();
        invertedPlugins.add(CraftPlayer.getPluginWeakReference(plugin));
        this.invertedVisibilityEntities.put(entity.getUniqueId(), invertedPlugins);

        return true;
    }

    private void untrackAndHideEntity(org.bukkit.entity.Entity entity) {
        // Remove this entity from the hidden player's EntityTrackerEntry
        ChunkMap tracker = ((ServerLevel) this.getHandle().level).getChunkSource().chunkMap;
        Entity other = ((CraftEntity) entity).getHandle();
        ChunkMap.TrackedEntity entry = tracker.entityMap.get(other.getId());
        if (entry != null) {
            entry.removePlayer(this.getHandle());
        }

        // Remove the hidden entity from this player user list, if they're on it
        if (other instanceof ServerPlayer) {
            ServerPlayer otherPlayer = (ServerPlayer) other;
            if (otherPlayer.sentListPacket) {
                this.getHandle().connection.send(new ClientboundPlayerInfoRemovePacket(List.of(otherPlayer.getUUID())));
            }
        }

        server.getPluginManager().callEvent(new PlayerHideEntityEvent(this, entity));
    }

    void resetAndHideEntity(org.bukkit.entity.Entity entity) {
        // SPIGOT-7312: Can't show/hide self
        if (this.equals(entity)) {
            return;
        }

        if (this.invertedVisibilityEntities.remove(entity.getUniqueId()) == null) {
            this.untrackAndHideEntity(entity);
        }
    }

    @Override
    @Deprecated
    public void showPlayer(Player player) {
        this.showEntity0(null, player);
    }

    @Override
    public void showPlayer(Plugin plugin, Player player) {
        this.showEntity(plugin, player);
    }

    @Override
    public void showEntity(Plugin plugin, org.bukkit.entity.Entity entity) {
        Validate.notNull(plugin, "Plugin cannot be null");
        // Don't require that plugin be enabled. A plugin must be allowed to call
        // showPlayer during its onDisable() method.
        this.showEntity0(plugin, entity);
    }

    private void showEntity0(@Nullable Plugin plugin, org.bukkit.entity.Entity entity) {
        Validate.notNull(entity, "shown entity cannot be null");
        if (this.getHandle().connection == null) return;
        if (this.equals(entity)) return;

        boolean shouldShow;
        if (entity.isVisibleByDefault()) {
            shouldShow = this.removeInvertedVisibility(plugin, entity);
        } else {
            shouldShow = this.addInvertedVisibility(plugin, entity);
        }

        if (shouldShow) {
            this.trackAndShowEntity(entity);
        }
    }

    private boolean removeInvertedVisibility(@Nullable Plugin plugin, org.bukkit.entity.Entity entity) {
        Set<WeakReference<Plugin>> invertedPlugins = this.invertedVisibilityEntities.get(entity.getUniqueId());
        if (invertedPlugins == null) {
            return false; // Entity isn't inverted
        }
        invertedPlugins.remove(CraftPlayer.getPluginWeakReference(plugin));
        if (!invertedPlugins.isEmpty()) {
            return false; // Some other plugins still want the entity inverted
        }
        this.invertedVisibilityEntities.remove(entity.getUniqueId());

        return true;
    }

    private void trackAndShowEntity(org.bukkit.entity.Entity entity) {
        ChunkMap tracker = ((ServerLevel) this.getHandle().level).getChunkSource().chunkMap;
        Entity other = ((CraftEntity) entity).getHandle();

        if (other instanceof ServerPlayer) {
            ServerPlayer otherPlayer = (ServerPlayer) other;
            this.getHandle().connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(otherPlayer)));
        }

        ChunkMap.TrackedEntity entry = tracker.entityMap.get(other.getId());
        if (entry != null && !entry.seenBy.contains(this.getHandle().connection)) {
            entry.updatePlayer(this.getHandle());
        }

        server.getPluginManager().callEvent(new PlayerShowEntityEvent(this, entity));
    }

    void resetAndShowEntity(org.bukkit.entity.Entity entity) {
        // SPIGOT-7312: Can't show/hide self
        if (this.equals(entity)) {
            return;
        }

        if (this.invertedVisibilityEntities.remove(entity.getUniqueId()) == null) {
            this.trackAndShowEntity(entity);
        }
    }

    public void onEntityRemove(Entity entity) {
        this.invertedVisibilityEntities.remove(entity.getUUID());
    }

    @Override
    public boolean canSee(Player player) {
        return this.canSee((org.bukkit.entity.Entity) player);
    }

    @Override
    public boolean canSee(org.bukkit.entity.Entity entity) {
        return this.equals(entity) || entity.isVisibleByDefault() ^ this.invertedVisibilityEntities.containsKey(entity.getUniqueId()); // SPIGOT-7312: Can always see self
    }

    public boolean canSee(UUID uuid) {
        org.bukkit.entity.Entity entity = getServer().getPlayer(uuid);
        if (entity == null) {
            entity = getServer().getEntity(uuid); // Also includes players, but check players first for efficiency
        }

        return (entity != null) ? this.canSee(entity) : false; // If we can't find it, we can't see it
    }

    @Override
    public Map<String, Object> serialize() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();

        result.put("name", getName());

        return result;
    }

    @Override
    public Player getPlayer() {
        return this;
    }

    @Override
    public ServerPlayer getHandle() {
        return (ServerPlayer) entity;
    }

    public void setHandle(final ServerPlayer entity) {
        super.setHandle(entity);
    }

    @Override
    public String toString() {
        return "CraftPlayer{" + "name=" + getName() + '}';
    }

    @Override
    public int hashCode() {
        if (this.hash == 0 || this.hash == 485) {
            this.hash = 97 * 5 + (this.getUniqueId() != null ? this.getUniqueId().hashCode() : 0);
        }
        return this.hash;
    }

    @Override
    public long getFirstPlayed() {
        return this.firstPlayed;
    }

    @Override
    public long getLastPlayed() {
        return this.lastPlayed;
    }

    @Override
    public boolean hasPlayedBefore() {
        return this.hasPlayedBefore;
    }

    public void setFirstPlayed(long firstPlayed) {
        this.firstPlayed = firstPlayed;
    }

    public void readExtraData(CompoundTag nbttagcompound) {
        this.hasPlayedBefore = true;
        if (nbttagcompound.contains("bukkit")) {
            CompoundTag data = nbttagcompound.getCompound("bukkit");

            if (data.contains("firstPlayed")) {
                this.firstPlayed = data.getLong("firstPlayed");
                this.lastPlayed = data.getLong("lastPlayed");
            }

            if (data.contains("newExp")) {
                ServerPlayer handle = this.getHandle();
                handle.newExp = data.getInt("newExp");
                handle.newTotalExp = data.getInt("newTotalExp");
                handle.newLevel = data.getInt("newLevel");
                handle.expToDrop = data.getInt("expToDrop");
                handle.keepLevel = data.getBoolean("keepLevel");
            }
        }
    }

    public void setExtraData(CompoundTag nbttagcompound) {
        if (!nbttagcompound.contains("bukkit")) {
            nbttagcompound.put("bukkit", new CompoundTag());
        }

        CompoundTag data = nbttagcompound.getCompound("bukkit");
        ServerPlayer handle = this.getHandle();
        data.putInt("newExp", handle.newExp);
        data.putInt("newTotalExp", handle.newTotalExp);
        data.putInt("newLevel", handle.newLevel);
        data.putInt("expToDrop", handle.expToDrop);
        data.putBoolean("keepLevel", handle.keepLevel);
        data.putLong("firstPlayed", this.getFirstPlayed());
        data.putLong("lastPlayed", System.currentTimeMillis());
        data.putString("lastKnownName", handle.getScoreboardName());
    }

    @Override
    public boolean beginConversation(Conversation conversation) {
        return this.conversationTracker.beginConversation(conversation);
    }

    @Override
    public void abandonConversation(Conversation conversation) {
        this.conversationTracker.abandonConversation(conversation, new ConversationAbandonedEvent(conversation, new ManuallyAbandonedConversationCanceller()));
    }

    @Override
    public void abandonConversation(Conversation conversation, ConversationAbandonedEvent details) {
        this.conversationTracker.abandonConversation(conversation, details);
    }

    @Override
    public void acceptConversationInput(String input) {
        this.conversationTracker.acceptConversationInput(input);
    }

    @Override
    public boolean isConversing() {
        return this.conversationTracker.isConversing();
    }

    @Override
    public void sendPluginMessage(Plugin source, String channel, byte[] message) {
        StandardMessenger.validatePluginMessage(server.getMessenger(), source, channel, message);
        if (this.getHandle().connection == null) return;

        if (this.channels.contains(channel)) {
            channel = StandardMessenger.validateAndCorrectChannel(channel);
            ClientboundCustomPayloadPacket packet = new ClientboundCustomPayloadPacket(new ResourceLocation(channel), new FriendlyByteBuf(Unpooled.wrappedBuffer(message)));
            this.getHandle().connection.send(packet);
        }
    }

    @Override
    public void setTexturePack(String url) {
        this.setResourcePack(url);
    }

    @Override
    public void setResourcePack(String url) {
        this.setResourcePack(url, null);
    }

    @Override
    public void setResourcePack(String url, byte[] hash) {
        this.setResourcePack(url, hash, false);
    }

    @Override
    public void setResourcePack(String url, byte[] hash, String prompt) {
        this.setResourcePack(url, hash, prompt, false);
    }

    @Override
    public void setResourcePack(String url, byte[] hash, boolean force) {
        this.setResourcePack(url, hash, null, force);
    }

    @Override
    public void setResourcePack(String url, byte[] hash, String prompt, boolean force) {
        Validate.notNull(url, "Resource pack URL cannot be null");

        if (hash != null) {
            Validate.isTrue(hash.length == 20, "Resource pack hash should be 20 bytes long but was " + hash.length);

            this.getHandle().sendTexturePack(url, BaseEncoding.base16().lowerCase().encode(hash), force, CraftChatMessage.fromStringOrNull(prompt, true));
        } else {
            this.getHandle().sendTexturePack(url, "", force, CraftChatMessage.fromStringOrNull(prompt, true));
        }
    }

    public void addChannel(String channel) {
        Preconditions.checkState(this.channels.size() < 128, "Cannot register channel '%s'. Too many channels registered!", channel);
        channel = StandardMessenger.validateAndCorrectChannel(channel);
        if (this.channels.add(channel)) {
            server.getPluginManager().callEvent(new PlayerRegisterChannelEvent(this, channel));
        }
    }

    public void removeChannel(String channel) {
        channel = StandardMessenger.validateAndCorrectChannel(channel);
        if (this.channels.remove(channel)) {
            server.getPluginManager().callEvent(new PlayerUnregisterChannelEvent(this, channel));
        }
    }

    @Override
    public Set<String> getListeningPluginChannels() {
        return ImmutableSet.copyOf(channels);
    }

    public void sendSupportedChannels() {
        if (this.getHandle().connection == null) return;
        Set<String> listening = server.getMessenger().getIncomingChannels();

        if (!listening.isEmpty()) {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();

            for (String channel : listening) {
                try {
                    stream.write(channel.getBytes("UTF8"));
                    stream.write((byte) 0);
                } catch (IOException ex) {
                    Logger.getLogger(CraftPlayer.class.getName()).log(Level.SEVERE, "Could not send Plugin Channel REGISTER to " + getName(), ex);
                }
            }

            this.getHandle().connection.send(new ClientboundCustomPayloadPacket(new ResourceLocation("register"), new FriendlyByteBuf(Unpooled.wrappedBuffer(stream.toByteArray()))));
        }
    }

    @Override
    public EntityType getType() {
        return EntityType.PLAYER;
    }

    @Override
    public void setMetadata(String metadataKey, MetadataValue newMetadataValue) {
        server.getPlayerMetadata().setMetadata(this, metadataKey, newMetadataValue);
    }

    @Override
    public List<MetadataValue> getMetadata(String metadataKey) {
        return server.getPlayerMetadata().getMetadata(this, metadataKey);
    }

    @Override
    public boolean hasMetadata(String metadataKey) {
        return server.getPlayerMetadata().hasMetadata(this, metadataKey);
    }

    @Override
    public void removeMetadata(String metadataKey, Plugin owningPlugin) {
        server.getPlayerMetadata().removeMetadata(this, metadataKey, owningPlugin);
    }

    @Override
    public boolean setWindowProperty(Property prop, int value) {
        AbstractContainerMenu container = this.getHandle().containerMenu;
        if (container.getBukkitView().getType() != prop.getType()) {
            return false;
        }
        container.setData(prop.getId(), value);
        return true;
    }

    public void disconnect(String reason) {
        this.conversationTracker.abandonAllConversations();
        perm.clearPermissions();
    }

    @Override
    public boolean isFlying() {
        return this.getHandle().getAbilities().flying;
    }

    @Override
    public void setFlying(boolean value) {
        if (!this.getAllowFlight() && value) {
            throw new IllegalArgumentException("Cannot make player fly if getAllowFlight() is false");
        }

        this.getHandle().getAbilities().flying = value;
        this.getHandle().onUpdateAbilities();
    }

    @Override
    public boolean getAllowFlight() {
        return this.getHandle().getAbilities().mayfly;
    }

    @Override
    public void setAllowFlight(boolean value) {
        if (this.isFlying() && !value) {
            this.getHandle().getAbilities().flying = false;
        }

        this.getHandle().getAbilities().mayfly = value;
        this.getHandle().onUpdateAbilities();
    }

    @Override
    public int getNoDamageTicks() {
        if (this.getHandle().spawnInvulnerableTime > 0) {
            return Math.max(this.getHandle().spawnInvulnerableTime, this.getHandle().invulnerableTime);
        } else {
            return this.getHandle().invulnerableTime;
        }
    }

    @Override
    public void setNoDamageTicks(int ticks) {
        super.setNoDamageTicks(ticks);
        this.getHandle().spawnInvulnerableTime = ticks; // SPIGOT-5921: Update both for players, like the getter above
    }

    @Override
    public void setFlySpeed(float value) {
        this.validateSpeed(value);
        ServerPlayer player = this.getHandle();
        player.getAbilities().flyingSpeed = value / 2f;
        player.onUpdateAbilities();

    }

    @Override
    public void setWalkSpeed(float value) {
        this.validateSpeed(value);
        ServerPlayer player = this.getHandle();
        player.getAbilities().walkingSpeed = value / 2f;
        player.onUpdateAbilities();
        this.getHandle().getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(player.getAbilities().walkingSpeed); // SPIGOT-5833: combination of the two in 1.16+
    }

    @Override
    public float getFlySpeed() {
        return (float) this.getHandle().getAbilities().flyingSpeed * 2f;
    }

    @Override
    public float getWalkSpeed() {
        return this.getHandle().getAbilities().walkingSpeed * 2f;
    }

    private void validateSpeed(float value) {
        if (value < 0) {
            if (value < -1f) {
                throw new IllegalArgumentException(value + " is too low");
            }
        } else {
            if (value > 1f) {
                throw new IllegalArgumentException(value + " is too high");
            }
        }
    }

    @Override
    public void setMaxHealth(double amount) {
        super.setMaxHealth(amount);
        this.health = Math.min(this.health, health);
        this.getHandle().resetSentInfo();
    }

    @Override
    public void resetMaxHealth() {
        super.resetMaxHealth();
        this.getHandle().resetSentInfo();
    }

    @Override
    public CraftScoreboard getScoreboard() {
        return this.server.getScoreboardManager().getPlayerBoard(this);
    }

    @Override
    public void setScoreboard(Scoreboard scoreboard) {
        Validate.notNull(scoreboard, "Scoreboard cannot be null");
        ServerGamePacketListenerImpl playerConnection = this.getHandle().connection;
        if (playerConnection == null) {
            throw new IllegalStateException("Cannot set scoreboard yet");
        }
        if (playerConnection.isDisconnected()) {
            // throw new IllegalStateException("Cannot set scoreboard for invalid CraftPlayer"); // Spigot - remove this as Mojang's semi asynchronous Netty implementation can lead to races
        }

        this.server.getScoreboardManager().setPlayerBoard(this, scoreboard);
    }

    @Override
    public void setHealthScale(double value) {
        Validate.isTrue((float) value > 0F, "Must be greater than 0");
        this.healthScale = value;
        this.scaledHealth = true;
        this.updateScaledHealth();
    }

    @Override
    public double getHealthScale() {
        return this.healthScale;
    }

    @Override
    public void setHealthScaled(boolean scale) {
        if (this.scaledHealth != (this.scaledHealth = scale)) {
            this.updateScaledHealth();
        }
    }

    @Override
    public boolean isHealthScaled() {
        return this.scaledHealth;
    }

    public float getScaledHealth() {
        return (float) (this.isHealthScaled() ? this.getHealth() * this.getHealthScale() / getMaxHealth() : this.getHealth());
    }

    @Override
    public double getHealth() {
        return this.health;
    }

    public void setRealHealth(double health) {
        this.health = health;
    }

    public void updateScaledHealth() {
        this.updateScaledHealth(true);
    }

    public void updateScaledHealth(boolean sendHealth) {
        AttributeMap attributemapserver = this.getHandle().getAttributes();
        Collection<AttributeInstance> set = attributemapserver.getSyncableAttributes();

        this.injectScaledMaxHealth(set, true);

        // SPIGOT-3813: Attributes before health
        if (this.getHandle().connection != null) {
            this.getHandle().connection.send(new ClientboundUpdateAttributesPacket(this.getHandle().getId(), set));
            if (sendHealth) {
                this.sendHealthUpdate();
            }
        }
        this.getHandle().getEntityData().set(net.minecraft.world.entity.LivingEntity.DATA_HEALTH_ID, (float) this.getScaledHealth());

        this.getHandle().maxHealthCache = getMaxHealth();
    }

    public void sendHealthUpdate() {
        this.getHandle().connection.send(new ClientboundSetHealthPacket(this.getScaledHealth(), this.getHandle().getFoodData().getFoodLevel(), this.getHandle().getFoodData().getSaturationLevel()));
    }

    public void injectScaledMaxHealth(Collection<AttributeInstance> collection, boolean force) {
        if (!this.scaledHealth && !force) {
            return;
        }
        for (AttributeInstance genericInstance : collection) {
            if (genericInstance.getAttribute() == Attributes.MAX_HEALTH) {
                collection.remove(genericInstance);
                break;
            }
        }
        AttributeInstance dummy = new AttributeInstance(Attributes.MAX_HEALTH, (attribute) -> { });
        // Spigot start
        double healthMod = this.scaledHealth ? this.healthScale : getMaxHealth();
        if ( healthMod >= Float.MAX_VALUE || healthMod <= 0 )
        {
            healthMod = 20; // Reset health
            getServer().getLogger().warning( getName() + " tried to crash the server with a large health attribute" );
        }
        dummy.setBaseValue(healthMod);
        // Spigot end
        collection.add(dummy);
    }

    @Override
    public org.bukkit.entity.Entity getSpectatorTarget() {
        Entity followed = this.getHandle().getCamera();
        return followed == this.getHandle() ? null : followed.getBukkitEntity();
    }

    @Override
    public void setSpectatorTarget(org.bukkit.entity.Entity entity) {
        Preconditions.checkArgument(this.getGameMode() == GameMode.SPECTATOR, "Player must be in spectator mode");
        this.getHandle().setCamera((entity == null) ? null : ((CraftEntity) entity).getHandle());
    }

    @Override
    public void sendTitle(String title, String subtitle) {
        this.sendTitle(title, subtitle, 10, 70, 20);
    }

    @Override
    public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        ClientboundSetTitlesAnimationPacket times = new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut);
        this.getHandle().connection.send(times);

        if (title != null) {
            ClientboundSetTitleTextPacket packetTitle = new ClientboundSetTitleTextPacket(CraftChatMessage.fromString(title)[0]);
            this.getHandle().connection.send(packetTitle);
        }

        if (subtitle != null) {
            ClientboundSetSubtitleTextPacket packetSubtitle = new ClientboundSetSubtitleTextPacket(CraftChatMessage.fromString(subtitle)[0]);
            this.getHandle().connection.send(packetSubtitle);
        }
    }

    @Override
    public void resetTitle() {
        ClientboundClearTitlesPacket packetReset = new ClientboundClearTitlesPacket(true);
        this.getHandle().connection.send(packetReset);
    }

    @Override
    public void spawnParticle(Particle particle, Location location, int count) {
        this.spawnParticle(particle, location.getX(), location.getY(), location.getZ(), count);
    }

    @Override
    public void spawnParticle(Particle particle, double x, double y, double z, int count) {
        this.spawnParticle(particle, x, y, z, count, null);
    }

    @Override
    public <T> void spawnParticle(Particle particle, Location location, int count, T data) {
        this.spawnParticle(particle, location.getX(), location.getY(), location.getZ(), count, data);
    }

    @Override
    public <T> void spawnParticle(Particle particle, double x, double y, double z, int count, T data) {
        this.spawnParticle(particle, x, y, z, count, 0, 0, 0, data);
    }

    @Override
    public void spawnParticle(Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ) {
        this.spawnParticle(particle, location.getX(), location.getY(), location.getZ(), count, offsetX, offsetY, offsetZ);
    }

    @Override
    public void spawnParticle(Particle particle, double x, double y, double z, int count, double offsetX, double offsetY, double offsetZ) {
        this.spawnParticle(particle, x, y, z, count, offsetX, offsetY, offsetZ, null);
    }

    @Override
    public <T> void spawnParticle(Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ, T data) {
        this.spawnParticle(particle, location.getX(), location.getY(), location.getZ(), count, offsetX, offsetY, offsetZ, data);
    }

    @Override
    public <T> void spawnParticle(Particle particle, double x, double y, double z, int count, double offsetX, double offsetY, double offsetZ, T data) {
        this.spawnParticle(particle, x, y, z, count, offsetX, offsetY, offsetZ, 1, data);
    }

    @Override
    public void spawnParticle(Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ, double extra) {
        this.spawnParticle(particle, location.getX(), location.getY(), location.getZ(), count, offsetX, offsetY, offsetZ, extra);
    }

    @Override
    public void spawnParticle(Particle particle, double x, double y, double z, int count, double offsetX, double offsetY, double offsetZ, double extra) {
        this.spawnParticle(particle, x, y, z, count, offsetX, offsetY, offsetZ, extra, null);
    }

    @Override
    public <T> void spawnParticle(Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ, double extra, T data) {
        this.spawnParticle(particle, location.getX(), location.getY(), location.getZ(), count, offsetX, offsetY, offsetZ, extra, data);
    }

    @Override
    public <T> void spawnParticle(Particle particle, double x, double y, double z, int count, double offsetX, double offsetY, double offsetZ, double extra, T data) {
        if (data != null && !particle.getDataType().isInstance(data)) {
            throw new IllegalArgumentException("data should be " + particle.getDataType() + " got " + data.getClass());
        }
        ClientboundLevelParticlesPacket packetplayoutworldparticles = new ClientboundLevelParticlesPacket(CraftParticle.toNMS(particle, data), true, (float) x, (float) y, (float) z, (float) offsetX, (float) offsetY, (float) offsetZ, (float) extra, count);
        this.getHandle().connection.send(packetplayoutworldparticles);

    }

    @Override
    public org.bukkit.advancement.AdvancementProgress getAdvancementProgress(org.bukkit.advancement.Advancement advancement) {
        Preconditions.checkArgument(advancement != null, "advancement");

        CraftAdvancement craft = (CraftAdvancement) advancement;
        PlayerAdvancements data = this.getHandle().getAdvancements();
        AdvancementProgress progress = data.getOrStartProgress(craft.getHandle());

        return new CraftAdvancementProgress(craft, data, progress);
    }

    @Override
    public int getClientViewDistance() {
        return (this.getHandle().clientViewDistance == null) ? Bukkit.getViewDistance() : this.getHandle().clientViewDistance;
    }

    @Override
    public int getPing() {
        return this.getHandle().latency;
    }

    @Override
    public String getLocale() {
        return this.getHandle().locale;
    }

    @Override
    public void updateCommands() {
        if (this.getHandle().connection == null) return;

        this.getHandle().server.getCommands().sendCommands(this.getHandle());
    }

    @Override
    public void openBook(ItemStack book) {
        Validate.isTrue(book != null, "book == null");
        Validate.isTrue(book.getType() == Material.WRITTEN_BOOK, "Book must be Material.WRITTEN_BOOK");

        ItemStack hand = getInventory().getItemInMainHand();
        getInventory().setItemInMainHand(book);
        this.getHandle().openItemGui(org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(book), net.minecraft.world.InteractionHand.MAIN_HAND);
        getInventory().setItemInMainHand(hand);
    }

    @Override
    public void openSign(Sign sign) {
        CraftSign.openSign(sign, this);
    }

    @Override
    public void showDemoScreen() {
        if (this.getHandle().connection == null) return;

        this.getHandle().connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.DEMO_EVENT, ClientboundGameEventPacket.DEMO_PARAM_INTRO));
    }

    @Override
    public boolean isAllowingServerListings() {
        return this.getHandle().allowsListing();
    }

    // Spigot start
    private final Player.Spigot spigot = new Player.Spigot()
    {

        @Override
        public InetSocketAddress getRawAddress()
        {
            return (InetSocketAddress) CraftPlayer.this.getHandle().connection.getRawAddress();
        }

        @Override
        public boolean getCollidesWithEntities() {
            return CraftPlayer.this.isCollidable();
        }

        @Override
        public void setCollidesWithEntities(boolean collides) {
            CraftPlayer.this.setCollidable(collides);
        }

        @Override
        public void respawn()
        {
            if ( CraftPlayer.this.getHealth() <= 0 && CraftPlayer.this.isOnline() )
            {
                server.getServer().getPlayerList().respawn( CraftPlayer.this.getHandle(), false, RespawnReason.PLUGIN );
            }
        }

        @Override
        public Set<Player> getHiddenPlayers()
        {
            Set<Player> ret = new HashSet<>();
            for ( Player p : getServer().getOnlinePlayers() )
            {
                if ( !CraftPlayer.this.canSee(p) )
                {
                    ret.add( p );
                }
            }

            return java.util.Collections.unmodifiableSet( ret );
        }

        @Override
        public void sendMessage(BaseComponent component) {
          sendMessage( new BaseComponent[] { component } );
        }

        @Override
        public void sendMessage(BaseComponent... components) {
           this.sendMessage(net.md_5.bungee.api.ChatMessageType.SYSTEM, components);
        }

        @Override
        public void sendMessage(UUID sender, BaseComponent component) {
            this.sendMessage(net.md_5.bungee.api.ChatMessageType.CHAT, sender, component);
        }

        @Override
        public void sendMessage(UUID sender, BaseComponent... components) {
            this.sendMessage(net.md_5.bungee.api.ChatMessageType.CHAT, sender, components);
        }

        @Override
        public void sendMessage(net.md_5.bungee.api.ChatMessageType position, BaseComponent component) {
            sendMessage( position, new BaseComponent[] { component } );
        }

        @Override
        public void sendMessage(net.md_5.bungee.api.ChatMessageType position, BaseComponent... components) {
            this.sendMessage(position, null, components);
        }

        @Override
        public void sendMessage(net.md_5.bungee.api.ChatMessageType position, UUID sender, BaseComponent component) {
            sendMessage( position, sender, new BaseComponent[] { component } );
        }

        @Override
        public void sendMessage(net.md_5.bungee.api.ChatMessageType position, UUID sender, BaseComponent... components) {
            if ( CraftPlayer.this.getHandle().connection == null ) return;

            CraftPlayer.this.getHandle().connection.send(new net.minecraft.network.protocol.game.ClientboundSystemChatPacket(components, position == net.md_5.bungee.api.ChatMessageType.ACTION_BAR));
        }
    };

    public Player.Spigot spigot()
    {
        return this.spigot;
    }
    // Spigot end
}
