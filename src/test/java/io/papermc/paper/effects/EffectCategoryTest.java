package io.papermc.paper.effects;

import io.papermc.paper.adventure.PaperAdventure;
import net.minecraft.world.effect.MobEffectCategory;
import org.bukkit.craftbukkit.potion.CraftPotionEffectType;
import org.bukkit.potion.PotionEffectType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class EffectCategoryTest {

    @Test
    public void testEffectCategoriesExist() {
        for (MobEffectCategory mobEffectInfo : MobEffectCategory.values()) {
            assertNotNull(mobEffectInfo + " is missing a bukkit equivalent", CraftPotionEffectType.fromNMS(mobEffectInfo));
        }
    }

    @Test
    public void testCategoryHasEquivalentColors() {
        for (MobEffectCategory mobEffectInfo : MobEffectCategory.values()) {
            PotionEffectType.Category bukkitEffectCategory = CraftPotionEffectType.fromNMS(mobEffectInfo);
            assertEquals(mobEffectInfo.getTooltipFormatting().name() + " doesn't equal " + bukkitEffectCategory.getColor(), bukkitEffectCategory.getColor(), PaperAdventure.asAdventure(mobEffectInfo.getTooltipFormatting()));
        }
    }
}
