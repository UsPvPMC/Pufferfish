package net.minecraft.world.level.block.entity;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Dynamic;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.vibrations.VibrationListener;
import org.slf4j.Logger;

public class SculkSensorBlockEntity extends BlockEntity implements VibrationListener.VibrationListenerConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private VibrationListener listener;
    public int lastVibrationFrequency;

    public SculkSensorBlockEntity(BlockPos pos, BlockState state) {
        super(BlockEntityType.SCULK_SENSOR, pos, state);
        this.listener = new VibrationListener(new BlockPositionSource(this.worldPosition), ((SculkSensorBlock)state.getBlock()).getListenerRange(), this);
    }

    @Override
    public void load(CompoundTag nbt) {
        super.load(nbt);
        this.lastVibrationFrequency = nbt.getInt("last_vibration_frequency");
        if (nbt.contains("listener", 10)) {
            VibrationListener.codec(this).parse(new Dynamic<>(NbtOps.INSTANCE, nbt.getCompound("listener"))).resultOrPartial(LOGGER::error).ifPresent((listener) -> {
                this.listener = listener;
            });
        }

    }

    @Override
    protected void saveAdditional(CompoundTag nbt) {
        super.saveAdditional(nbt);
        nbt.putInt("last_vibration_frequency", this.lastVibrationFrequency);
        VibrationListener.codec(this).encodeStart(NbtOps.INSTANCE, this.listener).resultOrPartial(LOGGER::error).ifPresent((listenerNbt) -> {
            nbt.put("listener", listenerNbt);
        });
    }

    public VibrationListener getListener() {
        return this.listener;
    }

    public int getLastVibrationFrequency() {
        return this.lastVibrationFrequency;
    }

    @Override
    public boolean canTriggerAvoidVibration() {
        return true;
    }

    @Override
    public boolean shouldListen(ServerLevel world, GameEventListener listener, BlockPos pos, GameEvent event, @Nullable GameEvent.Context emitter) {
        return !pos.equals(this.getBlockPos()) || event != GameEvent.BLOCK_DESTROY && event != GameEvent.BLOCK_PLACE ? SculkSensorBlock.canActivate(this.getBlockState()) : false;
    }

    @Override
    public void onSignalReceive(ServerLevel world, GameEventListener listener, BlockPos pos, GameEvent event, @Nullable Entity entity, @Nullable Entity sourceEntity, float distance) {
        BlockState blockState = this.getBlockState();
        if (SculkSensorBlock.canActivate(blockState)) {
            this.lastVibrationFrequency = VibrationListener.getGameEventFrequency(event);
            SculkSensorBlock.activate(entity, world, this.worldPosition, blockState, getRedstoneStrengthForDistance(distance, listener.getListenerRadius()));
        }

    }

    @Override
    public void onSignalSchedule() {
        this.setChanged();
    }

    public static int getRedstoneStrengthForDistance(float distance, int range) {
        double d = (double)distance / (double)range;
        return Math.max(1, 15 - Mth.floor(d * 15.0D));
    }

    public void setLastVibrationFrequency(int lastVibrationFrequency) {
        this.lastVibrationFrequency = lastVibrationFrequency;
    }
}
