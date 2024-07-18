package org.bukkit;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import com.google.common.collect.Lists;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.RecordItem;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.CraftEquipmentSlot;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.enchantments.EnchantmentTarget;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.material.MaterialData;
import org.bukkit.support.AbstractTestingBase;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class PerMaterialTest extends AbstractTestingBase {
    private static Map<Block, Integer> fireValues;

    @BeforeClass
    public static void getFireValues() {
        PerMaterialTest.fireValues = ((FireBlock) Blocks.FIRE).igniteOdds;
    }

    @Parameters(name = "{index}: {0}")
    public static List<Object[]> data() {
        List<Object[]> list = Lists.newArrayList();
        for (Material material : Material.values()) {
            if (!material.isLegacy()) {
                list.add(new Object[] {material});
            }
        }
        return list;
    }

    @Parameter public Material material;

    @Test
    public void isBlock() {
        if (this.material != Material.AIR && this.material != Material.CAVE_AIR && this.material != Material.VOID_AIR) {
            assertThat(this.material.isBlock(), is(not(CraftMagicNumbers.getBlock(material) == null)));
        }
    }

    @Test
    public void isSolid() {
        if (this.material == Material.AIR) {
            assertFalse(this.material.isSolid());
        } else if (this.material.isBlock()) {
            assertThat(this.material.isSolid(), is(CraftMagicNumbers.getBlock(material).defaultBlockState().getMaterial().blocksMotion()));
        } else {
            assertFalse(this.material.isSolid());
        }
    }

    @Test
    public void isEdible() {
        if (this.material.isBlock()) {
            assertFalse(this.material.isEdible());
        } else {
            assertThat(this.material.isEdible(), is(CraftMagicNumbers.getItem(material).isEdible()));
        }
    }

    @Test
    public void isRecord() {
        assertThat(this.material.isRecord(), is(CraftMagicNumbers.getItem(material) instanceof RecordItem));
    }

    @Test
    public void maxDurability() {
        if (INVALIDATED_MATERIALS.contains(material)) return;

        if (this.material == Material.AIR) {
            assertThat((int) this.material.getMaxDurability(), is(0));
        } else if (this.material.isBlock()) {
            Item item = CraftMagicNumbers.getItem(material);
            assertThat((int) this.material.getMaxDurability(), is(item.getMaxDamage()));
        }
    }

    @Test
    public void maxStackSize() {
        if (INVALIDATED_MATERIALS.contains(material)) return;

        final ItemStack bukkit = new ItemStack(this.material);
        final CraftItemStack craft = CraftItemStack.asCraftCopy(bukkit);
        if (this.material == Material.AIR) {
            final int MAX_AIR_STACK = 0 /* Why can't I hold all of these AIR? */;
            assertThat(this.material.getMaxStackSize(), is(MAX_AIR_STACK));
            assertThat(bukkit.getMaxStackSize(), is(MAX_AIR_STACK));
            assertThat(craft.getMaxStackSize(), is(MAX_AIR_STACK));
        } else {
            assertThat(this.material.getMaxStackSize(), is(CraftMagicNumbers.getItem(material).getMaxStackSize()));
            assertThat(bukkit.getMaxStackSize(), is(this.material.getMaxStackSize()));
            assertThat(craft.getMaxStackSize(), is(this.material.getMaxStackSize()));
        }
    }

    @Test
    public void isTransparent() {
        if (this.material == Material.AIR) {
            assertTrue(this.material.isTransparent());
        } else if (this.material.isBlock()) {
            // assertThat(material.isTransparent(), is(not(CraftMagicNumbers.getBlock(material).getBlockData().getMaterial().blocksLight()))); // PAIL: not unit testable anymore (17w50a)
        } else {
            assertFalse(this.material.isTransparent());
        }
    }

    @Test
    public void isFlammable() {
        if (this.material != Material.AIR && this.material.isBlock()) {
            assertThat(this.material.isFlammable(), is(CraftMagicNumbers.getBlock(material).defaultBlockState().getMaterial().isFlammable()));
        } else {
            assertFalse(this.material.isFlammable());
        }
    }

    @Test
    public void isBurnable() {
        if (this.material.isBlock()) {
            Block block = CraftMagicNumbers.getBlock(material);
            assertThat(this.material.isBurnable(), is(PerMaterialTest.fireValues.containsKey(block) && PerMaterialTest.fireValues.get(block) > 0));
        } else {
            assertFalse(this.material.isBurnable());
        }
    }

    @Test
    public void isFuel() {
        assertThat(this.material.isFuel(), is(AbstractFurnaceBlockEntity.isFuel(new net.minecraft.world.item.ItemStack(CraftMagicNumbers.getItem(material)))));
    }

    @Test
    public void isOccluding() {
        if (this.material.isBlock()) {
            assertThat(this.material.isOccluding(), is(CraftMagicNumbers.getBlock(material).defaultBlockState().isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)));
        } else {
            assertFalse(this.material.isOccluding());
        }
    }

    @Test
    public void hasGravity() {
        if (this.material.isBlock()) {
            assertThat(this.material.hasGravity(), is(CraftMagicNumbers.getBlock(material) instanceof FallingBlock));
        } else {
            assertFalse(this.material.hasGravity());
        }
    }

    @Test
    public void usesDurability() {
        if (!this.material.isBlock()) {
            assertThat(EnchantmentTarget.BREAKABLE.includes(material), is(CraftMagicNumbers.getItem(material).canBeDepleted()));
        } else {
            assertFalse(EnchantmentTarget.BREAKABLE.includes(material));
        }
    }

    @Test
    public void testDurability() {
        if (!this.material.isBlock()) {
            assertThat(this.material.getMaxDurability(), is((short) CraftMagicNumbers.getItem(material).getMaxDamage()));
        } else {
            assertThat(this.material.getMaxDurability(), is((short) 0));
        }
    }

    @Test
    public void testBlock() {
        if (this.material == Material.AIR) {
            assertTrue(this.material.isBlock());
        } else {
            assertThat(this.material.isBlock(), is(equalTo(CraftMagicNumbers.getBlock(material) != null)));
        }
    }

    @Test
    public void testAir() {
        if (this.material.isBlock()) {
            assertThat(this.material.isAir(), is(equalTo(CraftMagicNumbers.getBlock(material).defaultBlockState().isAir())));
        } else {
            assertThat(this.material.isAir(), is(equalTo(false)));
        }
    }

    @Test
    public void testItem() {
        if (this.material == Material.AIR) {
            assertTrue(this.material.isItem());
        } else {
            assertThat(this.material.isItem(), is(equalTo(CraftMagicNumbers.getItem(material) != null)));
        }
    }

    @Test
    public void testInteractable() throws ReflectiveOperationException {
        if (this.material.isBlock()) {
            assertThat(this.material.isInteractable(),
                    is(!CraftMagicNumbers.getBlock(material).getClass()
                            .getMethod("use", BlockState.class, net.minecraft.world.level.Level.class, BlockPos.class, Player.class, InteractionHand.class, BlockHitResult.class)
                            .getDeclaringClass().equals(BlockBehaviour.class)));
        } else {
            assertFalse(this.material.isInteractable());
        }
    }

    @Test
    public void testBlockHardness() {
        if (this.material.isBlock()) {
            assertThat(this.material.getHardness(), is(CraftMagicNumbers.getBlock(material).defaultBlockState().destroySpeed));
        }
    }

    @Test
    public void testBlastResistance() {
        if (this.material.isBlock()) {
            assertThat(this.material.getBlastResistance(), is(CraftMagicNumbers.getBlock(material).getExplosionResistance()));
        }
    }

    @Test
    public void testSlipperiness() {
        if (this.material.isBlock()) {
            assertThat(this.material.getSlipperiness(), is(CraftMagicNumbers.getBlock(material).getFriction()));
        }
    }

    @Test
    public void testBlockDataCreation() {
        if (this.material.isBlock()) {
            assertNotNull(this.material.createBlockData());
        }
    }

    @Test
    public void testCraftingRemainingItem() {
        if (this.material.isItem()) {
            Item expectedItem = CraftMagicNumbers.getItem(material).getCraftingRemainingItem();
            Material expected = expectedItem == null ? null : CraftMagicNumbers.getMaterial(expectedItem);

            assertThat(this.material.getCraftingRemainingItem(), is(expected));
        }
    }

    @Test
    public void testEquipmentSlot() {
        if (this.material.isItem()) {
            EquipmentSlot expected = CraftEquipmentSlot.getSlot(Mob.getEquipmentSlotForItem(CraftItemStack.asNMSCopy(new ItemStack(this.material))));
            assertThat(this.material.getEquipmentSlot(), is(expected));
        }
    }

    @Test
    public void testBlockDataClass() {
        if (this.material.isBlock()) {
            Class<?> expectedClass = material.data;
            if (expectedClass != MaterialData.class) {
                BlockData blockData = Bukkit.createBlockData(material);
                assertTrue(expectedClass + " <> " + blockData.getClass(), expectedClass.isInstance(blockData));
            }
        }
    }

    @Test
    public void testCreativeCategory() {
        if (this.material.isItem()) {
            this.material.getCreativeCategory();
        }
    }
}
