package net.minecraft.world.effect;

import com.google.common.collect.ComparisonChain;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.ints.Int2IntFunction;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;

public class MobEffectInstance implements Comparable<MobEffectInstance> {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int INFINITE_DURATION = -1;
    private final MobEffect effect;
    private int duration;
    private int amplifier;
    private boolean ambient;
    private boolean visible;
    private boolean showIcon;
    @Nullable
    private MobEffectInstance hiddenEffect;
    private final Optional<MobEffectInstance.FactorData> factorData;

    public MobEffectInstance(MobEffect type) {
        this(type, 0, 0);
    }

    public MobEffectInstance(MobEffect type, int duration) {
        this(type, duration, 0);
    }

    public MobEffectInstance(MobEffect type, int duration, int amplifier) {
        this(type, duration, amplifier, false, true);
    }

    public MobEffectInstance(MobEffect type, int duration, int amplifier, boolean ambient, boolean visible) {
        this(type, duration, amplifier, ambient, visible, visible);
    }

    public MobEffectInstance(MobEffect type, int duration, int amplifier, boolean ambient, boolean showParticles, boolean showIcon) {
        this(type, duration, amplifier, ambient, showParticles, showIcon, (MobEffectInstance)null, type.createFactorData());
    }

    public MobEffectInstance(MobEffect type, int duration, int amplifier, boolean ambient, boolean showParticles, boolean showIcon, @Nullable MobEffectInstance hiddenEffect, Optional<MobEffectInstance.FactorData> factorCalculationData) {
        this.effect = type;
        this.duration = duration;
        this.amplifier = amplifier;
        this.ambient = ambient;
        this.visible = showParticles;
        this.showIcon = showIcon;
        this.hiddenEffect = hiddenEffect;
        this.factorData = factorCalculationData;
    }

    public MobEffectInstance(MobEffectInstance instance) {
        this.effect = instance.effect;
        this.factorData = this.effect.createFactorData();
        this.setDetailsFrom(instance);
    }

    public Optional<MobEffectInstance.FactorData> getFactorData() {
        return this.factorData;
    }

    void setDetailsFrom(MobEffectInstance that) {
        this.duration = that.duration;
        this.amplifier = that.amplifier;
        this.ambient = that.ambient;
        this.visible = that.visible;
        this.showIcon = that.showIcon;
    }

    public boolean update(MobEffectInstance that) {
        if (this.effect != that.effect) {
            LOGGER.warn("This method should only be called for matching effects!");
        }

        int i = this.duration;
        boolean bl = false;
        if (that.amplifier > this.amplifier) {
            if (that.isShorterDurationThan(this)) {
                MobEffectInstance mobEffectInstance = this.hiddenEffect;
                this.hiddenEffect = new MobEffectInstance(this);
                this.hiddenEffect.hiddenEffect = mobEffectInstance;
            }

            this.amplifier = that.amplifier;
            this.duration = that.duration;
            bl = true;
        } else if (this.isShorterDurationThan(that)) {
            if (that.amplifier == this.amplifier) {
                this.duration = that.duration;
                bl = true;
            } else if (this.hiddenEffect == null) {
                this.hiddenEffect = new MobEffectInstance(that);
            } else {
                this.hiddenEffect.update(that);
            }
        }

        if (!that.ambient && this.ambient || bl) {
            this.ambient = that.ambient;
            bl = true;
        }

        if (that.visible != this.visible) {
            this.visible = that.visible;
            bl = true;
        }

        if (that.showIcon != this.showIcon) {
            this.showIcon = that.showIcon;
            bl = true;
        }

        return bl;
    }

    private boolean isShorterDurationThan(MobEffectInstance effect) {
        return !this.isInfiniteDuration() && (this.duration < effect.duration || effect.isInfiniteDuration());
    }

    public boolean isInfiniteDuration() {
        return this.duration == -1;
    }

    public boolean endsWithin(int duration) {
        return !this.isInfiniteDuration() && this.duration <= duration;
    }

    public int mapDuration(Int2IntFunction mapper) {
        return !this.isInfiniteDuration() && this.duration != 0 ? mapper.applyAsInt(this.duration) : this.duration;
    }

    public MobEffect getEffect() {
        return this.effect;
    }

    public int getDuration() {
        return this.duration;
    }

    public int getAmplifier() {
        return this.amplifier;
    }

    public boolean isAmbient() {
        return this.ambient;
    }

    public boolean isVisible() {
        return this.visible;
    }

    public boolean showIcon() {
        return this.showIcon;
    }

