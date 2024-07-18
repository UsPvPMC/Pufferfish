package org.bukkit.craftbukkit.attribute;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.bukkit.attribute.Attribute;
import org.bukkit.support.AbstractTestingBase;
import org.junit.Assert;
import org.junit.Test;

public class AttributeTest extends AbstractTestingBase {

    @Test
    public void testToBukkit() {
        for (ResourceLocation nms : BuiltInRegistries.ATTRIBUTE.keySet()) {
            Attribute bukkit = CraftAttributeMap.fromMinecraft(nms.toString());

            Assert.assertNotNull(nms.toString(), bukkit);
        }
    }

    @Test
    public void testToNMS() {
        for (Attribute attribute : Attribute.values()) {
            net.minecraft.world.entity.ai.attributes.Attribute nms = CraftAttributeMap.toMinecraft(attribute);

            Assert.assertNotNull(attribute.name(), nms);
        }
    }
}
