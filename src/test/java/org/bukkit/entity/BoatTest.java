package org.bukkit.entity;

import net.minecraft.world.entity.vehicle.Boat;
import org.bukkit.craftbukkit.entity.CraftBoat;
import org.junit.Test;

public class BoatTest {

    @Test
    public void testTypes() {
        for (Boat.Type enumBoatType : Boat.Type.values()) {
            CraftBoat.boatTypeFromNms(enumBoatType);
        }

        for (org.bukkit.entity.Boat.Type enumBoatType : org.bukkit.entity.Boat.Type.values()) {
            CraftBoat.boatTypeToNms(enumBoatType);
        }
    }

    @Test
    public void testStatus() {
        for (Boat.Status enumStatus : Boat.Status.values()) {
            CraftBoat.boatStatusFromNms(enumStatus);
        }
    }
}