    public boolean tick(LivingEntity entity, Runnable overwriteCallback) {
        if (this.hasRemainingDuration()) {
            int i = this.isInfiniteDuration() ? entity.tickCount : this.duration;
            if (this.effect.isDurationEffectTick(i, this.amplifier)) {
                this.applyEffect(entity);
            }

            this.tickDownDuration();
            if (this.duration == 0 && this.hiddenEffect != null) {
                this.setDetailsFrom(this.hiddenEffect);
                this.hiddenEffect = this.hiddenEffect.hiddenEffect;
                overwriteCallback.run();
            }
        }

        this.factorData.ifPresent((factorCalculationData) -> {
            factorCalculationData.tick(this);
        });
        return this.hasRemainingDuration();
    }

    private boolean hasRemainingDuration() {
        return this.isInfiniteDuration() || this.duration > 0;
    }

    private int tickDownDuration() {
        if (this.hiddenEffect != null) {
            this.hiddenEffect.tickDownDuration();
        }

        return this.duration = this.mapDuration((duration) -> {
            return duration - 1;
        });
    }

    public void applyEffect(LivingEntity entity) {
        if (this.hasRemainingDuration()) {
            this.effect.applyEffectTick(entity, this.amplifier);
        }

    }

    public String getDescriptionId() {
        return this.effect.getDescriptionId();
    }

    @Override
    public String toString() {
        String string;
        if (this.amplifier > 0) {
            string = this.getDescriptionId() + " x " + (this.amplifier + 1) + ", Duration: " + this.describeDuration();
        } else {
            string = this.getDescriptionId() + ", Duration: " + this.describeDuration();
        }

        if (!this.visible) {
            string = string + ", Particles: false";
        }

        if (!this.showIcon) {
            string = string + ", Show Icon: false";
        }

        return string;
    }

