package io.papermc.paper.advancement;

import io.papermc.paper.adventure.PaperAdventure;
import net.kyori.adventure.text.format.TextColor;
import net.minecraft.advancements.FrameType;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AdvancementFrameTest {

    @Test
    public void test() {
        for (FrameType nmsFrameType : FrameType.values()) {
            final TextColor expectedColor = PaperAdventure.asAdventure(nmsFrameType.getChatColor());
            final String expectedTranslationKey = ((TranslatableContents) nmsFrameType.getDisplayName().getContents()).getKey();
            final var frame = PaperAdvancementDisplay.asPaperFrame(nmsFrameType);
            assertEquals("The translation keys should be the same", expectedTranslationKey, frame.translationKey());
            assertEquals("The frame colors should be the same", expectedColor, frame.color());
            assertEquals(nmsFrameType.getName(), AdvancementDisplay.Frame.NAMES.key(frame));
        }
    }
}
