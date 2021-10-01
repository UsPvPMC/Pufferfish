package io.papermc.paper.scoreboard;

import net.minecraft.world.scores.Scoreboard;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboardTranslations;
import org.bukkit.scoreboard.DisplaySlot;
import org.junit.Test;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

public class DisplaySlotTest {

    @Test
    public void testBukkitToMinecraftDisplaySlots() {
        for (DisplaySlot value : DisplaySlot.values()) {
            assertNotEquals(-1, CraftScoreboardTranslations.fromBukkitSlot(value));
        }
    }

    @Test
    public void testMinecraftToBukkitDisplaySlots() {
        for (String name : Scoreboard.getDisplaySlotNames()) {
            assertNotNull(CraftScoreboardTranslations.toBukkitSlot(Scoreboard.getDisplaySlotByName(name)));
        }
    }
}
