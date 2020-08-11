package io.papermc.paper.world;

import com.destroystokyo.paper.ClientOption;
import java.util.Map;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import org.bukkit.Difficulty;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.attribute.Attribute;
import org.bukkit.support.AbstractTestingBase;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class TranslationKeyTest extends AbstractTestingBase {

    @Test
    public void testChatVisibilityKeys() {
        for (ClientOption.ChatVisibility chatVisibility : ClientOption.ChatVisibility.values()) {
            if (chatVisibility == ClientOption.ChatVisibility.UNKNOWN) continue;
            Assert.assertEquals(chatVisibility + "'s translation key doesn't match", ChatVisiblity.valueOf(chatVisibility.name()).getKey(), chatVisibility.translationKey());
        }
    }

    @Test
    public void testDifficultyKeys() {
        for (Difficulty bukkitDifficulty : Difficulty.values()) {
            Assert.assertEquals(bukkitDifficulty + "'s translation key doesn't match", ((TranslatableContents) net.minecraft.world.Difficulty.byId(bukkitDifficulty.ordinal()).getDisplayName().getContents()).getKey(), bukkitDifficulty.translationKey());
        }
    }

    @Test
    public void testGameruleKeys() {
        for (GameRule<?> rule : GameRule.values()) {
            Assert.assertEquals(rule.getName() + "'s translation doesn't match", org.bukkit.craftbukkit.CraftWorld.getGameRulesNMS().get(rule.getName()).getDescriptionId(), rule.translationKey());
        }
    }

    @Test
    public void testAttributeKeys() {
        for (Attribute attribute : Attribute.values()) {
            Assert.assertEquals("translation key mismatch for " + attribute, org.bukkit.craftbukkit.attribute.CraftAttributeMap.toMinecraft(attribute).getDescriptionId(), attribute.translationKey());
        }
    }

    @Test
    public void testFireworkEffectType() {
        for (FireworkEffect.Type type : FireworkEffect.Type.values()) {
            Assert.assertEquals("translation key mismatch for " + type, net.minecraft.world.item.FireworkRocketItem.Shape.byId(org.bukkit.craftbukkit.inventory.CraftMetaFirework.getNBT(type)).getName(), org.bukkit.FireworkEffect.Type.NAMES.key(type));
        }
    }

    @Test
    @Ignore // TODO fix
    public void testCreativeCategory() {
        // for (CreativeModeTab tab : CreativeModeTabs.tabs()) {
        //     CreativeCategory category = Objects.requireNonNull(CraftCreativeCategory.fromNMS(tab));
        //     Assert.assertEquals("translation key mismatch for " + category, ((TranslatableContents) tab.getDisplayName().getContents()).getKey(), category.translationKey());
        // }
    }

    @Test
    public void testGameMode() {
        for (GameType nms : GameType.values()) {
            GameMode bukkit = GameMode.getByValue(nms.getId());
            Assert.assertNotNull(bukkit);
            Assert.assertEquals("translation key mismatch for " + bukkit, ((TranslatableContents) nms.getLongDisplayName().getContents()).getKey(), bukkit.translationKey());
        }
    }

    @Test
    public void testBiome() {
        for (Map.Entry<ResourceKey<Biome>, Biome> nms : AbstractTestingBase.BIOMES.entrySet()) {
            org.bukkit.block.Biome bukkit = org.bukkit.block.Biome.valueOf(nms.getKey().location().getPath().toUpperCase());
            Assert.assertEquals("translation key mismatch for " + bukkit, nms.getKey().location().toLanguageKey("biome"), bukkit.translationKey());
        }
    }
}
