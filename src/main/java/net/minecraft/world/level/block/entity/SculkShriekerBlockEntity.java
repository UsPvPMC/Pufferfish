package net.minecraft.world.level.block.entity;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Dynamic;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.OptionalInt;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.GameEventTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.SpawnUtil;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.warden.WardenSpawnTracker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.vibrations.VibrationListener;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

public class SculkShriekerBlockEntity extends BlockEntity implements VibrationListener.VibrationListenerConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int LISTENER_RADIUS = 8;
    private static final int WARNING_SOUND_RADIUS = 10;
    private static final int WARDEN_SPAWN_ATTEMPTS = 20;
    private static final int WARDEN_SPAWN_RANGE_XZ = 5;
    private static final int WARDEN_SPAWN_RANGE_Y = 6;
    private static final int DARKNESS_RADIUS = 40;
    private static final Int2ObjectMap<SoundEvent> SOUND_BY_LEVEL = Util.make(new Int2ObjectOpenHashMap<>(), (warningSounds) -> {
        warningSounds.put(1, SoundEvents.WARDEN_NEARBY_CLOSE);
        warningSounds.put(2, SoundEvents.WARDEN_NEARBY_CLOSER);
        warningSounds.put(3, SoundEvents.WARDEN_NEARBY_CLOSEST);
        warningSounds.put(4, SoundEvents.WARDEN_LISTENING_ANGRY);
    });
    private static final int SHRIEKING_TICKS = 90;
    public int warningLevel;
    private VibrationListener listener = new VibrationListener(new BlockPositionSource(this.worldPosition), 8, this);

    public SculkShriekerBlockEntity(BlockPos pos, BlockState state) {
        super(BlockEntityType.SCULK_SHRIEKER, pos, state);
    }

    public VibrationListener getListener() {
        return this.listener;
    }

    @Override
    public void load(CompoundTag nbt) {
        super.load(nbt);
        if (nbt.contains("warning_level", 99)) {
            this.warningLevel = nbt.getInt("warning_level");
        }

        if (nbt.contains("listener", 10)) {
            VibrationListener.codec(this).parse(new Dynamic<>(NbtOps.INSTANCE, nbt.getCompound("listener"))).resultOrPartial(LOGGER::error).ifPresent((vibrationListener) -> {
                this.listener = vibrationListener;
            });
        }

    }

    @Override
    protected void saveAdditional(CompoundTag nbt) {
        super.saveAdditional(nbt);
        nbt.putInt("warning_level", this.warningLevel);
        VibrationListener.codec(this).encodeStart(NbtOps.INSTANCE, this.listener).resultOrPartial(LOGGER::error).ifPresent((tag) -> {
            nbt.put("listener", tag);
        });
    }

    @Override
    public TagKey<GameEvent> getListenableEvents() {
        return GameEventTags.SHRIEKER_CAN_LISTEN;
    }

    @Override
    public boolean shouldListen(ServerLevel world, GameEventListener listener, BlockPos pos, GameEvent event, GameEvent.Context emitter) {
        return !this.getBlockState().getValue(SculkShriekerBlock.SHRIEKING) && tryGetPlayer(emitter.sourceEntity()) != null;
    }

    @Nullable
    public static ServerPlayer tryGetPlayer(@Nullable Entity entity) {
        // Paper start - ensure level is the same for sculk events
        final ServerPlayer player = tryGetPlayer0(entity);
        return player != null && player.level == entity.level ? player : null;
    }
    @Nullable
    private static ServerPlayer tryGetPlayer0(@Nullable Entity entity) {
        // Paper end
        if (entity instanceof ServerPlayer serverPlayer) {
            return serverPlayer;
        } else {
            if (entity != null) {
                LivingEntity serverPlayer4 = entity.getControllingPassenger();
                if (serverPlayer4 instanceof ServerPlayer) {
                    ServerPlayer serverPlayer2 = (ServerPlayer)serverPlayer4;
                    return serverPlayer2;
                }
            }

            if (entity instanceof Projectile projectile) {
                Entity var3 = projectile.getOwner();
                if (var3 instanceof ServerPlayer serverPlayer3) {
                    return serverPlayer3;
                }
            }

            if (entity instanceof ItemEntity itemEntity) {
                Entity var9 = itemEntity.getOwner();
                if (var9 instanceof ServerPlayer serverPlayer4) {
                    return serverPlayer4;
                }
            }

            return null;
        }
    }

    @Override
    public void onSignalReceive(ServerLevel world, GameEventListener listener, BlockPos pos, GameEvent event, @Nullable Entity entity, @Nullable Entity sourceEntity, float distance) {
        this.tryShriek(world, tryGetPlayer(sourceEntity != null ? sourceEntity : entity));
    }

    public void tryShriek(ServerLevel world, @Nullable ServerPlayer player) {
        if (player != null) {
            BlockState blockState = this.getBlockState();
            if (!blockState.getValue(SculkShriekerBlock.SHRIEKING)) {
                this.warningLevel = 0;
                if (!this.canRespond(world) || this.tryToWarn(world, player)) {
                    this.shriek(world, player);
                }
            }
        }
    }

    private boolean tryToWarn(ServerLevel world, ServerPlayer player) {
        OptionalInt optionalInt = WardenSpawnTracker.tryWarn(world, this.getBlockPos(), player);
        optionalInt.ifPresent((warningLevel) -> {
            this.warningLevel = warningLevel;
        });
        return optionalInt.isPresent();
    }

    private void shriek(ServerLevel world, @Nullable Entity entity) {
        BlockPos blockPos = this.getBlockPos();
        BlockState blockState = this.getBlockState();
        world.setBlock(blockPos, blockState.setValue(SculkShriekerBlock.SHRIEKING, Boolean.valueOf(true)), 2);
        world.scheduleTick(blockPos, blockState.getBlock(), 90);
        world.levelEvent(3007, blockPos, 0);
        world.gameEvent(GameEvent.SHRIEK, blockPos, GameEvent.Context.of(entity));
    }

    private boolean canRespond(ServerLevel world) {
        return this.getBlockState().getValue(SculkShriekerBlock.CAN_SUMMON) && world.getDifficulty() != Difficulty.PEACEFUL && world.getGameRules().getBoolean(GameRules.RULE_DO_WARDEN_SPAWNING);
    }

    public void tryRespond(ServerLevel world) {
        if (this.canRespond(world) && this.warningLevel > 0) {
            if (!this.trySummonWarden(world)) {
                this.playWardenReplySound();
            }

            Warden.applyDarknessAround(world, Vec3.atCenterOf(this.getBlockPos()), (Entity)null, 40);
        }

    }

    private void playWardenReplySound() {
        SoundEvent soundEvent = SOUND_BY_LEVEL.get(this.warningLevel);
        if (soundEvent != null) {
            BlockPos blockPos = this.getBlockPos();
            int i = blockPos.getX() + Mth.randomBetweenInclusive(this.level.random, -10, 10);
            int j = blockPos.getY() + Mth.randomBetweenInclusive(this.level.random, -10, 10);
            int k = blockPos.getZ() + Mth.randomBetweenInclusive(this.level.random, -10, 10);
            this.level.playSound((Player)null, (double)i, (double)j, (double)k, soundEvent, SoundSource.HOSTILE, 5.0F, 1.0F);
        }

    }

    private boolean trySummonWarden(ServerLevel world) {
        return this.warningLevel < 4 ? false : SpawnUtil.trySpawnMob(EntityType.WARDEN, MobSpawnType.TRIGGERED, world, this.getBlockPos(), 20, 5, 6, SpawnUtil.Strategy.ON_TOP_OF_COLLIDER).isPresent();
    }

    @Override
    public void onSignalSchedule() {
        this.setChanged();
    }
}
