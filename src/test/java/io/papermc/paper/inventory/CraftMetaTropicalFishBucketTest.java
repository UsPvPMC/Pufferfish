package io.papermc.paper.inventory;

import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.entity.TropicalFish;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.TropicalFishBucketMeta;
import org.bukkit.support.AbstractTestingBase;
import org.junit.Assert;
import org.junit.Test;

public class CraftMetaTropicalFishBucketTest extends AbstractTestingBase {

    @Test
    public void testAllCombinations() {
        final var rawMeta = new ItemStack(Material.TROPICAL_FISH_BUCKET).getItemMeta();
        Assert.assertTrue("Meta was not a tropical fish bucket", rawMeta instanceof TropicalFishBucketMeta);

        final var meta = (TropicalFishBucketMeta) rawMeta;

        for (final var bodyColor : DyeColor.values()) {
            for (final var pattern : TropicalFish.Pattern.values()) {
                for (final var patternColor : DyeColor.values()) {
                    meta.setBodyColor(bodyColor);
                    Assert.assertEquals("Body color did not match post body color!", bodyColor, meta.getBodyColor());

                    meta.setPattern(pattern);
                    Assert.assertEquals("Pattern did not match post pattern!", pattern, meta.getPattern());
                    Assert.assertEquals("Body color did not match post pattern!", bodyColor, meta.getBodyColor());

                    meta.setPatternColor(patternColor);
                    Assert.assertEquals("Pattern did not match post pattern color!", pattern, meta.getPattern());
                    Assert.assertEquals("Body color did not match post pattern color!", bodyColor, meta.getBodyColor());
                    Assert.assertEquals("Pattern color did not match post pattern color!", patternColor, meta.getPatternColor());
                }
            }
        }
    }

}
