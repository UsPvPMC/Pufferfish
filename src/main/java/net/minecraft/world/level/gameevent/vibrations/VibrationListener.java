package net.minecraft.world.level.gameevent.vibrations;

import com.google.common.annotations.VisibleForTesting;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.GameEventTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipBlockStateContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
// CraftBukkit start
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.event.block.BlockReceiveGameEvent;
// CraftBukkit end

public class VibrationListener implements GameEventListener {

    @VisibleForTesting
    public static final Object2IntMap<GameEvent> VIBRATION_FREQUENCY_FOR_EVENT = Object2IntMaps.unmodifiable((Object2IntMap) Util.make(new Object2IntOpenHashMap(), (object2intopenhashmap) -> {
        object2intopenhashmap.put(GameEvent.STEP, 1);
        object2intopenhashmap.put(GameEvent.ITEM_INTERACT_FINISH, 2);
        object2intopenhashmap.put(GameEvent.FLAP, 2);
        object2intopenhashmap.put(GameEvent.SWIM, 3);
        object2intopenhashmap.put(GameEvent.ELYTRA_GLIDE, 4);
        object2intopenhashmap.put(GameEvent.HIT_GROUND, 5);
        object2intopenhashmap.put(GameEvent.TELEPORT, 5);
        object2intopenhashmap.put(GameEvent.SPLASH, 6);
        object2intopenhashmap.put(GameEvent.ENTITY_SHAKE, 6);
        object2intopenhashmap.put(GameEvent.BLOCK_CHANGE, 6);
        object2intopenhashmap.put(GameEvent.NOTE_BLOCK_PLAY, 6);
        object2intopenhashmap.put(GameEvent.ENTITY_DISMOUNT, 6);
        object2intopenhashmap.put(GameEvent.PROJECTILE_SHOOT, 7);
        object2intopenhashmap.put(GameEvent.DRINK, 7);
        object2intopenhashmap.put(GameEvent.PRIME_FUSE, 7);
        object2intopenhashmap.put(GameEvent.ENTITY_MOUNT, 7);
        object2intopenhashmap.put(GameEvent.PROJECTILE_LAND, 8);
        object2intopenhashmap.put(GameEvent.EAT, 8);
        object2intopenhashmap.put(GameEvent.ENTITY_INTERACT, 8);
        object2intopenhashmap.put(GameEvent.ENTITY_DAMAGE, 8);
        object2intopenhashmap.put(GameEvent.EQUIP, 9);
        object2intopenhashmap.put(GameEvent.SHEAR, 9);
        object2intopenhashmap.put(GameEvent.ENTITY_ROAR, 9);
        object2intopenhashmap.put(GameEvent.BLOCK_CLOSE, 10);
        object2intopenhashmap.put(GameEvent.BLOCK_DEACTIVATE, 10);
        object2intopenhashmap.put(GameEvent.BLOCK_DETACH, 10);
        object2intopenhashmap.put(GameEvent.DISPENSE_FAIL, 10);
        object2intopenhashmap.put(GameEvent.BLOCK_OPEN, 11);
        object2intopenhashmap.put(GameEvent.BLOCK_ACTIVATE, 11);
        object2intopenhashmap.put(GameEvent.BLOCK_ATTACH, 11);
        object2intopenhashmap.put(GameEvent.ENTITY_PLACE, 12);
        object2intopenhashmap.put(GameEvent.BLOCK_PLACE, 12);
        object2intopenhashmap.put(GameEvent.FLUID_PLACE, 12);
        object2intopenhashmap.put(GameEvent.ENTITY_DIE, 13);
        object2intopenhashmap.put(GameEvent.BLOCK_DESTROY, 13);
        object2intopenhashmap.put(GameEvent.FLUID_PICKUP, 13);
        object2intopenhashmap.put(GameEvent.CONTAINER_CLOSE, 14);
        object2intopenhashmap.put(GameEvent.PISTON_CONTRACT, 14);
        object2intopenhashmap.put(GameEvent.PISTON_EXTEND, 15);
        object2intopenhashmap.put(GameEvent.CONTAINER_OPEN, 15);
        object2intopenhashmap.put(GameEvent.EXPLODE, 15);
        object2intopenhashmap.put(GameEvent.LIGHTNING_STRIKE, 15);
        object2intopenhashmap.put(GameEvent.INSTRUMENT_PLAY, 15);
    }));
    protected final PositionSource listenerSource;
    public int listenerRange;
    protected final VibrationListener.VibrationListenerConfig config;
    @Nullable
    protected VibrationInfo currentVibration;
    protected int travelTimeInTicks;
    private final VibrationSelector selectionStrategy;

