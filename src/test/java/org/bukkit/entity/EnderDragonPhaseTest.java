package org.bukkit.entity;

import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import org.bukkit.craftbukkit.entity.CraftEnderDragon;
import org.junit.Assert;
import org.junit.Test;

public class EnderDragonPhaseTest {

    @Test
    public void testNotNull() {
        for (EnderDragon.Phase phase : EnderDragon.Phase.values()) {
            EnderDragonPhase dragonControllerPhase = CraftEnderDragon.getMinecraftPhase(phase);
            Assert.assertNotNull(phase.name(), dragonControllerPhase);
            Assert.assertNotNull(phase.name(), CraftEnderDragon.getBukkitPhase(dragonControllerPhase));
        }
    }

    @Test
    public void testBukkitToMinecraft() {
        Assert.assertEquals("CIRCLING", CraftEnderDragon.getMinecraftPhase(EnderDragon.Phase.CIRCLING), EnderDragonPhase.HOLDING_PATTERN);
        Assert.assertEquals("STRAFING", CraftEnderDragon.getMinecraftPhase(EnderDragon.Phase.STRAFING), EnderDragonPhase.STRAFE_PLAYER);
        Assert.assertEquals("FLY_TO_PORTAL", CraftEnderDragon.getMinecraftPhase(EnderDragon.Phase.FLY_TO_PORTAL), EnderDragonPhase.LANDING_APPROACH);
        Assert.assertEquals("LAND_ON_PORTAL", CraftEnderDragon.getMinecraftPhase(EnderDragon.Phase.LAND_ON_PORTAL), EnderDragonPhase.LANDING);
        Assert.assertEquals("LEAVE_PORTAL", CraftEnderDragon.getMinecraftPhase(EnderDragon.Phase.LEAVE_PORTAL), EnderDragonPhase.TAKEOFF);
        Assert.assertEquals("BREATH_ATTACK", CraftEnderDragon.getMinecraftPhase(EnderDragon.Phase.BREATH_ATTACK), EnderDragonPhase.SITTING_FLAMING);
        Assert.assertEquals("SEARCH_FOR_BREATH_ATTACK_TARGET", CraftEnderDragon.getMinecraftPhase(EnderDragon.Phase.SEARCH_FOR_BREATH_ATTACK_TARGET), EnderDragonPhase.SITTING_SCANNING);
        Assert.assertEquals("ROAR_BEFORE_ATTACK", CraftEnderDragon.getMinecraftPhase(EnderDragon.Phase.ROAR_BEFORE_ATTACK), EnderDragonPhase.SITTING_ATTACKING);
        Assert.assertEquals("CHARGE_PLAYER", CraftEnderDragon.getMinecraftPhase(EnderDragon.Phase.CHARGE_PLAYER), EnderDragonPhase.CHARGING_PLAYER);
        Assert.assertEquals("DYING", CraftEnderDragon.getMinecraftPhase(EnderDragon.Phase.DYING), EnderDragonPhase.DYING);
        Assert.assertEquals("HOVER", CraftEnderDragon.getMinecraftPhase(EnderDragon.Phase.HOVER), EnderDragonPhase.HOVERING);
    }

    @Test
    public void testMinecraftToBukkit() {
        Assert.assertEquals("CIRCLING", CraftEnderDragon.getBukkitPhase(EnderDragonPhase.HOLDING_PATTERN), EnderDragon.Phase.CIRCLING);
        Assert.assertEquals("STRAFING", CraftEnderDragon.getBukkitPhase(EnderDragonPhase.STRAFE_PLAYER), EnderDragon.Phase.STRAFING);
        Assert.assertEquals("FLY_TO_PORTAL", CraftEnderDragon.getBukkitPhase(EnderDragonPhase.LANDING_APPROACH), EnderDragon.Phase.FLY_TO_PORTAL);
        Assert.assertEquals("LAND_ON_PORTAL", CraftEnderDragon.getBukkitPhase(EnderDragonPhase.LANDING), EnderDragon.Phase.LAND_ON_PORTAL);
        Assert.assertEquals("LEAVE_PORTAL", CraftEnderDragon.getBukkitPhase(EnderDragonPhase.TAKEOFF), EnderDragon.Phase.LEAVE_PORTAL);
        Assert.assertEquals("BREATH_ATTACK", CraftEnderDragon.getBukkitPhase(EnderDragonPhase.SITTING_FLAMING), EnderDragon.Phase.BREATH_ATTACK);
        Assert.assertEquals("SEARCH_FOR_BREATH_ATTACK_TARGET", CraftEnderDragon.getBukkitPhase(EnderDragonPhase.SITTING_SCANNING), EnderDragon.Phase.SEARCH_FOR_BREATH_ATTACK_TARGET);
        Assert.assertEquals("ROAR_BEFORE_ATTACK", CraftEnderDragon.getBukkitPhase(EnderDragonPhase.SITTING_ATTACKING), EnderDragon.Phase.ROAR_BEFORE_ATTACK);
        Assert.assertEquals("CHARGE_PLAYER", CraftEnderDragon.getBukkitPhase(EnderDragonPhase.CHARGING_PLAYER), EnderDragon.Phase.CHARGE_PLAYER);
        Assert.assertEquals("DYING", CraftEnderDragon.getBukkitPhase(EnderDragonPhase.DYING), EnderDragon.Phase.DYING);
        Assert.assertEquals("HOVER", CraftEnderDragon.getBukkitPhase(EnderDragonPhase.HOVERING), EnderDragon.Phase.HOVER);
    }
}
