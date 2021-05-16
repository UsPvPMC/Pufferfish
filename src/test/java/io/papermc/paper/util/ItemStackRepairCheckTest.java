package io.papermc.paper.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.support.AbstractTestingBase;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ItemStackRepairCheckTest extends AbstractTestingBase {

    @Test
    public void testIsRepariableBy() {
        ItemStack diamondPick = new ItemStack(Material.DIAMOND_PICKAXE);

        assertTrue("diamond pick isn't repairable by a diamond", diamondPick.isRepairableBy(new ItemStack(Material.DIAMOND)));
    }

    @Test
    public void testCanRepair() {
        ItemStack diamond = new ItemStack(Material.DIAMOND);

        assertTrue("diamond can't repair a diamond axe", diamond.canRepair(new ItemStack(Material.DIAMOND_AXE)));
    }

    @Test
    public void testIsNotRepairableBy() {
        ItemStack notDiamondPick = new ItemStack(Material.ACACIA_SAPLING);

        assertFalse("acacia sapling is repairable by a diamond", notDiamondPick.isRepairableBy(new ItemStack(Material.DIAMOND)));
    }

    @Test
    public void testCanNotRepair() {
        ItemStack diamond = new ItemStack(Material.DIAMOND);

        assertFalse("diamond can repair oak button", diamond.canRepair(new ItemStack(Material.OAK_BUTTON)));
    }

    @Test
    public void testInvalidItem() {
        ItemStack badItemStack = new ItemStack(Material.ACACIA_WALL_SIGN);

        assertFalse("acacia wall sign is repairable by diamond", badItemStack.isRepairableBy(new ItemStack(Material.DIAMOND)));
    }
}
