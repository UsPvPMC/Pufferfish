package io.papermc.paper.world;

import com.destroystokyo.paper.ClientOption;
import net.minecraft.world.entity.player.ChatVisiblity;
import org.bukkit.Difficulty;
import org.junit.Assert;
import org.junit.Test;

public class TranslationKeyTest {

    @Test
    public void testChatVisibilityKeys() {
        for (ClientOption.ChatVisibility chatVisibility : ClientOption.ChatVisibility.values()) {
            if (chatVisibility == ClientOption.ChatVisibility.UNKNOWN) continue;
            Assert.assertEquals(chatVisibility + "'s translation key doesn't match", ChatVisiblity.valueOf(chatVisibility.name()).getKey(), chatVisibility.translationKey());
        }
    }
}
