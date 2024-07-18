package net.minecraft.world.entity.animal.sniffer;

import com.mojang.serialization.Dynamic;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.DebugPackets;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class Sniffer extends Animal {

    private static final int DIGGING_PARTICLES_DELAY_TICKS = 1700;
    private static final int DIGGING_PARTICLES_DURATION_TICKS = 6000;
    private static final int DIGGING_PARTICLES_AMOUNT = 30;
    private static final int DIGGING_DROP_SEED_OFFSET_TICKS = 120;
    private static final int SNIFFING_PROXIMITY_DISTANCE = 10;
    private static final int SNIFFER_BABY_AGE_TICKS = 48000;
    private static final EntityDataAccessor<Sniffer.State> DATA_STATE = SynchedEntityData.defineId(Sniffer.class, EntityDataSerializers.SNIFFER_STATE);
    private static final EntityDataAccessor<Integer> DATA_DROP_SEED_AT_TICK = SynchedEntityData.defineId(Sniffer.class, EntityDataSerializers.INT);
    public final AnimationState walkingAnimationState = new AnimationState();
    public final AnimationState panicAnimationState = new AnimationState();
    public final AnimationState feelingHappyAnimationState = new AnimationState();
    public final AnimationState scentingAnimationState = new AnimationState();
    public final AnimationState sniffingAnimationState = new AnimationState();
    public final AnimationState searchingAnimationState = new AnimationState();
    public final AnimationState diggingAnimationState = new AnimationState();
    public final AnimationState risingAnimationState = new AnimationState();

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes().add(Attributes.MOVEMENT_SPEED, 0.10000000149011612D).add(Attributes.MAX_HEALTH, 14.0D);
    }

    public Sniffer(EntityType<? extends Animal> type, Level world) {
        super(type, world);
        // this.entityData.define(Sniffer.DATA_STATE, Sniffer.a.IDLING); // CraftBukkit - moved down to appropriate location
        // this.entityData.define(Sniffer.DATA_DROP_SEED_AT_TICK, 0); // CraftBukkit - moved down to appropriate location
        this.getNavigation().setCanFloat(true);
        this.setPathfindingMalus(BlockPathTypes.WATER, -2.0F);
    }

    // CraftBukkit start - SPIGOT-7295: moved from constructor to appropriate location
    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(Sniffer.DATA_STATE, Sniffer.State.IDLING);
        this.entityData.define(Sniffer.DATA_DROP_SEED_AT_TICK, 0);
    }
    // CraftBukkit end

    @Override
    protected float getStandingEyeHeight(Pose pose, EntityDimensions dimensions) {
        return this.getDimensions(pose).height * 0.6F;
    }

    private boolean isMoving() {
        boolean flag = this.onGround || this.isInWaterOrBubble();

        return flag && this.getDeltaMovement().horizontalDistanceSqr() > 1.0E-6D;
    }

    private boolean isMovingInWater() {
        return this.isMoving() && this.isInWater() && !this.isUnderWater() && this.getDeltaMovement().horizontalDistanceSqr() > 9.999999999999999E-6D;
    }

    private boolean isMovingOnLand() {
        return this.isMoving() && !this.isUnderWater() && !this.isInWater();
    }

    public boolean isPanicking() {
        return this.brain.getMemory(MemoryModuleType.IS_PANICKING).isPresent();
    }

    public boolean canPlayDiggingSound() {
        return this.getState() == Sniffer.State.DIGGING || this.getState() == Sniffer.State.SEARCHING;
    }

    private BlockPos getHeadPosition() {
        Vec3 vec3d = this.position().add(this.getForward().scale(2.25D));

        return BlockPos.containing(vec3d.x(), this.getY(), vec3d.z());
    }

    public Sniffer.State getState() { // PAIL private -> public
        return (Sniffer.State) this.entityData.get(Sniffer.DATA_STATE);
    }

    private Sniffer setState(Sniffer.State state) {
        this.entityData.set(Sniffer.DATA_STATE, state);
        return this;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
        if (Sniffer.DATA_STATE.equals(data)) {
            Sniffer.State sniffer_a = this.getState();

            this.resetAnimations();
            switch (sniffer_a) {
                case SCENTING:
                    this.scentingAnimationState.startIfStopped(this.tickCount);
                    break;
                case SNIFFING:
                    this.sniffingAnimationState.startIfStopped(this.tickCount);
                    break;
                case SEARCHING:
                    this.searchingAnimationState.startIfStopped(this.tickCount);
                    break;
                case DIGGING:
                    this.diggingAnimationState.startIfStopped(this.tickCount);
                    break;
                case RISING:
                    this.risingAnimationState.startIfStopped(this.tickCount);
                    break;
                case FEELING_HAPPY:
                    this.feelingHappyAnimationState.startIfStopped(this.tickCount);
            }
        }

        super.onSyncedDataUpdated(data);
    }

    private void resetAnimations() {
        this.searchingAnimationState.stop();
        this.diggingAnimationState.stop();
        this.sniffingAnimationState.stop();
        this.risingAnimationState.stop();
        this.feelingHappyAnimationState.stop();
        this.scentingAnimationState.stop();
    }

    public Sniffer transitionTo(Sniffer.State state) {
        switch (state) {
            case SCENTING:
                this.playSound(SoundEvents.SNIFFER_SCENTING, 1.0F, 1.0F);
                this.setState(Sniffer.State.SCENTING);
                break;
            case SNIFFING:
                this.playSound(SoundEvents.SNIFFER_SNIFFING, 1.0F, 1.0F);
                this.setState(Sniffer.State.SNIFFING);
                break;
            case SEARCHING:
                this.setState(Sniffer.State.SEARCHING);
                break;
            case DIGGING:
                this.setState(Sniffer.State.DIGGING).onDiggingStart();
                break;
            case RISING:
                this.playSound(SoundEvents.SNIFFER_DIGGING_STOP, 1.0F, 1.0F);
                this.setState(Sniffer.State.RISING);
                break;
            case FEELING_HAPPY:
                this.playSound(SoundEvents.SNIFFER_HAPPY, 1.0F, 1.0F);
                this.setState(Sniffer.State.FEELING_HAPPY);
                break;
            case IDLING:
                this.setState(Sniffer.State.IDLING);
        }

        return this;
    }

    private Sniffer onDiggingStart() {
        this.entityData.set(Sniffer.DATA_DROP_SEED_AT_TICK, this.tickCount + 120);
        this.level.broadcastEntityEvent(this, (byte) 63);
        return this;
    }

    public Sniffer onDiggingComplete(boolean explored) {
        if (explored) {
            this.storeExploredPosition(this.getOnPos());
        }

        return this;
    }

    public Optional<BlockPos> calculateDigPosition() { // PAIL public
        return IntStream.range(0, 5).mapToObj((i) -> {
            return LandRandomPos.getPos(this, 10 + 2 * i, 3);
        }).filter(Objects::nonNull).map(BlockPos::containing).map(BlockPos::below).filter(this::canDig).findFirst();
    }

    @Override
    protected boolean canRide(Entity entity) {
        return false;
    }

    public boolean canDig() { // PAIL public
        return !this.isPanicking() && !this.isBaby() && !this.isInWater() && this.canDig(this.getHeadPosition().below());
    }

    private boolean canDig(BlockPos pos) {
        boolean flag;

        if (this.level.getBlockState(pos).is(BlockTags.SNIFFER_DIGGABLE_BLOCK) && this.level.getBlockState(pos.above()).isAir()) {
            Stream stream = this.getExploredPositions();

            Objects.requireNonNull(pos);
            if (stream.noneMatch(pos::equals)) {
                flag = true;
                return flag;
            }
        }

        flag = false;
        return flag;
    }

    private void dropSeed() {
        if (!this.level.isClientSide() && (Integer) this.entityData.get(Sniffer.DATA_DROP_SEED_AT_TICK) == this.tickCount) {
            ItemStack itemstack = new ItemStack(Items.TORCHFLOWER_SEEDS);
            BlockPos blockposition = this.getHeadPosition();
            ItemEntity entityitem = new ItemEntity(this.level, (double) blockposition.getX(), (double) blockposition.getY(), (double) blockposition.getZ(), itemstack);

            // CraftBukkit start - handle EntityDropItemEvent
            org.bukkit.event.entity.EntityDropItemEvent event = new org.bukkit.event.entity.EntityDropItemEvent(this.getBukkitEntity(), (org.bukkit.entity.Item) entityitem.getBukkitEntity());
            org.bukkit.Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return;
            }
            // CraftBukkit end
            entityitem.setDefaultPickUpDelay();
            this.level.addFreshEntity(entityitem);
            this.playSound(SoundEvents.SNIFFER_DROP_SEED, 1.0F, 1.0F);
        }
    }

    private Sniffer emitDiggingParticles(AnimationState diggingAnimationState) {
        boolean flag = diggingAnimationState.getAccumulatedTime() > 1700L && diggingAnimationState.getAccumulatedTime() < 6000L;

        if (flag) {
            BlockState iblockdata = this.getBlockStateOn();
            BlockPos blockposition = this.getHeadPosition();

            if (iblockdata.getRenderShape() != RenderShape.INVISIBLE) {
                for (int i = 0; i < 30; ++i) {
                    Vec3 vec3d = Vec3.atCenterOf(blockposition);

                    this.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, iblockdata), vec3d.x, vec3d.y, vec3d.z, 0.0D, 0.0D, 0.0D);
                }

                if (this.tickCount % 10 == 0) {
                    this.level.playLocalSound(this.getX(), this.getY(), this.getZ(), iblockdata.getSoundType().getHitSound(), this.getSoundSource(), 0.5F, 0.5F, false);
                }
            }
        }

        return this;
    }

    public Sniffer storeExploredPosition(BlockPos pos) { // PAIL private -> public
        List<BlockPos> list = (List) this.getExploredPositions().limit(20L).collect(Collectors.toList());

        list.add(0, pos);
        this.getBrain().setMemory(MemoryModuleType.SNIFFER_EXPLORED_POSITIONS, list); // CraftBukkit - decompile error
        return this;
    }

    public Stream<BlockPos> getExploredPositions() { // PAIL private -> public
        return this.getBrain().getMemory(MemoryModuleType.SNIFFER_EXPLORED_POSITIONS).stream().flatMap(Collection::stream);
    }

    @Override
    protected void jumpFromGround() {
        super.jumpFromGround();
        double d0 = this.moveControl.getSpeedModifier();

        if (d0 > 0.0D) {
            double d1 = this.getDeltaMovement().horizontalDistanceSqr();

            if (d1 < 0.01D) {
                this.moveRelative(0.1F, new Vec3(0.0D, 0.0D, 1.0D));
            }
        }

    }

    @Override
    public void tick() {
        boolean flag = this.isInWater() && !this.isUnderWater();

        this.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(flag ? 0.20000000298023224D : 0.10000000149011612D);
        if (!this.isMovingOnLand() && !this.isMovingInWater()) {
            this.panicAnimationState.stop();
            this.walkingAnimationState.stop();
        } else if (this.isPanicking()) {
            this.walkingAnimationState.stop();
            this.panicAnimationState.startIfStopped(this.tickCount);
        } else {
            this.panicAnimationState.stop();
            this.walkingAnimationState.startIfStopped(this.tickCount);
        }

        switch (this.getState()) {
            case SEARCHING:
                this.playSearchingSound();
                break;
            case DIGGING:
                this.emitDiggingParticles(this.diggingAnimationState).dropSeed();
        }

        super.tick();
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack itemstack = player.getItemInHand(hand);
        InteractionResult enuminteractionresult = super.mobInteract(player, hand);

        if (enuminteractionresult.consumesAction() && this.isFood(itemstack)) {
            this.level.playSound((Player) null, (Entity) this, this.getEatingSound(itemstack), SoundSource.NEUTRAL, 1.0F, Mth.randomBetween(this.level.random, 0.8F, 1.2F));
        }

        return enuminteractionresult;
    }

    private void playSearchingSound() {
        if (this.level.isClientSide() && this.tickCount % 20 == 0) {
            this.level.playLocalSound(this.getX(), this.getY(), this.getZ(), SoundEvents.SNIFFER_SEARCHING, this.getSoundSource(), 1.0F, 1.0F, false);
        }

    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.SNIFFER_STEP, 0.15F, 1.0F);
    }

    @Override
    public SoundEvent getEatingSound(ItemStack stack) {
        return SoundEvents.SNIFFER_EAT;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return Set.of(Sniffer.State.DIGGING, Sniffer.State.SEARCHING).contains(this.getState()) ? null : SoundEvents.SNIFFER_IDLE;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SNIFFER_HURT;
    }

    @Override
    public SoundEvent getDeathSound() {
        return SoundEvents.SNIFFER_DEATH;
    }

    @Override
    public int getMaxHeadYRot() {
        return 50;
    }

    @Override
    public void setBaby(boolean baby) {
        this.setAge(baby ? -48000 : 0);
    }

    @Override
    public AgeableMob getBreedOffspring(ServerLevel world, AgeableMob entity) {
        return (AgeableMob) EntityType.SNIFFER.create(world);
    }

    @Override
    public boolean canMate(Animal other) {
        if (!(other instanceof Sniffer)) {
            return false;
        } else {
            Sniffer sniffer = (Sniffer) other;
            Set<Sniffer.State> set = Set.of(Sniffer.State.IDLING, Sniffer.State.SCENTING, Sniffer.State.FEELING_HAPPY);

            return set.contains(this.getState()) && set.contains(sniffer.getState()) && super.canMate(other);
        }
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        return super.getBoundingBoxForCulling().inflate(0.6000000238418579D);
    }

    @Override
    public boolean isFood(ItemStack stack) {
        return stack.is(ItemTags.SNIFFER_FOOD);
    }

    @Override
    protected Brain<?> makeBrain(Dynamic<?> dynamic) {
        return SnifferAi.makeBrain(this.brainProvider().makeBrain(dynamic));
    }

    @Override
    public Brain<Sniffer> getBrain() {
        return (Brain<Sniffer>) super.getBrain(); // CraftBukkit - decompile error
    }

    @Override
    protected Brain.Provider<Sniffer> brainProvider() {
        return Brain.provider(SnifferAi.MEMORY_TYPES, SnifferAi.SENSOR_TYPES);
    }

    @Override
    protected void customServerAiStep() {
        this.level.getProfiler().push("snifferBrain");
        this.getBrain().tick((ServerLevel) this.level, this);
        this.level.getProfiler().popPush("snifferActivityUpdate");
        SnifferAi.updateActivity(this);
        this.level.getProfiler().pop();
        super.customServerAiStep();
    }

    @Override
    protected void sendDebugPackets() {
        super.sendDebugPackets();
        DebugPackets.sendEntityBrain(this);
    }

    public static enum State {

        IDLING, FEELING_HAPPY, SCENTING, SNIFFING, SEARCHING, DIGGING, RISING;

        private State() {}
    }
}