    public static Codec<VibrationListener> codec(VibrationListener.VibrationListenerConfig callback) {
        return RecordCodecBuilder.create((instance) -> {
            return instance.group(PositionSource.CODEC.fieldOf("source").forGetter((vibrationlistener) -> {
                return vibrationlistener.listenerSource;
            }), ExtraCodecs.NON_NEGATIVE_INT.fieldOf("range").forGetter((vibrationlistener) -> {
                return vibrationlistener.listenerRange;
            }), VibrationInfo.CODEC.optionalFieldOf("event").forGetter((vibrationlistener) -> {
                return Optional.ofNullable(vibrationlistener.currentVibration);
            }), VibrationSelector.CODEC.fieldOf("selector").forGetter((vibrationlistener) -> {
                return vibrationlistener.selectionStrategy;
            }), ExtraCodecs.NON_NEGATIVE_INT.fieldOf("event_delay").orElse(0).forGetter((vibrationlistener) -> {
                return vibrationlistener.travelTimeInTicks;
            })).apply(instance, (positionsource, integer, optional, vibrationselector, integer1) -> {
                return new VibrationListener(positionsource, integer, callback, (VibrationInfo) optional.orElse(null), vibrationselector, integer1); // CraftBukkit - decompile error
            });
        });
    }

    private VibrationListener(PositionSource positionSource, int range, VibrationListener.VibrationListenerConfig callback, @Nullable VibrationInfo vibration, VibrationSelector selector, int delay) {
        this.listenerSource = positionSource;
        this.listenerRange = range;
        this.config = callback;
        this.currentVibration = vibration;
        this.travelTimeInTicks = delay;
        this.selectionStrategy = selector;
    }

    public VibrationListener(PositionSource positionSource, int range, VibrationListener.VibrationListenerConfig callback) {
        this(positionSource, range, callback, (VibrationInfo) null, new VibrationSelector(), 0);
    }

    public static int getGameEventFrequency(GameEvent event) {
        return VibrationListener.VIBRATION_FREQUENCY_FOR_EVENT.getOrDefault(event, 0);
    }

