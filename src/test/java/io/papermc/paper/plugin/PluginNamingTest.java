package io.papermc.paper.plugin;

import io.papermc.paper.plugin.provider.configuration.PaperPluginMeta;
import org.junit.Assert;
import org.junit.Test;

public class PluginNamingTest {
    private static final String TEST_NAME = "Test_Plugin";
    private static final String TEST_VERSION = "1.0";

    private final PaperPluginMeta pluginMeta;

    public PluginNamingTest() {
        this.pluginMeta = new PaperPluginMeta();
        this.pluginMeta.setName(TEST_NAME);
        this.pluginMeta.setVersion(TEST_VERSION);
    }

    @Test
    public void testName() {
        Assert.assertEquals(TEST_NAME, this.pluginMeta.getName());
    }

    @Test
    public void testDisplayName() {
        Assert.assertEquals(TEST_NAME + " v" + TEST_VERSION, this.pluginMeta.getDisplayName());
    }
}