    private String describeDuration() {
        return this.isInfiniteDuration() ? "infinite" : Integer.toString(this.duration);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        } else if (!(object instanceof MobEffectInstance)) {
            return false;
        } else {
            MobEffectInstance mobEffectInstance = (MobEffectInstance)object;
            return this.duration == mobEffectInstance.duration && this.amplifier == mobEffectInstance.amplifier && this.ambient == mobEffectInstance.ambient && this.effect.equals(mobEffectInstance.effect);
        }
    }

    @Override
    public int hashCode() {
        int i = this.effect.hashCode();
        i = 31 * i + this.duration;
        i = 31 * i + this.amplifier;
        return 31 * i + (this.ambient ? 1 : 0);
    }

    public CompoundTag save(CompoundTag nbt) {
        nbt.putInt("Id", MobEffect.getId(this.getEffect()));
        this.writeDetailsTo(nbt);
        return nbt;
    }

    private void writeDetailsTo(CompoundTag nbt) {
        nbt.putByte("Amplifier", (byte)this.getAmplifier());
        nbt.putInt("Duration", this.getDuration());
        nbt.putBoolean("Ambient", this.isAmbient());
        nbt.putBoolean("ShowParticles", this.isVisible());
        nbt.putBoolean("ShowIcon", this.showIcon());
        if (this.hiddenEffect != null) {
            CompoundTag compoundTag = new CompoundTag();
            this.hiddenEffect.save(compoundTag);
            nbt.put("HiddenEffect", compoundTag);
        }

        this.factorData.ifPresent((factorCalculationData) -> {
            MobEffectInstance.FactorData.CODEC.encodeStart(NbtOps.INSTANCE, factorCalculationData).resultOrPartial(LOGGER::error).ifPresent((factorCalculationDataNbt) -> {
                nbt.put("FactorCalculationData", factorCalculationDataNbt);
            });
        });
    }

    @Nullable
    public static MobEffectInstance load(CompoundTag nbt) {
        int i = nbt.getInt("Id");
        MobEffect mobEffect = MobEffect.byId(i);
        return mobEffect == null ? null : loadSpecifiedEffect(mobEffect, nbt);
    }

    private static MobEffectInstance loadSpecifiedEffect(MobEffect type, CompoundTag nbt) {
        int i = nbt.getByte("Amplifier");
        int j = nbt.getInt("Duration");
        boolean bl = nbt.getBoolean("Ambient");
        boolean bl2 = true;
        if (nbt.contains("ShowParticles", 1)) {
            bl2 = nbt.getBoolean("ShowParticles");
        }

        boolean bl3 = bl2;
        if (nbt.contains("ShowIcon", 1)) {
            bl3 = nbt.getBoolean("ShowIcon");
        }

        MobEffectInstance mobEffectInstance = null;
        if (nbt.contains("HiddenEffect", 10)) {
            mobEffectInstance = loadSpecifiedEffect(type, nbt.getCompound("HiddenEffect"));
        }

        Optional<MobEffectInstance.FactorData> optional;
        if (nbt.contains("FactorCalculationData", 10)) {
            optional = MobEffectInstance.FactorData.CODEC.parse(new Dynamic<>(NbtOps.INSTANCE, nbt.getCompound("FactorCalculationData"))).resultOrPartial(LOGGER::error);
        } else {
            optional = Optional.empty();
        }

        return new MobEffectInstance(type, j, Math.max(i, 0), bl, bl2, bl3, mobEffectInstance, optional);
    }

    @Override
    public int compareTo(MobEffectInstance mobEffectInstance) {
        int i = 32147;
        return (this.getDuration() <= 32147 || mobEffectInstance.getDuration() <= 32147) && (!this.isAmbient() || !mobEffectInstance.isAmbient()) ? ComparisonChain.start().compareFalseFirst(this.isAmbient(), mobEffectInstance.isAmbient()).compareFalseFirst(this.isInfiniteDuration(), mobEffectInstance.isInfiniteDuration()).compare(this.getDuration(), mobEffectInstance.getDuration()).compare(this.getEffect().getColor(), mobEffectInstance.getEffect().getColor()).result() : ComparisonChain.start().compare(this.isAmbient(), mobEffectInstance.isAmbient()).compare(this.getEffect().getColor(), mobEffectInstance.getEffect().getColor()).result();
    }

    public static class FactorData {
        public static final Codec<MobEffectInstance.FactorData> CODEC = RecordCodecBuilder.create((instance) -> {
            return instance.group(ExtraCodecs.NON_NEGATIVE_INT.fieldOf("padding_duration").forGetter((data) -> {
                return data.paddingDuration;
            }), Codec.FLOAT.fieldOf("factor_start").orElse(0.0F).forGetter((data) -> {
                return data.factorStart;
            }), Codec.FLOAT.fieldOf("factor_target").orElse(1.0F).forGetter((data) -> {
                return data.factorTarget;
            }), Codec.FLOAT.fieldOf("factor_current").orElse(0.0F).forGetter((data) -> {
                return data.factorCurrent;
            }), ExtraCodecs.NON_NEGATIVE_INT.fieldOf("ticks_active").orElse(0).forGetter((data) -> {
                return data.ticksActive;
            }), Codec.FLOAT.fieldOf("factor_previous_frame").orElse(0.0F).forGetter((data) -> {
                return data.factorPreviousFrame;
            }), Codec.BOOL.fieldOf("had_effect_last_tick").orElse(false).forGetter((data) -> {
                return data.hadEffectLastTick;
            })).apply(instance, MobEffectInstance.FactorData::new);
        });
        private final int paddingDuration;
        private float factorStart;
        private float factorTarget;
        private float factorCurrent;
        private int ticksActive;
        private float factorPreviousFrame;
        private boolean hadEffectLastTick;

        public FactorData(int paddingDuration, float factorStart, float factorTarget, float factorCurrent, int effectChangedTimestamp, float factorPreviousFrame, boolean hadEffectLastTick) {
            this.paddingDuration = paddingDuration;
            this.factorStart = factorStart;
            this.factorTarget = factorTarget;
            this.factorCurrent = factorCurrent;
            this.ticksActive = effectChangedTimestamp;
            this.factorPreviousFrame = factorPreviousFrame;
            this.hadEffectLastTick = hadEffectLastTick;
        }

        public FactorData(int paddingDuration) {
            this(paddingDuration, 0.0F, 1.0F, 0.0F, 0, 0.0F, false);
        }

        public void tick(MobEffectInstance effect) {
            this.factorPreviousFrame = this.factorCurrent;
            boolean bl = !effect.endsWithin(this.paddingDuration);
            ++this.ticksActive;
            if (this.hadEffectLastTick != bl) {
                this.hadEffectLastTick = bl;
                this.ticksActive = 0;
                this.factorStart = this.factorCurrent;
                this.factorTarget = bl ? 1.0F : 0.0F;
            }

            float f = Mth.clamp((float)this.ticksActive / (float)this.paddingDuration, 0.0F, 1.0F);
            this.factorCurrent = Mth.lerp(f, this.factorStart, this.factorTarget);
        }

        public float getFactor(LivingEntity entity, float tickDelta) {
            if (entity.isRemoved()) {
                this.factorPreviousFrame = this.factorCurrent;
            }

            return Mth.lerp(tickDelta, this.factorPreviousFrame, this.factorCurrent);
        }
    }
}
