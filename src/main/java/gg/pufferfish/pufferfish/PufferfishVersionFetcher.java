package gg.pufferfish.pufferfish;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.GREEN;
import static net.kyori.adventure.text.format.NamedTextColor.RED;

import com.destroystokyo.paper.VersionHistoryManager;
import com.destroystokyo.paper.util.VersionFetcher;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.craftbukkit.CraftServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class PufferfishVersionFetcher implements VersionFetcher {
	
	private static final Logger LOGGER = Logger.getLogger("PufferfishVersionFetcher");
	private static final HttpClient client = HttpClient.newHttpClient();
	
	private static final URI JENKINS_URI = URI.create("https://ci.pufferfish.host/job/Pufferfish-1.19/lastSuccessfulBuild/buildNumber");
	private static final String GITHUB_FORMAT = "https://api.github.com/repos/pufferfish-gg/Pufferfish/compare/ver/1.19...%s";
	
	private static final HttpResponse.BodyHandler<JsonObject> JSON_OBJECT_BODY_HANDLER = responseInfo -> HttpResponse.BodySubscribers
			.mapping(
					HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8),
					string -> new Gson().fromJson(string, JsonObject.class)
			);
	
	@Override
	public long getCacheTime() {
		return TimeUnit.MINUTES.toMillis(30);
	}
	
	@Override
	public @NotNull Component getVersionMessage(final @NotNull String serverVersion) {
		final String[] parts = CraftServer.class.getPackage().getImplementationVersion().split("-");
		@NotNull Component component;
		
		if (parts.length != 3) {
			component = text("Unknown server version.", RED);
		} else {
			final String versionString = parts[2];
			
			try {
				component = this.fetchJenkinsVersion(Integer.parseInt(versionString));
			} catch (NumberFormatException e) {
				component = this.fetchGithubVersion(versionString.substring(1, versionString.length() - 1));
			}
		}
		
		final @Nullable Component history = this.getHistory();
		return history != null ? Component
				.join(JoinConfiguration.noSeparators(), component, Component.newline(), this.getHistory()) : component;
	}
	
	private @NotNull Component fetchJenkinsVersion(final int versionNumber) {
		final HttpRequest request = HttpRequest.newBuilder(JENKINS_URI).build();
		try {
			final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				return text("Received invalid status code (" + response.statusCode() + ") from server.", RED);
			}
			
			int latestVersionNumber;
			try {
				latestVersionNumber = Integer.parseInt(response.body());
			} catch (NumberFormatException e) {
				LOGGER.log(Level.WARNING, "Received invalid response from Jenkins \"" + response.body() + "\".");
				return text("Received invalid response from server.", RED);
			}
			
			final int versionDiff = latestVersionNumber - versionNumber;
			return this.getResponseMessage(versionDiff);
		} catch (IOException | InterruptedException e) {
			LOGGER.log(Level.WARNING, "Failed to look up version from Jenkins", e);
			return text("Failed to retrieve version from server.", RED);
		}
	}
	
	// Based off code contributed by Techcable <Techcable@outlook.com> in Paper/GH-65
	private @NotNull Component fetchGithubVersion(final @NotNull String hash) {
		final URI uri = URI.create(String.format(GITHUB_FORMAT, hash));
		final HttpRequest request = HttpRequest.newBuilder(uri).build();
		try {
			final HttpResponse<JsonObject> response = client.send(request, JSON_OBJECT_BODY_HANDLER);
			if (response.statusCode() != 200) {
				return text("Received invalid status code (" + response.statusCode() + ") from server.", RED);
			}
			
			final JsonObject obj = response.body();
			final int versionDiff = obj.get("behind_by").getAsInt();
			
			return this.getResponseMessage(versionDiff);
		} catch (IOException | InterruptedException e) {
			LOGGER.log(Level.WARNING, "Failed to look up version from GitHub", e);
			return text("Failed to retrieve version from server.", RED);
		}
	}
	
	private @NotNull Component getResponseMessage(final int versionDiff) {
		return switch (Math.max(-1, Math.min(1, versionDiff))) {
			case -1 -> text("You are running an unsupported version of Pufferfish.", RED);
			case 0 -> text("You are on the latest version!", GREEN);
			default -> text("You are running " + versionDiff + " version" + (versionDiff == 1 ? "" : "s") + " beyond. " +
							"Please update your server when possible to maintain stability, security, and receive the latest optimizations.",
					RED);
		};
	}
	
	private @Nullable Component getHistory() {
		final VersionHistoryManager.VersionData data = VersionHistoryManager.INSTANCE.getVersionData();
		if (data == null) {
			return null;
		}
		
		final String oldVersion = data.getOldVersion();
		if (oldVersion == null) {
			return null;
		}
		
		return Component.text("Previous version: " + oldVersion, NamedTextColor.GRAY, TextDecoration.ITALIC);
	}
}