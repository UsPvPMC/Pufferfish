package net.minecraft.server;

import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import java.io.File;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.util.SignatureValidator;

// Paper start
public record Services(MinecraftSessionService sessionService, SignatureValidator serviceSignatureValidator, GameProfileRepository profileRepository, GameProfileCache profileCache, @javax.annotation.Nullable io.papermc.paper.configuration.PaperConfigurations paperConfigurations) {

    public Services(MinecraftSessionService sessionService, SignatureValidator signatureValidator, GameProfileRepository profileRepository, GameProfileCache profileCache) {
        this(sessionService, signatureValidator, profileRepository, profileCache, null);
    }

    @Override
    public io.papermc.paper.configuration.PaperConfigurations paperConfigurations() {
        return java.util.Objects.requireNonNull(this.paperConfigurations);
    }
    // Paper end
    public static final String USERID_CACHE_FILE = "usercache.json"; // Paper - private -> public

    public static Services create(YggdrasilAuthenticationService authenticationService, File rootDirectory, File userCacheFile, joptsimple.OptionSet optionSet) throws Exception { // Paper
        MinecraftSessionService minecraftSessionService = authenticationService.createMinecraftSessionService();
        GameProfileRepository gameProfileRepository = authenticationService.createProfileRepository();
        GameProfileCache gameProfileCache = new GameProfileCache(gameProfileRepository, userCacheFile); // Paper
        SignatureValidator signatureValidator = SignatureValidator.from(authenticationService.getServicesKey());
        // Paper start
        final java.nio.file.Path legacyConfigPath = ((File) optionSet.valueOf("paper-settings")).toPath();
        final java.nio.file.Path configDirPath = ((File) optionSet.valueOf("paper-settings-directory")).toPath();
        io.papermc.paper.configuration.PaperConfigurations paperConfigurations = io.papermc.paper.configuration.PaperConfigurations.setup(legacyConfigPath, configDirPath, rootDirectory.toPath(), (File) optionSet.valueOf("spigot-settings"));
        return new Services(minecraftSessionService, signatureValidator, gameProfileRepository, gameProfileCache, paperConfigurations);
        // Paper end
    }
}
