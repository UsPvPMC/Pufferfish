package io.papermc.paper.enchantments;

import net.minecraft.world.item.enchantment.Enchantment.Rarity;
import org.bukkit.craftbukkit.enchantments.CraftEnchantment;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public class EnchantmentRarityTest {

    @Test
    public void test() {
        for (Rarity nmsRarity : Rarity.values()) {
            // Will throw exception if a bukkit counterpart is not found
            CraftEnchantment.fromNMSRarity(nmsRarity);
        }
    }
}
