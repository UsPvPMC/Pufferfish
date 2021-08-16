package io.papermc.paper.command.subcommands;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.entity.MobCategory;
import org.junit.Assert;
import org.junit.Test;

public class MobcapsCommandTest {
    @Test
    public void testMobCategoryColors() {
        final Set<String> missing = new HashSet<>();
        for (final MobCategory value : MobCategory.values()) {
            if (!MobcapsCommand.MOB_CATEGORY_COLORS.containsKey(value)) {
                missing.add(value.getName());
            }
        }
        Assert.assertTrue("MobcapsCommand.MOB_CATEGORY_COLORS map missing TextColors for [" + String.join(", ", missing + "]"), missing.isEmpty());
    }
}
