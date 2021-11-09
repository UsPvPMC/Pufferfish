package gg.pufferfish.pufferfish;

import gg.pufferfish.pufferfish.simd.SIMDDetection;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.comments.CommentType;
import org.simpleyaml.configuration.file.YamlFile;
import org.simpleyaml.exceptions.InvalidConfigurationException;

public class PufferfishConfig {
	
	private static final YamlFile config = new YamlFile();
	private static int updates = 0;
	
	private static ConfigurationSection convertToBukkit(org.simpleyaml.configuration.ConfigurationSection section) {
		ConfigurationSection newSection = new MemoryConfiguration();
		for (String key : section.getKeys(false)) {
			if (section.isConfigurationSection(key)) {
				newSection.set(key, convertToBukkit(section.getConfigurationSection(key)));
			} else {
				newSection.set(key, section.get(key));
			}
		}
		return newSection;
	}
	
	public static ConfigurationSection getConfigCopy() {
		return convertToBukkit(config);
	}
	
	public static int getUpdates() {
		return updates;
	}
	
	public static void load() throws IOException {
		File configFile = new File("pufferfish.yml");
		
		if (configFile.exists()) {
			try {
				config.load(configFile);
			} catch (InvalidConfigurationException e) {
				throw new IOException(e);
			}
		}
		
		getString("info.version", "1.0");
		setComment("info",
				"Pufferfish Configuration",
				"Check out Pufferfish Host for maximum performance server hosting: https://pufferfish.host",
				"Join our Discord for support: https://discord.gg/reZw4vQV9H",
				"Download new builds at https://ci.pufferfish.host/job/Pufferfish");
		
		for (Method method : PufferfishConfig.class.getDeclaredMethods()) {
			if (Modifier.isStatic(method.getModifiers()) && Modifier.isPrivate(method.getModifiers()) && method.getParameterCount() == 0 &&
					method.getReturnType() == Void.TYPE && !method.getName().startsWith("lambda")) {
				method.setAccessible(true);
				try {
					method.invoke(null);
				} catch (Throwable t) {
					MinecraftServer.LOGGER.warn("Failed to load configuration option from " + method.getName(), t);
				}
			}
		}
		
		updates++;
		
		config.save(configFile);
		
		// Attempt to detect vectorization
		try {
			SIMDDetection.isEnabled = SIMDDetection.canEnable(PufferfishLogger.LOGGER);
			SIMDDetection.versionLimited = SIMDDetection.getJavaVersion() != 17 && SIMDDetection.getJavaVersion() != 18 && SIMDDetection.getJavaVersion() != 19;
		} catch (NoClassDefFoundError | Exception ignored) {
			ignored.printStackTrace();
		}
		
		if (SIMDDetection.isEnabled) {
			PufferfishLogger.LOGGER.info("SIMD operations detected as functional. Will replace some operations with faster versions.");
		} else if (SIMDDetection.versionLimited) {
			PufferfishLogger.LOGGER.warning("Will not enable SIMD! These optimizations are only safely supported on Java 17, Java 18, and Java 19.");
		} else {
			PufferfishLogger.LOGGER.warning("SIMD operations are available for your server, but are not configured!");
			PufferfishLogger.LOGGER.warning("To enable additional optimizations, add \"--add-modules=jdk.incubator.vector\" to your startup flags, BEFORE the \"-jar\".");
			PufferfishLogger.LOGGER.warning("If you have already added this flag, then SIMD operations are not supported on your JVM or CPU.");
			PufferfishLogger.LOGGER.warning("Debug: Java: " + System.getProperty("java.version") + ", test run: " + SIMDDetection.testRun);
		}
	}
	
	private static void setComment(String key, String... comment) {
		if (config.contains(key)) {
			config.setComment(key, String.join("\n", comment), CommentType.BLOCK);
		}
	}
	
	private static void ensureDefault(String key, Object defaultValue, String... comment) {
		if (!config.contains(key)) {
			config.set(key, defaultValue);
			config.setComment(key, String.join("\n", comment), CommentType.BLOCK);
		}
	}
	
	private static boolean getBoolean(String key, boolean defaultValue, String... comment) {
		return getBoolean(key, null, defaultValue, comment);
	}
	
	private static boolean getBoolean(String key, @Nullable String oldKey, boolean defaultValue, String... comment) {
		ensureDefault(key, defaultValue, comment);
		return config.getBoolean(key, defaultValue);
	}
	
	private static int getInt(String key, int defaultValue, String... comment) {
		return getInt(key, null, defaultValue, comment);
	}
	
	private static int getInt(String key, @Nullable String oldKey, int defaultValue, String... comment) {
		ensureDefault(key, defaultValue, comment);
		return config.getInt(key, defaultValue);
	}
	
	private static double getDouble(String key, double defaultValue, String... comment) {
		return getDouble(key, null, defaultValue, comment);
	}
	
	private static double getDouble(String key, @Nullable String oldKey, double defaultValue, String... comment) {
		ensureDefault(key, defaultValue, comment);
		return config.getDouble(key, defaultValue);
	}
	
	private static String getString(String key, String defaultValue, String... comment) {
		return getOldString(key, null, defaultValue, comment);
	}
	
	private static String getOldString(String key, @Nullable String oldKey, String defaultValue, String... comment) {
		ensureDefault(key, defaultValue, comment);
		return config.getString(key, defaultValue);
	}
	
	private static List<String> getStringList(String key, List<String> defaultValue, String... comment) {
		return getStringList(key, null, defaultValue, comment);
	}
	
	private static List<String> getStringList(String key, @Nullable String oldKey, List<String> defaultValue, String... comment) {
		ensureDefault(key, defaultValue, comment);
		return config.getStringList(key);
	}
	
	public static String sentryDsn;
	private static void sentry() {
		String sentryEnvironment = System.getenv("SENTRY_DSN");
		String sentryConfig = getString("sentry-dsn", "", "Sentry DSN for improved error logging, leave blank to disable", "Obtain from https://sentry.io/");
		
		sentryDsn = sentryEnvironment == null ? sentryConfig : sentryEnvironment;
		if (sentryDsn != null && !sentryDsn.isBlank()) {
			gg.pufferfish.pufferfish.sentry.SentryManager.init();
		}
	}
	
	public static boolean enableBooks;
	private static void books() {
		enableBooks = getBoolean("enable-books", true,
				"Whether or not books should be writeable.",
				"Servers that anticipate being a target for duping may want to consider",
				"disabling this option.",
				"This can be overridden per-player with the permission pufferfish.usebooks");
	}
	
	public static boolean enableSuffocationOptimization;
	private static void suffocationOptimization() {
		enableSuffocationOptimization = getBoolean("enable-suffocation-optimization", true,
				"Optimizes the suffocation check by selectively skipping",
				"the check in a way that still appears vanilla. This should",
				"be left enabled on most servers, but is provided as a",
				"configuration option if the vanilla deviation is undesirable.");
	}
	
}