    public void tick(Level world) {
        if (world instanceof ServerLevel) {
            ServerLevel worldserver = (ServerLevel) world;

            if (this.currentVibration == null) {
                this.selectionStrategy.chosenCandidate(worldserver.getGameTime()).ifPresent((vibrationinfo) -> {
                    this.currentVibration = vibrationinfo;
                    Vec3 vec3d = this.currentVibration.pos();

                    this.travelTimeInTicks = Mth.floor(this.currentVibration.distance());
                    worldserver.sendParticles(new VibrationParticleOption(this.listenerSource, this.travelTimeInTicks), vec3d.x, vec3d.y, vec3d.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                    this.config.onSignalSchedule();
                    this.selectionStrategy.startOver();
                });
            }

            if (this.currentVibration != null) {
                --this.travelTimeInTicks;
                if (this.travelTimeInTicks <= 0) {
                    this.travelTimeInTicks = 0;
                    // CraftBukkit - decompile error
                    this.config.onSignalReceive(worldserver, this, BlockPos.containing(this.currentVibration.pos()), this.currentVibration.gameEvent(), (Entity) this.currentVibration.getEntity(worldserver).orElse(null), (Entity) this.currentVibration.getProjectileOwner(worldserver).orElse(null), this.currentVibration.distance());
                    this.currentVibration = null;
                }
            }
        }

    }

    @Override
    public PositionSource getListenerSource() {
        return this.listenerSource;
    }

    @Override
    public int getListenerRadius() {
        return this.listenerRange;
    }

    @Override
    public boolean handleGameEvent(ServerLevel world, GameEvent event, GameEvent.Context emitter, Vec3 emitterPos) {
        if (this.currentVibration != null) {
            return false;
        } else if (!this.config.isValidVibration(event, emitter)) {
            return false;
        } else {
            Optional<Vec3> optional = this.listenerSource.getPosition(world);

            if (optional.isEmpty()) {
                return false;
            } else {
                Vec3 vec3d1 = (Vec3) optional.get();

                // CraftBukkit start
                boolean defaultCancel = !this.config.shouldListen(world, this, BlockPos.containing(emitterPos), event, emitter);
                Entity entity = emitter.sourceEntity();
                BlockReceiveGameEvent event1 = new BlockReceiveGameEvent(org.bukkit.GameEvent.getByKey(CraftNamespacedKey.fromMinecraft(BuiltInRegistries.GAME_EVENT.getKey(event))), CraftBlock.at(world, BlockPos.containing(vec3d1)), (entity == null) ? null : entity.getBukkitEntity());
                event1.setCancelled(defaultCancel);
                world.getCraftServer().getPluginManager().callEvent(event1);
                if (event1.isCancelled()) {
                    // CraftBukkit end
                    return false;
                } else if (VibrationListener.isOccluded(world, emitterPos, vec3d1)) {
                    return false;
                } else {
                    this.scheduleVibration(world, event, emitter, emitterPos, vec3d1);
                    return true;
                }
            }
        }
    }

    public void forceGameEvent(ServerLevel world, GameEvent event, GameEvent.Context emitter, Vec3 emitterPos) {
        this.listenerSource.getPosition(world).ifPresent((vec3d1) -> {
            this.scheduleVibration(world, event, emitter, emitterPos, vec3d1);
        });
    }

    public void scheduleVibration(ServerLevel world, GameEvent event, GameEvent.Context emitter, Vec3 emitterPos, Vec3 listenerPos) {
        this.selectionStrategy.addCandidate(new VibrationInfo(event, (float) emitterPos.distanceTo(listenerPos), emitterPos, emitter.sourceEntity()), world.getGameTime());
    }

    private static boolean isOccluded(Level world, Vec3 start, Vec3 end) {
        Vec3 vec3d2 = new Vec3((double) Mth.floor(start.x) + 0.5D, (double) Mth.floor(start.y) + 0.5D, (double) Mth.floor(start.z) + 0.5D);
        Vec3 vec3d3 = new Vec3((double) Mth.floor(end.x) + 0.5D, (double) Mth.floor(end.y) + 0.5D, (double) Mth.floor(end.z) + 0.5D);
        Direction[] aenumdirection = Direction.values();
        int i = aenumdirection.length;

        for (int j = 0; j < i; ++j) {
            Direction enumdirection = aenumdirection[j];
            Vec3 vec3d4 = vec3d2.relative(enumdirection, 9.999999747378752E-6D);

            if (world.isBlockInLine(new ClipBlockStateContext(vec3d4, vec3d3, (iblockdata) -> {
                return iblockdata.is(BlockTags.OCCLUDES_VIBRATION_SIGNALS);
            })).getType() != HitResult.Type.BLOCK) {
                return false;
            }
        }

        return true;
    }

    public interface VibrationListenerConfig {

        default TagKey<GameEvent> getListenableEvents() {
            return GameEventTags.VIBRATIONS;
        }

        default boolean canTriggerAvoidVibration() {
            return false;
        }

        default boolean isValidVibration(GameEvent gameEvent, GameEvent.Context emitter) {
            if (!gameEvent.is(this.getListenableEvents())) {
                return false;
            } else {
                Entity entity = emitter.sourceEntity();

                if (entity != null) {
                    if (entity.isSpectator()) {
                        return false;
                    }

                    if (entity.isSteppingCarefully() && gameEvent.is(GameEventTags.IGNORE_VIBRATIONS_SNEAKING)) {
                        if (this.canTriggerAvoidVibration() && entity instanceof ServerPlayer) {
                            ServerPlayer entityplayer = (ServerPlayer) entity;

                            CriteriaTriggers.AVOID_VIBRATION.trigger(entityplayer);
                        }

                        return false;
                    }

                    if (entity.dampensVibrations()) {
                        return false;
                    }
                }

                return emitter.affectedState() != null ? !emitter.affectedState().is(BlockTags.DAMPENS_VIBRATIONS) : true;
            }
        }

        boolean shouldListen(ServerLevel world, GameEventListener listener, BlockPos pos, GameEvent event, GameEvent.Context emitter);

        void onSignalReceive(ServerLevel world, GameEventListener listener, BlockPos pos, GameEvent event, @Nullable Entity entity, @Nullable Entity sourceEntity, float distance);

        default void onSignalSchedule() {}
    }
}
