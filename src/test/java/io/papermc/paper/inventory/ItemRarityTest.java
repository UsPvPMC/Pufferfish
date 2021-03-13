package io.papermc.paper.inventory;

import io.papermc.paper.adventure.PaperAdventure;
import net.minecraft.world.item.Rarity;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ItemRarityTest {

    @Test
    public void testConvertFromNmsToBukkit() {
        for (Rarity nmsRarity : Rarity.values()) {
            assertEquals("rarity names are mis-matched", ItemRarity.values()[nmsRarity.ordinal()].name(), nmsRarity.name());
        }
    }

    @Test
    public void testRarityFormatting() {
        for (Rarity nmsRarity : Rarity.values()) {
            assertEquals("rarity formatting is mis-matched", nmsRarity.color, PaperAdventure.asVanilla(ItemRarity.values()[nmsRarity.ordinal()].color));
        }
    }
}
