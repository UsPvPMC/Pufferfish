package net.minecraft.world.level.block.entity;

import com.google.common.annotations.VisibleForTesting;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SculkCatalystBlock;
import net.minecraft.world.level.block.SculkSpreader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.phys.Vec3;

public class SculkCatalystBlockEntity extends BlockEntity implements GameEventListener {

    private final BlockPositionSource blockPosSource;
    private final SculkSpreader sculkSpreader;

    public SculkCatalystBlockEntity(BlockPos pos, BlockState state) {
        super(BlockEntityType.SCULK_CATALYST, pos, state);
        this.blockPosSource = new BlockPositionSource(this.worldPosition);
        this.sculkSpreader = SculkSpreader.createLevelSpreader();
    }

    @Override
    public PositionSource getListenerSource() {
        return this.blockPosSource;
    }

    @Override
    public int getListenerRadius() {
        return 8;
    }

    @Override
    public GameEventListener.DeliveryMode getDeliveryMode() {
        return GameEventListener.DeliveryMode.BY_DISTANCE;
    }

    @Override
    public boolean handleGameEvent(ServerLevel world, GameEvent event, GameEvent.Context emitter, Vec3 emitterPos) {
        if (event == GameEvent.ENTITY_DIE) {
            Entity entity = emitter.sourceEntity();

            if (entity instanceof LivingEntity) {
                LivingEntity entityliving = (LivingEntity) entity;

                if (!entityliving.wasExperienceConsumed()) {
                    int i = entityliving.getExperienceReward();

                    if (entityliving.shouldDropExperience() && i > 0) {
                        this.sculkSpreader.addCursors(BlockPos.containing(emitterPos.relative(Direction.UP, 0.5D)), i);
                        this.tryAwardItSpreadsAdvancement(entityliving);
                    }

                    entityliving.skipDropExperience();
                    SculkCatalystBlock.bloom(world, this.worldPosition, this.getBlockState(), world.getRandom());
                }

                return true;
            }
        }

        return false;
    }

    private void tryAwardItSpreadsAdvancement(LivingEntity deadEntity) {
        LivingEntity entityliving1 = deadEntity.getLastHurtByMob();

        if (entityliving1 instanceof ServerPlayer) {
            ServerPlayer entityplayer = (ServerPlayer) entityliving1;
            DamageSource damagesource = deadEntity.getLastDamageSource() == null ? this.level.damageSources().playerAttack(entityplayer) : deadEntity.getLastDamageSource();

            CriteriaTriggers.KILL_MOB_NEAR_SCULK_CATALYST.trigger(entityplayer, deadEntity, damagesource);
        }

    }

    public static void serverTick(Level world, BlockPos pos, BlockState state, SculkCatalystBlockEntity blockEntity) {
        org.bukkit.craftbukkit.event.CraftEventFactory.sourceBlockOverride = blockEntity.getBlockPos(); // CraftBukkit - SPIGOT-7068: Add source block override, not the most elegant way but better than passing down a BlockPosition up to five methods deep.
        blockEntity.sculkSpreader.updateCursors(world, pos, world.getRandom(), true);
        org.bukkit.craftbukkit.event.CraftEventFactory.sourceBlockOverride = null; // CraftBukkit
    }

    @Override
    public void load(CompoundTag nbt) {
        super.load(nbt);
        this.sculkSpreader.load(nbt);
    }

    @Override
    protected void saveAdditional(CompoundTag nbt) {
        this.sculkSpreader.save(nbt);
        super.saveAdditional(nbt);
    }

    @VisibleForTesting
    public SculkSpreader getSculkSpreader() {
        return this.sculkSpreader;
    }
}
