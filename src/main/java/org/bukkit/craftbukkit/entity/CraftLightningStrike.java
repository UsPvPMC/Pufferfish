package org.bukkit.craftbukkit.entity;

import net.minecraft.world.entity.LightningBolt;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LightningStrike;

public class CraftLightningStrike extends CraftEntity implements LightningStrike {
    public CraftLightningStrike(final CraftServer server, final LightningBolt entity) {
        super(server, entity);
    }

    @Override
    public boolean isEffect() {
        return this.getHandle().visualOnly;
    }

    @Override
    public LightningBolt getHandle() {
        return (LightningBolt) entity;
    }

    @Override
    public String toString() {
        return "CraftLightningStrike";
    }

    @Override
    public EntityType getType() {
        return EntityType.LIGHTNING;
    }

    // Spigot start
    private final LightningStrike.Spigot spigot = new LightningStrike.Spigot() {

        @Override
        public boolean isSilent()
        {
            return CraftLightningStrike.this.getHandle().isSilent;
        }
    };

    @Override
    public LightningStrike.Spigot spigot() {
        return this.spigot;
    }
    // Spigot end
}
