package org.bukkit.craftbukkit.block;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.bukkit.Material;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.support.AbstractTestingBase;
import org.junit.Test;

public class BlockStateTest extends AbstractTestingBase {

    @Test
    public void testTileEntityBlockStates() {
        for (Block block : BuiltInRegistries.BLOCK) {
            Material material = CraftMagicNumbers.getMaterial(block);
            Class<?> blockStateType = CraftBlockStates.getBlockStateType(material);
            boolean isCraftBlockEntityState = CraftBlockEntityState.class.isAssignableFrom(blockStateType);

            if (block instanceof EntityBlock) {
                assertTrue(material + " has BlockState of type " + blockStateType.getName() + ", but expected subtype of CraftBlockEntityState", isCraftBlockEntityState);

                // check tile entity type
                BlockEntity tileEntity = ((EntityBlock) block).newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
                BlockEntity materialTileEntity = CraftBlockStates.createNewTileEntity(material);

                if (tileEntity == null) {
                    if (CraftBlockStates.isTileEntityOptional(material)) {
                        continue;
                    }
                    fail(material + " has no tile entity, it be added to CraftBlockStates#isTileEntityOptional");
                }

                assertNotNull(material + " has no tile entity expected tile entity of type " + tileEntity.getClass(), materialTileEntity);
                assertSame(material + " has unexpected tile entity type, expected " + tileEntity.getClass() + " but got " + tileEntity.getClass(), materialTileEntity.getClass(), tileEntity.getClass());
            } else {
                assertTrue(material + " has unexpected CraftBlockEntityState subytype " + blockStateType.getName() + " (but is not a tile)", !isCraftBlockEntityState);
            }
        }
    }

    @Test
    public void testBlockEntityTypes() {
        for (var blockEntityType : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            org.junit.Assert.assertNotNull(CraftBlockStates.getBlockStateType(blockEntityType));
        }
    }
}
