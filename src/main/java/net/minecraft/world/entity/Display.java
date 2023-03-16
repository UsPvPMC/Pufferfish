package net.minecraft.world.entity;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.mojang.math.Transformation;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Brightness;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.FastColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;

public abstract class Display extends Entity {
    static final Logger LOGGER = LogUtils.getLogger();
    private static final float INITIAL_UPDATE_PROGRESS = Float.POSITIVE_INFINITY;
    public static final int NO_BRIGHTNESS_OVERRIDE = -1;
    private static final EntityDataAccessor<Integer> DATA_INTERPOLATION_START_DELTA_TICKS_ID = SynchedEntityData.defineId(Display.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_INTERPOLATION_DURATION_ID = SynchedEntityData.defineId(Display.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Vector3f> DATA_TRANSLATION_ID = SynchedEntityData.defineId(Display.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3f> DATA_SCALE_ID = SynchedEntityData.defineId(Display.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Quaternionf> DATA_LEFT_ROTATION_ID = SynchedEntityData.defineId(Display.class, EntityDataSerializers.QUATERNION);
    private static final EntityDataAccessor<Quaternionf> DATA_RIGHT_ROTATION_ID = SynchedEntityData.defineId(Display.class, EntityDataSerializers.QUATERNION);
    private static final EntityDataAccessor<Byte> DATA_BILLBOARD_RENDER_CONSTRAINTS_ID = SynchedEntityData.defineId(Display.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_BRIGHTNESS_OVERRIDE_ID = SynchedEntityData.defineId(Display.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_VIEW_RANGE_ID = SynchedEntityData.defineId(Display.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SHADOW_RADIUS_ID = SynchedEntityData.defineId(Display.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SHADOW_STRENGTH_ID = SynchedEntityData.defineId(Display.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_WIDTH_ID = SynchedEntityData.defineId(Display.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_HEIGHT_ID = SynchedEntityData.defineId(Display.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_GLOW_COLOR_OVERRIDE_ID = SynchedEntityData.defineId(Display.class, EntityDataSerializers.INT);
    private static final float INITIAL_SHADOW_RADIUS = 0.0F;
    private static final float INITIAL_SHADOW_STRENGTH = 1.0F;
    private static final int NO_GLOW_COLOR_OVERRIDE = -1;
    public static final String TAG_INTERPOLATION_DURATION = "interpolation_duration";
    public static final String TAG_START_INTERPOLATION = "start_interpolation";
    public static final String TAG_TRANSFORMATION = "transformation";
    public static final String TAG_BILLBOARD = "billboard";
    public static final String TAG_BRIGHTNESS = "brightness";
    public static final String TAG_VIEW_RANGE = "view_range";
    public static final String TAG_SHADOW_RADIUS = "shadow_radius";
    public static final String TAG_SHADOW_STRENGTH = "shadow_strength";
    public static final String TAG_WIDTH = "width";
    public static final String TAG_HEIGHT = "height";
    public static final String TAG_GLOW_COLOR_OVERRIDE = "glow_color_override";
    private final Display.GenericInterpolator<Transformation> transformation = new Display.GenericInterpolator<Transformation>(Transformation.identity()) {
        @Override
        protected Transformation interpolate(float delta, Transformation start, Transformation end) {
            return start.slerp(end, delta);
        }
    };
    private final Display.FloatInterpolator shadowRadius = new Display.FloatInterpolator(0.0F);
    private final Display.FloatInterpolator shadowStrength = new Display.FloatInterpolator(1.0F);
    private final Quaternionf orientation = new Quaternionf();
    protected final Display.InterpolatorSet interpolators = new Display.InterpolatorSet();
    private long interpolationStartClientTick;
    private float lastProgress;
    private AABB cullingBoundingBox;
    private boolean updateInterpolators;
    private boolean updateTime;

    public Display(EntityType<?> type, Level world) {
        super(type, world);
        this.noPhysics = true;
        this.noCulling = true;
        this.cullingBoundingBox = this.getBoundingBox();
        this.interpolators.addEntry(Set.of(DATA_TRANSLATION_ID, DATA_LEFT_ROTATION_ID, DATA_SCALE_ID, DATA_RIGHT_ROTATION_ID), (value, dataTracker) -> {
            this.transformation.updateValue(value, createTransformation(dataTracker));
        });
        this.interpolators.addEntry(DATA_SHADOW_STRENGTH_ID, this.shadowStrength);
        this.interpolators.addEntry(DATA_SHADOW_RADIUS_ID, this.shadowRadius);
    }

    @Override
    public void onSyncedDataUpdated(List<SynchedEntityData.DataValue<?>> dataEntries) {
        super.onSyncedDataUpdated(dataEntries);
        boolean bl = false;

        for(SynchedEntityData.DataValue<?> dataValue : dataEntries) {
            bl |= this.interpolators.shouldTriggerUpdate(dataValue.id());
        }

        if (bl) {
            boolean bl2 = this.tickCount <= 0;
            if (bl2) {
                this.interpolators.updateValues(Float.POSITIVE_INFINITY, this.entityData);
            } else {
                this.updateInterpolators = true;
            }
        }

    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
        super.onSyncedDataUpdated(data);
        if (DATA_HEIGHT_ID.equals(data) || DATA_WIDTH_ID.equals(data)) {
            this.updateCulling();
        }

        if (DATA_INTERPOLATION_START_DELTA_TICKS_ID.equals(data)) {
            this.updateTime = true;
        }

    }

    public static Transformation createTransformation(SynchedEntityData dataTracker) {
        Vector3f vector3f = dataTracker.get(DATA_TRANSLATION_ID);
        Quaternionf quaternionf = dataTracker.get(DATA_LEFT_ROTATION_ID);
        Vector3f vector3f2 = dataTracker.get(DATA_SCALE_ID);
        Quaternionf quaternionf2 = dataTracker.get(DATA_RIGHT_ROTATION_ID);
        return new Transformation(vector3f, quaternionf, vector3f2, quaternionf2);
    }

    @Override
    public void tick() {
        Entity entity = this.getVehicle();
        if (entity != null && entity.isRemoved()) {
            this.stopRiding();
        }

        if (this.level.isClientSide) {
            if (this.updateTime) {
                this.updateTime = false;
                int i = this.getInterpolationDelay();
                this.interpolationStartClientTick = (long)(this.tickCount + i);
            }

            if (this.updateInterpolators) {
                this.updateInterpolators = false;
                this.interpolators.updateValues(this.lastProgress, this.entityData);
            }
        }

    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_INTERPOLATION_START_DELTA_TICKS_ID, 0);
        this.entityData.define(DATA_INTERPOLATION_DURATION_ID, 0);
        this.entityData.define(DATA_TRANSLATION_ID, new Vector3f());
        this.entityData.define(DATA_SCALE_ID, new Vector3f(1.0F, 1.0F, 1.0F));
        this.entityData.define(DATA_RIGHT_ROTATION_ID, new Quaternionf());
        this.entityData.define(DATA_LEFT_ROTATION_ID, new Quaternionf());
        this.entityData.define(DATA_BILLBOARD_RENDER_CONSTRAINTS_ID, Display.BillboardConstraints.FIXED.getId());
        this.entityData.define(DATA_BRIGHTNESS_OVERRIDE_ID, -1);
        this.entityData.define(DATA_VIEW_RANGE_ID, 1.0F);
        this.entityData.define(DATA_SHADOW_RADIUS_ID, 0.0F);
        this.entityData.define(DATA_SHADOW_STRENGTH_ID, 1.0F);
        this.entityData.define(DATA_WIDTH_ID, 0.0F);
        this.entityData.define(DATA_HEIGHT_ID, 0.0F);
        this.entityData.define(DATA_GLOW_COLOR_OVERRIDE_ID, -1);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag nbt) {
        if (nbt.contains("transformation")) {
            Transformation.EXTENDED_CODEC.decode(NbtOps.INSTANCE, nbt.get("transformation")).resultOrPartial(Util.prefix("Display entity", LOGGER::error)).ifPresent((pair) -> {
                this.setTransformation(pair.getFirst());
            });
        }

        if (nbt.contains("interpolation_duration", 99)) {
            int i = nbt.getInt("interpolation_duration");
            this.setInterpolationDuration(i);
        }

        if (nbt.contains("start_interpolation", 99)) {
            int j = nbt.getInt("start_interpolation");
            this.setInterpolationDelay(j);
        }

        if (nbt.contains("billboard", 8)) {
            Display.BillboardConstraints.CODEC.decode(NbtOps.INSTANCE, nbt.get("billboard")).resultOrPartial(Util.prefix("Display entity", LOGGER::error)).ifPresent((pair) -> {
                this.setBillboardConstraints(pair.getFirst());
            });
        }

        if (nbt.contains("view_range", 99)) {
            this.setViewRange(nbt.getFloat("view_range"));
        }

        if (nbt.contains("shadow_radius", 99)) {
            this.setShadowRadius(nbt.getFloat("shadow_radius"));
        }

        if (nbt.contains("shadow_strength", 99)) {
            this.setShadowStrength(nbt.getFloat("shadow_strength"));
        }

        if (nbt.contains("width", 99)) {
            this.setWidth(nbt.getFloat("width"));
        }

        if (nbt.contains("height", 99)) {
            this.setHeight(nbt.getFloat("height"));
        }

        if (nbt.contains("glow_color_override", 99)) {
            this.setGlowColorOverride(nbt.getInt("glow_color_override"));
        }

        if (nbt.contains("brightness", 10)) {
            Brightness.CODEC.decode(NbtOps.INSTANCE, nbt.get("brightness")).resultOrPartial(Util.prefix("Display entity", LOGGER::error)).ifPresent((pair) -> {
                this.setBrightnessOverride(pair.getFirst());
            });
        } else {
            this.setBrightnessOverride((Brightness)null);
        }

    }

    public void setTransformation(Transformation transformation) {
        this.entityData.set(DATA_TRANSLATION_ID, transformation.getTranslation());
        this.entityData.set(DATA_LEFT_ROTATION_ID, transformation.getLeftRotation());
        this.entityData.set(DATA_SCALE_ID, transformation.getScale());
        this.entityData.set(DATA_RIGHT_ROTATION_ID, transformation.getRightRotation());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag nbt) {
        Transformation.EXTENDED_CODEC.encodeStart(NbtOps.INSTANCE, createTransformation(this.entityData)).result().ifPresent((transformations) -> {
            nbt.put("transformation", transformations);
        });
        Display.BillboardConstraints.CODEC.encodeStart(NbtOps.INSTANCE, this.getBillboardConstraints()).result().ifPresent((billboard) -> {
            nbt.put("billboard", billboard);
        });
        nbt.putInt("interpolation_duration", this.getInterpolationDuration());
        nbt.putFloat("view_range", this.getViewRange());
        nbt.putFloat("shadow_radius", this.getShadowRadius());
        nbt.putFloat("shadow_strength", this.getShadowStrength());
        nbt.putFloat("width", this.getWidth());
        nbt.putFloat("height", this.getHeight());
        nbt.putInt("glow_color_override", this.getGlowColorOverride());
        Brightness brightness = this.getBrightnessOverride();
        if (brightness != null) {
            Brightness.CODEC.encodeStart(NbtOps.INSTANCE, brightness).result().ifPresent((brightnessx) -> {
                nbt.put("brightness", brightnessx);
            });
        }

    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return new ClientboundAddEntityPacket(this);
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        return this.cullingBoundingBox;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    public Quaternionf orientation() {
        return this.orientation;
    }

    public Transformation transformation(float delta) {
        return this.transformation.get(delta);
    }

    public void setInterpolationDuration(int interpolationDuration) {
        this.entityData.set(DATA_INTERPOLATION_DURATION_ID, interpolationDuration);
    }

    public int getInterpolationDuration() {
        return this.entityData.get(DATA_INTERPOLATION_DURATION_ID);
    }

    public void setInterpolationDelay(int startInterpolation) {
        this.entityData.set(DATA_INTERPOLATION_START_DELTA_TICKS_ID, startInterpolation, true);
    }

    public int getInterpolationDelay() {
        return this.entityData.get(DATA_INTERPOLATION_START_DELTA_TICKS_ID);
    }

    public void setBillboardConstraints(Display.BillboardConstraints billboardMode) {
        this.entityData.set(DATA_BILLBOARD_RENDER_CONSTRAINTS_ID, billboardMode.getId());
    }

    public Display.BillboardConstraints getBillboardConstraints() {
        return Display.BillboardConstraints.BY_ID.apply(this.entityData.get(DATA_BILLBOARD_RENDER_CONSTRAINTS_ID));
    }

    public void setBrightnessOverride(@Nullable Brightness brightness) {
        this.entityData.set(DATA_BRIGHTNESS_OVERRIDE_ID, brightness != null ? brightness.pack() : -1);
    }

    @Nullable
    public Brightness getBrightnessOverride() {
        int i = this.entityData.get(DATA_BRIGHTNESS_OVERRIDE_ID);
        return i != -1 ? Brightness.unpack(i) : null;
    }

    public int getPackedBrightnessOverride() {
        return this.entityData.get(DATA_BRIGHTNESS_OVERRIDE_ID);
    }

    public void setViewRange(float viewRange) {
        this.entityData.set(DATA_VIEW_RANGE_ID, viewRange);
    }

    public float getViewRange() {
        return this.entityData.get(DATA_VIEW_RANGE_ID);
    }

    public void setShadowRadius(float shadowRadius) {
        this.entityData.set(DATA_SHADOW_RADIUS_ID, shadowRadius);
    }

    public float getShadowRadius() {
        return this.entityData.get(DATA_SHADOW_RADIUS_ID);
    }

    public float getShadowRadius(float delta) {
        return this.shadowRadius.get(delta);
    }

    public void setShadowStrength(float shadowStrength) {
        this.entityData.set(DATA_SHADOW_STRENGTH_ID, shadowStrength);
    }

    public float getShadowStrength() {
        return this.entityData.get(DATA_SHADOW_STRENGTH_ID);
    }

    public float getShadowStrength(float delta) {
        return this.shadowStrength.get(delta);
    }

    public void setWidth(float width) {
        this.entityData.set(DATA_WIDTH_ID, width);
    }

    public float getWidth() {
        return this.entityData.get(DATA_WIDTH_ID);
    }

    public void setHeight(float height) {
        this.entityData.set(DATA_HEIGHT_ID, height);
    }

    public int getGlowColorOverride() {
        return this.entityData.get(DATA_GLOW_COLOR_OVERRIDE_ID);
    }

    public void setGlowColorOverride(int glowColorOverride) {
        this.entityData.set(DATA_GLOW_COLOR_OVERRIDE_ID, glowColorOverride);
    }

    public float calculateInterpolationProgress(float delta) {
        int i = this.getInterpolationDuration();
        if (i <= 0) {
            return 1.0F;
        } else {
            float f = (float)((long)this.tickCount - this.interpolationStartClientTick);
            float g = f + delta;
            float h = Mth.clamp(Mth.inverseLerp(g, 0.0F, (float)i), 0.0F, 1.0F);
            this.lastProgress = h;
            return h;
        }
    }

    public float getHeight() {
        return this.entityData.get(DATA_HEIGHT_ID);
    }

    @Override
    public void setPos(double x, double y, double z) {
        super.setPos(x, y, z);
        this.updateCulling();
    }

    private void updateCulling() {
        float f = this.getWidth();
        float g = this.getHeight();
        if (f != 0.0F && g != 0.0F) {
            this.noCulling = false;
            float h = f / 2.0F;
            double d = this.getX();
            double e = this.getY();
            double i = this.getZ();
            this.cullingBoundingBox = new AABB(d - (double)h, e, i - (double)h, d + (double)h, e + (double)g, i + (double)h);
        } else {
            this.noCulling = true;
        }

    }

    @Override
    public void setXRot(float pitch) {
        super.setXRot(pitch);
        this.updateOrientation();
    }

    @Override
    public void setYRot(float yaw) {
        super.setYRot(yaw);
        this.updateOrientation();
    }

    private void updateOrientation() {
        this.orientation.rotationYXZ(-0.017453292F * this.getYRot(), ((float)Math.PI / 180F) * this.getXRot(), 0.0F);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < Mth.square((double)this.getViewRange() * 64.0D * getViewScale());
    }

    @Override
    public int getTeamColor() {
        int i = this.getGlowColorOverride();
        return i != -1 ? i : super.getTeamColor();
    }

    public static enum BillboardConstraints implements StringRepresentable {
        FIXED((byte)0, "fixed"),
        VERTICAL((byte)1, "vertical"),
        HORIZONTAL((byte)2, "horizontal"),
        CENTER((byte)3, "center");

        public static final Codec<Display.BillboardConstraints> CODEC = StringRepresentable.fromEnum(Display.BillboardConstraints::values);
        public static final IntFunction<Display.BillboardConstraints> BY_ID = ByIdMap.continuous(Display.BillboardConstraints::getId, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
        private final byte id;
        private final String name;

        private BillboardConstraints(byte index, String name) {
            this.name = name;
            this.id = index;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }

        byte getId() {
            return this.id;
        }
    }

    public static class BlockDisplay extends Display {
        public static final String TAG_BLOCK_STATE = "block_state";
        private static final EntityDataAccessor<BlockState> DATA_BLOCK_STATE_ID = SynchedEntityData.defineId(Display.BlockDisplay.class, EntityDataSerializers.BLOCK_STATE);

        public BlockDisplay(EntityType<?> type, Level world) {
            super(type, world);
        }

        @Override
        protected void defineSynchedData() {
            super.defineSynchedData();
            this.entityData.define(DATA_BLOCK_STATE_ID, Blocks.AIR.defaultBlockState());
        }

        public BlockState getBlockState() {
            return this.entityData.get(DATA_BLOCK_STATE_ID);
        }

        public void setBlockState(BlockState state) {
            this.entityData.set(DATA_BLOCK_STATE_ID, state);
        }

        @Override
        protected void readAdditionalSaveData(CompoundTag nbt) {
            super.readAdditionalSaveData(nbt);
            this.setBlockState(NbtUtils.readBlockState(this.level.holderLookup(Registries.BLOCK), nbt.getCompound("block_state")));
        }

        @Override
        protected void addAdditionalSaveData(CompoundTag nbt) {
            super.addAdditionalSaveData(nbt);
            nbt.put("block_state", NbtUtils.writeBlockState(this.getBlockState()));
        }
    }

    static class ColorInterpolator extends Display.IntInterpolator {
        protected ColorInterpolator(int value) {
            super(value);
        }

        @Override
        protected int interpolate(float delta, int start, int end) {
            return FastColor.ARGB32.lerp(delta, start, end);
        }
    }

    static class FloatInterpolator extends Display.Interpolator<Float> {
        protected FloatInterpolator(float value) {
            super(value);
        }

        protected float interpolate(float delta, float start, float end) {
            return Mth.lerp(delta, start, end);
        }

        public float get(float delta) {
            return !((double)delta >= 1.0D) && this.lastValue != null ? this.interpolate(delta, this.lastValue, this.currentValue) : this.currentValue;
        }

        @Override
        protected Float getGeneric(float f) {
            return this.get(f);
        }
    }

    abstract static class GenericInterpolator<T> extends Display.Interpolator<T> {
        protected GenericInterpolator(T value) {
            super(value);
        }

        protected abstract T interpolate(float delta, T start, T end);

        public T get(float delta) {
            return (T)(!((double)delta >= 1.0D) && this.lastValue != null ? this.interpolate(delta, this.lastValue, this.currentValue) : this.currentValue);
        }

        @Override
        protected T getGeneric(float value) {
            return this.get(value);
        }
    }

    static class IntInterpolator extends Display.Interpolator<Integer> {
        protected IntInterpolator(int value) {
            super(value);
        }

        protected int interpolate(float delta, int start, int end) {
            return Mth.lerpInt(delta, start, end);
        }

        public int get(float value) {
            return !((double)value >= 1.0D) && this.lastValue != null ? this.interpolate(value, this.lastValue, this.currentValue) : this.currentValue;
        }

        @Override
        protected Integer getGeneric(float f) {
            return this.get(f);
        }
    }

    @FunctionalInterface
    interface IntepolatorUpdater {
        void update(float value, SynchedEntityData dataTracker);
    }

    abstract static class Interpolator<T> {
        @Nullable
        protected T lastValue;
        protected T currentValue;

        protected Interpolator(T value) {
            this.currentValue = value;
        }

        protected abstract T getGeneric(float value);

        public void updateValue(float prevValue, T value) {
            if (prevValue != Float.POSITIVE_INFINITY) {
                this.lastValue = this.getGeneric(prevValue);
            }

            this.currentValue = value;
        }
    }

    static class InterpolatorSet {
        private final IntSet interpolatedData = new IntOpenHashSet();
        private final List<Display.IntepolatorUpdater> updaters = new ArrayList<>();

        protected <T> void addEntry(EntityDataAccessor<T> data, Display.Interpolator<T> interpolator) {
            this.interpolatedData.add(data.getId());
            this.updaters.add((value, dataTracker) -> {
                interpolator.updateValue(value, dataTracker.get(data));
            });
        }

        protected void addEntry(Set<EntityDataAccessor<?>> dataSet, Display.IntepolatorUpdater updater) {
            for(EntityDataAccessor<?> entityDataAccessor : dataSet) {
                this.interpolatedData.add(entityDataAccessor.getId());
            }

            this.updaters.add(updater);
        }

        public boolean shouldTriggerUpdate(int id) {
            return this.interpolatedData.contains(id);
        }

        public void updateValues(float value, SynchedEntityData dataTracker) {
            for(Display.IntepolatorUpdater intepolatorUpdater : this.updaters) {
                intepolatorUpdater.update(value, dataTracker);
            }

        }
    }

    public static class ItemDisplay extends Display {
        private static final String TAG_ITEM = "item";
        private static final String TAG_ITEM_DISPLAY = "item_display";
        private static final EntityDataAccessor<ItemStack> DATA_ITEM_STACK_ID = SynchedEntityData.defineId(Display.ItemDisplay.class, EntityDataSerializers.ITEM_STACK);
        private static final EntityDataAccessor<Byte> DATA_ITEM_DISPLAY_ID = SynchedEntityData.defineId(Display.ItemDisplay.class, EntityDataSerializers.BYTE);
        private final SlotAccess slot = new SlotAccess() {
            @Override
            public ItemStack get() {
                return ItemDisplay.this.getItemStack();
            }

            @Override
            public boolean set(ItemStack stack) {
                ItemDisplay.this.setItemStack(stack);
                return true;
            }
        };

        public ItemDisplay(EntityType<?> type, Level world) {
            super(type, world);
        }

        @Override
        protected void defineSynchedData() {
            super.defineSynchedData();
            this.entityData.define(DATA_ITEM_STACK_ID, ItemStack.EMPTY);
            this.entityData.define(DATA_ITEM_DISPLAY_ID, ItemDisplayContext.NONE.getId());
        }

        public ItemStack getItemStack() {
            return this.entityData.get(DATA_ITEM_STACK_ID);
        }

        public void setItemStack(ItemStack stack) {
            this.entityData.set(DATA_ITEM_STACK_ID, stack);
        }

        public void setItemTransform(ItemDisplayContext transformationMode) {
            this.entityData.set(DATA_ITEM_DISPLAY_ID, transformationMode.getId());
        }

        public ItemDisplayContext getItemTransform() {
            return ItemDisplayContext.BY_ID.apply(this.entityData.get(DATA_ITEM_DISPLAY_ID));
        }

        @Override
        protected void readAdditionalSaveData(CompoundTag nbt) {
            super.readAdditionalSaveData(nbt);
            this.setItemStack(ItemStack.of(nbt.getCompound("item")));
            if (nbt.contains("item_display", 8)) {
                ItemDisplayContext.CODEC.decode(NbtOps.INSTANCE, nbt.get("item_display")).resultOrPartial(Util.prefix("Display entity", Display.LOGGER::error)).ifPresent((mode) -> {
                    this.setItemTransform(mode.getFirst());
                });
            }

        }

        @Override
        protected void addAdditionalSaveData(CompoundTag nbt) {
            super.addAdditionalSaveData(nbt);
            nbt.put("item", this.getItemStack().save(new CompoundTag()));
            ItemDisplayContext.CODEC.encodeStart(NbtOps.INSTANCE, this.getItemTransform()).result().ifPresent((nbtx) -> {
                nbt.put("item_display", nbtx);
            });
        }

        @Override
        public SlotAccess getSlot(int mappedIndex) {
            return mappedIndex == 0 ? this.slot : SlotAccess.NULL;
        }
    }

    public static class TextDisplay extends Display {
        public static final String TAG_TEXT = "text";
        private static final String TAG_LINE_WIDTH = "line_width";
        private static final String TAG_TEXT_OPACITY = "text_opacity";
        private static final String TAG_BACKGROUND_COLOR = "background";
        private static final String TAG_SHADOW = "shadow";
        private static final String TAG_SEE_THROUGH = "see_through";
        private static final String TAG_USE_DEFAULT_BACKGROUND = "default_background";
        private static final String TAG_ALIGNMENT = "alignment";
        public static final byte FLAG_SHADOW = 1;
        public static final byte FLAG_SEE_THROUGH = 2;
        public static final byte FLAG_USE_DEFAULT_BACKGROUND = 4;
        public static final byte FLAG_ALIGN_LEFT = 8;
        public static final byte FLAG_ALIGN_RIGHT = 16;
        private static final byte INITIAL_TEXT_OPACITY = -1;
        public static final int INITIAL_BACKGROUND = 1073741824;
        private static final EntityDataAccessor<Component> DATA_TEXT_ID = SynchedEntityData.defineId(Display.TextDisplay.class, EntityDataSerializers.COMPONENT);
        private static final EntityDataAccessor<Integer> DATA_LINE_WIDTH_ID = SynchedEntityData.defineId(Display.TextDisplay.class, EntityDataSerializers.INT);
        private static final EntityDataAccessor<Integer> DATA_BACKGROUND_COLOR_ID = SynchedEntityData.defineId(Display.TextDisplay.class, EntityDataSerializers.INT);
        private static final EntityDataAccessor<Byte> DATA_TEXT_OPACITY_ID = SynchedEntityData.defineId(Display.TextDisplay.class, EntityDataSerializers.BYTE);
        private static final EntityDataAccessor<Byte> DATA_STYLE_FLAGS_ID = SynchedEntityData.defineId(Display.TextDisplay.class, EntityDataSerializers.BYTE);
        private final Display.IntInterpolator textOpacity = new Display.IntInterpolator(-1);
        private final Display.IntInterpolator backgroundColor = new Display.ColorInterpolator(1073741824);
        @Nullable
        private Display.TextDisplay.CachedInfo clientDisplayCache;

        public TextDisplay(EntityType<?> type, Level world) {
            super(type, world);
            this.interpolators.addEntry(DATA_BACKGROUND_COLOR_ID, this.backgroundColor);
            this.interpolators.addEntry(Set.of(DATA_TEXT_OPACITY_ID), (value, dataTracker) -> {
                this.textOpacity.updateValue(value, Integer.valueOf(dataTracker.get(DATA_TEXT_OPACITY_ID) & 255));
            });
        }

        @Override
        protected void defineSynchedData() {
            super.defineSynchedData();
            this.entityData.define(DATA_TEXT_ID, Component.empty());
            this.entityData.define(DATA_LINE_WIDTH_ID, 200);
            this.entityData.define(DATA_BACKGROUND_COLOR_ID, 1073741824);
            this.entityData.define(DATA_TEXT_OPACITY_ID, (byte)-1);
            this.entityData.define(DATA_STYLE_FLAGS_ID, (byte)0);
        }

        @Override
        public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
            super.onSyncedDataUpdated(data);
            this.clientDisplayCache = null;
        }

        public Component getText() {
            return this.entityData.get(DATA_TEXT_ID);
        }

        public void setText(Component text) {
            this.entityData.set(DATA_TEXT_ID, text);
        }

        public int getLineWidth() {
            return this.entityData.get(DATA_LINE_WIDTH_ID);
        }

        public void setLineWidth(int lineWidth) {
            this.entityData.set(DATA_LINE_WIDTH_ID, lineWidth);
        }

        public byte getTextOpacity(float delta) {
            return (byte)this.textOpacity.get(delta);
        }

        public byte getTextOpacity() {
            return this.entityData.get(DATA_TEXT_OPACITY_ID);
        }

        public void setTextOpacity(byte textOpacity) {
            this.entityData.set(DATA_TEXT_OPACITY_ID, textOpacity);
        }

        public int getBackgroundColor(float delta) {
            return this.backgroundColor.get(delta);
        }

        public int getBackgroundColor() {
            return this.entityData.get(DATA_BACKGROUND_COLOR_ID);
        }

        public void setBackgroundColor(int background) {
            this.entityData.set(DATA_BACKGROUND_COLOR_ID, background);
        }

        public byte getFlags() {
            return this.entityData.get(DATA_STYLE_FLAGS_ID);
        }

        public void setFlags(byte flags) {
            this.entityData.set(DATA_STYLE_FLAGS_ID, flags);
        }

        private static byte loadFlag(byte flags, CompoundTag nbt, String nbtKey, byte flag) {
            return nbt.getBoolean(nbtKey) ? (byte)(flags | flag) : flags;
        }

        @Override
        protected void readAdditionalSaveData(CompoundTag nbt) {
            super.readAdditionalSaveData(nbt);
            if (nbt.contains("line_width", 99)) {
                this.setLineWidth(nbt.getInt("line_width"));
            }

            if (nbt.contains("text_opacity", 99)) {
                this.setTextOpacity(nbt.getByte("text_opacity"));
            }

            if (nbt.contains("background", 99)) {
                this.setBackgroundColor(nbt.getInt("background"));
            }

            byte b = loadFlag((byte)0, nbt, "shadow", (byte)1);
            b = loadFlag(b, nbt, "see_through", (byte)2);
            b = loadFlag(b, nbt, "default_background", (byte)4);
            Optional<Display.TextDisplay.Align> optional = Display.TextDisplay.Align.CODEC.decode(NbtOps.INSTANCE, nbt.get("alignment")).result().map(Pair::getFirst); // Paper
            if (optional.isPresent()) {
                byte var10000;
                switch ((Display.TextDisplay.Align)optional.get()) {
                    case CENTER:
                        var10000 = b;
                        break;
                    case LEFT:
                        var10000 = (byte)(b | 8);
                        break;
                    case RIGHT:
                        var10000 = (byte)(b | 16);
                        break;
                    default:
                        throw new IncompatibleClassChangeError();
                }

                b = var10000;
            }

            this.setFlags(b);
            if (nbt.contains("text", 8)) {
                String string = nbt.getString("text");

                try {
                    Component component = Component.Serializer.fromJson(string);
                    if (component != null) {
                        CommandSourceStack commandSourceStack = this.createCommandSourceStack().withPermission(2);
                        Component component2 = ComponentUtils.updateForEntity(commandSourceStack, component, this, 0);
                        this.setText(component2);
                    } else {
                        this.setText(Component.empty());
                    }
                } catch (Exception var8) {
                    Display.LOGGER.warn("Failed to parse display entity text {}", string, var8);
                }
            }

        }

        private static void storeFlag(byte flags, CompoundTag nbt, String nbtKey, byte flag) {
            nbt.putBoolean(nbtKey, (flags & flag) != 0);
        }

        @Override
        protected void addAdditionalSaveData(CompoundTag nbt) {
            super.addAdditionalSaveData(nbt);
            nbt.putString("text", Component.Serializer.toJson(this.getText()));
            nbt.putInt("line_width", this.getLineWidth());
            nbt.putInt("background", this.getBackgroundColor());
            nbt.putByte("text_opacity", this.getTextOpacity());
            byte b = this.getFlags();
            storeFlag(b, nbt, "shadow", (byte)1);
            storeFlag(b, nbt, "see_through", (byte)2);
            storeFlag(b, nbt, "default_background", (byte)4);
            Display.TextDisplay.Align.CODEC.encodeStart(NbtOps.INSTANCE, getAlign(b)).result().ifPresent((tag) -> {
                nbt.put("alignment", tag);
            });
        }

        public Display.TextDisplay.CachedInfo cacheDisplay(Display.TextDisplay.LineSplitter splitter) {
            if (this.clientDisplayCache == null) {
                int i = this.getLineWidth();
                this.clientDisplayCache = splitter.split(this.getText(), i);
            }

            return this.clientDisplayCache;
        }

        public static Display.TextDisplay.Align getAlign(byte flags) {
            if ((flags & 8) != 0) {
                return Display.TextDisplay.Align.LEFT;
            } else {
                return (flags & 16) != 0 ? Display.TextDisplay.Align.RIGHT : Display.TextDisplay.Align.CENTER;
            }
        }

        public static enum Align implements StringRepresentable {
            CENTER("center"),
            LEFT("left"),
            RIGHT("right");

            public static final Codec<Display.TextDisplay.Align> CODEC = StringRepresentable.fromEnum(Display.TextDisplay.Align::values);
            private final String name;

            private Align(String name) {
                this.name = name;
            }

            @Override
            public String getSerializedName() {
                return this.name;
            }
        }

        public static record CachedInfo(List<Display.TextDisplay.CachedLine> lines, int width) {
        }

        public static record CachedLine(FormattedCharSequence contents, int width) {
        }

        @FunctionalInterface
        public interface LineSplitter {
            Display.TextDisplay.CachedInfo split(Component text, int lineWidth);
        }
    }
}
