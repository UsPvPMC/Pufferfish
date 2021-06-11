package org.bukkit.entity;

import org.bukkit.craftbukkit.entity.CraftPanda;
import org.junit.Assert;
import org.junit.Test;

public class PandaGeneTest {

    @Test
    public void testBukkit() {
        for (Panda.Gene gene : Panda.Gene.values()) {
            net.minecraft.world.entity.animal.Panda.Gene nms = CraftPanda.toNms(gene); // Paper - remap fix

            Assert.assertNotNull("NMS gene null for " + gene, nms);
            Assert.assertEquals("Recessive status did not match " + gene, gene.isRecessive(), nms.isRecessive());
            Assert.assertEquals("Gene did not convert back " + gene, gene, CraftPanda.fromNms(nms));
        }
    }

    @Test
    public void testNMS() {
        for (net.minecraft.world.entity.animal.Panda.Gene gene : net.minecraft.world.entity.animal.Panda.Gene.values()) { // Paper - remap fix
            org.bukkit.entity.Panda.Gene bukkit = CraftPanda.fromNms(gene);

            Assert.assertNotNull("Bukkit gene null for " + gene, bukkit);
            Assert.assertEquals("Recessive status did not match " + gene, gene.isRecessive(), bukkit.isRecessive());
            Assert.assertEquals("Gene did not convert back " + gene, gene, CraftPanda.toNms(bukkit));
        }
    }
}
