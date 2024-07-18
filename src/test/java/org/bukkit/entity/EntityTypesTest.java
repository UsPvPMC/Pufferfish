package org.bukkit.entity;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import org.bukkit.support.AbstractTestingBase;
import org.junit.Assert;
import org.junit.Test;

public class EntityTypesTest extends AbstractTestingBase {

    @Test
    public void testMaps() {
        Set<EntityType> allBukkit = Arrays.stream(EntityType.values()).filter((b) -> b.getName() != null).collect(Collectors.toSet());

        for (EntityType<?> nms : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation key = EntityType.getKey(nms);

            org.bukkit.entity.EntityType bukkit = org.bukkit.entity.EntityType.fromName(key.getPath());
            Assert.assertNotNull("Missing nms->bukkit " + key, bukkit);

            Assert.assertTrue("Duplicate entity nms->" + bukkit, allBukkit.remove(bukkit));
        }

        Assert.assertTrue("Unmapped bukkit entities " + allBukkit, allBukkit.isEmpty());
    }

    @Test
    public void testTranslationKey() {
        for (org.bukkit.entity.EntityType entityType : org.bukkit.entity.EntityType.values()) {
            // Currently EntityType#getTranslationKey has a validation for null name then for test skip this and check correct names.
            if (entityType.getName() != null) {
                Assert.assertNotNull("Nulllable translation key for " + entityType, entityType.getTranslationKey());
            }
        }
    }
}
