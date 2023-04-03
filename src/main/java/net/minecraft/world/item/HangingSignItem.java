package net.minecraft.world.item;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class HangingSignItem extends StandingAndWallBlockItem {
    public HangingSignItem(Block hangingSign, Block wallHangingSign, Item.Properties settings) {
        super(hangingSign, wallHangingSign, settings, Direction.UP);
    }

    @Override
    protected boolean canPlace(LevelReader world, BlockState state, BlockPos pos) {
        Block var5 = state.getBlock();
        if (var5 instanceof WallHangingSignBlock wallHangingSignBlock) {
            if (!wallHangingSignBlock.canPlace(state, world, pos)) {
                return false;
            }
        }

        return super.canPlace(world, state, pos);
    }

    @Override
    protected boolean updateCustomBlockEntityTag(BlockPos pos, Level world, @Nullable Player player, ItemStack stack, BlockState state) {
        boolean bl = super.updateCustomBlockEntityTag(pos, world, player, stack, state);
        if (!world.isClientSide && !bl && player != null) {
            // Paper start - moved in ItemStack use handler for events cancellation
            /*BlockEntity var8 = world.getBlockEntity(pos);
            if (var8 instanceof SignBlockEntity) {
                SignBlockEntity signBlockEntity = (SignBlockEntity)var8;
                player.openTextEdit(signBlockEntity);
            }*/
            SignItem.openSign = pos;
            // Paper end
        }

        return bl;
    }
}
