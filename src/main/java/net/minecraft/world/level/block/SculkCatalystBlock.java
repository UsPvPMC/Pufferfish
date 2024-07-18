package net.minecraft.world.level.block;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SculkCatalystBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.gameevent.GameEventListener;

public class SculkCatalystBlock extends BaseEntityBlock {

    public static final int PULSE_TICKS = 8;
    public static final BooleanProperty PULSE = BlockStateProperties.BLOOM;
    private final IntProvider xpRange = ConstantInt.of(5);

    public SculkCatalystBlock(BlockBehaviour.Properties settings) {
        super(settings);
        this.registerDefaultState((BlockState) ((BlockState) this.stateDefinition.any()).setValue(SculkCatalystBlock.PULSE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SculkCatalystBlock.PULSE);
    }

    @Override
    public void tick(BlockState state, ServerLevel world, BlockPos pos, RandomSource random) {
        if ((Boolean) state.getValue(SculkCatalystBlock.PULSE)) {
            world.setBlock(pos, (BlockState) state.setValue(SculkCatalystBlock.PULSE, false), 3);
        }

    }

    public static void bloom(ServerLevel world, BlockPos pos, BlockState state, RandomSource random) {
        world.setBlock(pos, (BlockState) state.setValue(SculkCatalystBlock.PULSE, true), 3);
        world.scheduleTick(pos, state.getBlock(), 8);
        world.sendParticles(ParticleTypes.SCULK_SOUL, (double) pos.getX() + 0.5D, (double) pos.getY() + 1.15D, (double) pos.getZ() + 0.5D, 2, 0.2D, 0.0D, 0.2D, 0.0D);
        world.playSound((Player) null, pos, SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.BLOCKS, 2.0F, 0.6F + random.nextFloat() * 0.4F);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SculkCatalystBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> GameEventListener getListener(ServerLevel world, T blockEntity) {
        if (blockEntity instanceof SculkCatalystBlockEntity) {
            SculkCatalystBlockEntity sculkcatalystblockentity = (SculkCatalystBlockEntity) blockEntity;

            return sculkcatalystblockentity;
        } else {
            return null;
        }
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        return world.isClientSide ? null : createTickerHelper(type, BlockEntityType.SCULK_CATALYST, SculkCatalystBlockEntity::serverTick);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void spawnAfterBreak(BlockState state, ServerLevel world, BlockPos pos, ItemStack tool, boolean dropExperience) {
        super.spawnAfterBreak(state, world, pos, tool, dropExperience);
        // CraftBukkit start - Delegate to getExpDrop
    }

    @Override
    public int getExpDrop(BlockState iblockdata, ServerLevel worldserver, BlockPos blockposition, ItemStack itemstack, boolean flag) {
        if (flag) {
            return this.tryDropExperience(worldserver, blockposition, itemstack, this.xpRange);
        }

        return 0;
        // CraftBukkit end
    }
}
