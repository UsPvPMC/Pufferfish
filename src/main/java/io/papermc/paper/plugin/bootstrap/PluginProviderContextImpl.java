package io.papermc.paper.plugin.bootstrap;

import io.papermc.paper.plugin.PluginInitializerManager;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.provider.PluginProvider;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public record PluginProviderContextImpl(PluginMeta config, Path dataFolder,
                                        ComponentLogger logger, Path pluginSource) implements PluginProviderContext {

    public static PluginProviderContextImpl of(PluginMeta config, ComponentLogger logger, Path pluginSource) {
        Path dataFolder = PluginInitializerManager.instance().pluginDirectoryPath().resolve(config.getName());

        return new PluginProviderContextImpl(config, dataFolder, logger, pluginSource);
    }

    public static PluginProviderContextImpl of(PluginProvider<?> provider, Path pluginFolder) {
        Path dataFolder = pluginFolder.resolve(provider.getMeta().getName());

        return new PluginProviderContextImpl(provider.getMeta(), dataFolder, provider.getLogger(), provider.getSource());
    }

    @Override
    public @NotNull PluginMeta getConfiguration() {
        return this.config;
    }

    @Override
    public @NotNull Path getDataDirectory() {
        return this.dataFolder;
    }

    @Override
    public @NotNull ComponentLogger getLogger() {
        return this.logger;
    }

    @Override
    public @NotNull Path getPluginSource() {
        return this.pluginSource;
    }
}
