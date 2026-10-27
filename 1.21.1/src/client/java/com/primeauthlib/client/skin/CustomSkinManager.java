package com.primeauthlib.client.skin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import com.primeauthlib.PrimeAuthLib;
import com.primeauthlib.client.security.SecurityUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Manages loading, auto-detecting model (Slim vs Wide), caching, multiple skin switching,
 * drag-and-drop importing, Mojang/URL fetching, and hot-reloading player skins placed in 'auth-libskins' for Minecraft 1.21.1.
 * Fully supports online accounts, offline (cracked) accounts, and offline servers.
 */
public class CustomSkinManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("primeauthlib-skins");
	public static final String SKINS_DIR_NAME = "auth-libskins";
	private static final Gson GSON = new Gson();

	public enum ModelMode {
		AUTO("Auto-Detect"),
		SLIM("Slim (Alex)"),
		WIDE("Wide (Steve)");

		private final String displayName;

		ModelMode(String displayName) {
			this.displayName = displayName;
		}

		public String getDisplayName() {
			return displayName;
		}
	}

	private static Path skinsDir;
	private static ModelMode currentModelMode = ModelMode.AUTO;
	private static String selectedSkinFileName = null;
	private static final Map<String, CachedSkin> CACHED_SKINS = new ConcurrentHashMap<>();
	private static final Map<String, Long> LAST_CHECK_TIMES = new ConcurrentHashMap<>();
	private static final long CHECK_INTERVAL_MS = 1000;

	private record CachedSkin(
		Path path,
		long lastModified,
		ResourceLocation assetId,
		ResourceLocation texturePath,
		DynamicTexture texture,
		PlayerSkin.Model model
	) {}

	public static void init() {
		autoConfigureFolderForVersion();
		ServerSkinManager.init();
	}

	public static void autoConfigureFolderForVersion() {
		String mcVersion = FabricLoader.getInstance().getModContainer("minecraft")
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("1.21.1");

		Path gameDir = FabricLoader.getInstance().getGameDir();
		skinsDir = gameDir.resolve(SKINS_DIR_NAME);

		try {
			boolean isNewDir = !Files.exists(skinsDir);
			Files.createDirectories(skinsDir);

			Path readmeFile = skinsDir.resolve("README.txt");
			if (!Files.exists(readmeFile)) {
				String readmeContent = """
					=====================================================
					  PrimeAuthLib Custom Skins Folder (Minecraft %s)
					=====================================================

					PrimeAuthLib loads skin textures directly from this directory.
					No external auth servers, java agents, or complicated setup needed!

					HOW TO USE:
					1. Put any Minecraft skin (.png) in this folder.
					2. Name it one of the following:
					   - 'skin.png'     -> Applies to your player by default
					   - '<PlayerName>.png' -> Applies to that specific player (e.g. 'Notch.png')
					   - '<UUID>.png'   -> Applies to that player's UUID
					   - 'default.png'  -> Fallback skin for players without a custom skin
					3. Drag-and-drop any .png directly into the in-game 'Auth-Lib Skin' screen!
					4. Use the in-game carousel arrows (◀ / ▶) to switch skins instantly.
					5. Model arms (Slim 3px vs Wide 4px) are AUTO-DETECTED automatically!

					Multiplayer Server Skin Sync:
					- Open 'Server Skin (/skin set)...' to synchronize your skin with server plugins.
					- Supports MineSkin URLs & direct URLs with SHA-256 integrity and TLS encryption.

					Hot Reload:
					- Modify or replace any .png file in this folder while Minecraft is running,
					  and it will automatically refresh in-game within 1 second!
					""".formatted(mcVersion);
				Files.writeString(readmeFile, readmeContent);
			}

			loadConfig();

			if (isNewDir) {
				LOGGER.info("Successfully created and configured {} folder for Minecraft {}", SKINS_DIR_NAME, mcVersion);
			} else {
				LOGGER.info("Configured {} folder for Minecraft {}", SKINS_DIR_NAME, mcVersion);
			}
		} catch (IOException e) {
			LOGGER.error("Failed to auto-configure skins directory at {}", skinsDir, e);
		}
	}

	public static Path getSkinsDir() {
		if (skinsDir == null) {
			skinsDir = FabricLoader.getInstance().getGameDir().resolve(SKINS_DIR_NAME);
		}
		return skinsDir;
	}

	public static ModelMode getModelMode() {
		return currentModelMode;
	}

	public static void setModelMode(ModelMode mode) {
		currentModelMode = mode;
		saveConfig();
		reloadAll();
	}

	public static String getSelectedSkinFileName() {
		return selectedSkinFileName;
	}

	public static void setSelectedSkinFileName(@Nullable String fileName) {
		selectedSkinFileName = fileName;
		saveConfig();
		reloadAll();
	}

	public static List<Path> getAvailableSkins() {
		Path dir = getSkinsDir();
		if (!Files.exists(dir)) {
			return List.of();
		}

		try (Stream<Path> stream = Files.list(dir)) {
			return stream
				.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
				.sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
				.toList();
		} catch (IOException e) {
			LOGGER.error("Failed to list available skins in {}", dir, e);
			return List.of();
		}
	}

	public static int getSelectedSkinIndex() {
		List<Path> skins = getAvailableSkins();
		if (skins.isEmpty()) {
			return -1;
		}

		if (selectedSkinFileName != null && !selectedSkinFileName.isEmpty()) {
			for (int i = 0; i < skins.size(); i++) {
				if (skins.get(i).getFileName().toString().equalsIgnoreCase(selectedSkinFileName)) {
					return i;
				}
			}
		}

		for (int i = 0; i < skins.size(); i++) {
			if (skins.get(i).getFileName().toString().equalsIgnoreCase("skin.png")) {
				return i;
			}
		}
		return 0;
	}

	public static String getSelectedSkinCarouselInfo() {
		List<Path> skins = getAvailableSkins();
		if (skins.isEmpty()) {
			return "§cNo skins in folder (Drag .png here)";
		}
		int index = getSelectedSkinIndex();
		if (index < 0 || index >= skins.size()) {
			index = 0;
		}
		Path current = skins.get(index);
		return "§a" + current.getFileName().toString() + " §7(" + (index + 1) + "/" + skins.size() + ")";
	}

	public static void selectPreviousSkin() {
		List<Path> skins = getAvailableSkins();
		if (skins.isEmpty()) {
			return;
		}
		int current = getSelectedSkinIndex();
		if (current == -1) {
			current = 0;
		}
		int prev = (current - 1 + skins.size()) % skins.size();
		selectedSkinFileName = skins.get(prev).getFileName().toString();
		saveConfig();
		reloadAll();
	}

	public static void selectNextSkin() {
		List<Path> skins = getAvailableSkins();
		if (skins.isEmpty()) {
			return;
		}
		int current = getSelectedSkinIndex();
		if (current == -1) {
			current = 0;
		}
		int next = (current + 1) % skins.size();
		selectedSkinFileName = skins.get(next).getFileName().toString();
		saveConfig();
		reloadAll();
	}

	public static void selectSkin(Path file) {
		if (file != null) {
			selectedSkinFileName = file.getFileName().toString();
			saveConfig();
			reloadAll();
		}
	}

	public static boolean importSkinFile(Path sourcePath) {
		if (sourcePath == null || !Files.exists(sourcePath)) {
			return false;
		}
		String fileName = sourcePath.getFileName().toString();
		if (!fileName.toLowerCase(Locale.ROOT).endsWith(".png")) {
			return false;
		}
		try {
			Path targetDir = getSkinsDir();
			String safeFileName = SecurityUtils.sanitizeFileName(fileName);
			Path targetFile = targetDir.resolve(safeFileName);
			if (Files.exists(targetFile) && !Files.isSameFile(sourcePath, targetFile)) {
				String base = safeFileName.substring(0, safeFileName.length() - 4);
				int count = 1;
				while (Files.exists(targetDir.resolve(base + "_" + count + ".png"))) {
					count++;
				}
				targetFile = targetDir.resolve(base + "_" + count + ".png");
			}
			if (!Files.isSameFile(sourcePath, targetFile)) {
				Files.copy(sourcePath, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			selectSkin(targetFile);
			return true;
		} catch (IOException e) {
			LOGGER.error("Failed to import dropped skin file: {}", sourcePath, e);
			return false;
		}
	}

	public static final Pattern MINESKIN_PATTERN = Pattern.compile("https?://(?:www\\.)?(?:mineskin\\.org/skins/|minesk\\.in/|mineskin\\.org/v2/skins/)([a-zA-Z0-9_-]+)");
	public static final Pattern TEXTURE_NET_PATTERN = Pattern.compile("https?://textures\\.minecraft\\.net/texture/([a-zA-Z0-9]+)");

	public static boolean isPng(byte[] bytes) {
		return SecurityUtils.isValidPng(bytes);
	}

	public static byte[] fetchRawBytesWithRedirects(String urlStr) throws IOException {
		SecurityUtils.HashedPayload payload = SecurityUtils.fetchEncryptedAndHashedPayload(urlStr, SecurityUtils.MAX_SKIN_PAYLOAD_BYTES);
		return payload.bytes();
	}

	@Nullable
	public static String resolveMineSkinTexture(String skinId) {
		if (skinId == null || !skinId.matches("^[a-zA-Z0-9_\\-]+$")) {
			return null;
		}

		try {
			HttpURLConnection conn = SecurityUtils.openEncryptedConnection("https://api.mineskin.org/v2/skins/" + skinId, 6000, 6000);
			if (conn.getResponseCode() == 200) {
				try (InputStream is = conn.getInputStream(); InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
					JsonObject obj = GSON.fromJson(reader, JsonObject.class);
					if (obj != null && obj.has("skin")) {
						JsonObject skin = obj.getAsJsonObject("skin");
						if (skin.has("texture")) {
							return skin.getAsJsonObject("texture").get("url").getAsString();
						}
					}
				}
			}
		} catch (Exception ignored) {}

		try {
			HttpURLConnection conn = SecurityUtils.openEncryptedConnection("https://api.mineskin.org/get/uuid/" + skinId, 6000, 6000);
			if (conn.getResponseCode() == 200) {
				try (InputStream is = conn.getInputStream(); InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
					JsonObject obj = GSON.fromJson(reader, JsonObject.class);
					if (obj != null && obj.has("data")) {
						JsonObject data = obj.getAsJsonObject("data");
						if (data.has("texture")) {
							return data.getAsJsonObject("texture").get("url").getAsString();
						}
					}
				}
			}
		} catch (Exception ignored) {}

		return null;
	}

	public static byte[] downloadSkinBytes(String inputUrl) throws IOException {
		if (inputUrl == null || inputUrl.isBlank()) {
			throw new IOException("Skin URL cannot be empty");
		}
		String cleanUrl = SecurityUtils.validateAndSanitizeUrl(inputUrl);
		String targetUrl = cleanUrl;

		Matcher msMatcher = MINESKIN_PATTERN.matcher(cleanUrl);
		if (msMatcher.find()) {
			String skinId = msMatcher.group(1);
			String apiResolved = resolveMineSkinTexture(skinId);
			if (apiResolved != null && !apiResolved.isBlank()) {
				targetUrl = SecurityUtils.validateAndSanitizeUrl(apiResolved);
			}
		}

		SecurityUtils.HashedPayload payload = SecurityUtils.fetchEncryptedAndHashedPayload(targetUrl, SecurityUtils.MAX_SKIN_PAYLOAD_BYTES);
		byte[] bytes = payload.bytes();

		if (isPng(bytes)) {
			SecurityUtils.validatePngDimensions(bytes, 1024);
			validateSkinImageDimensions(bytes);
			LOGGER.info("Verified skin texture integrity (SHA-256: {})", payload.sha256Hex());
			return bytes;
		}

		String bodyText = new String(bytes, StandardCharsets.UTF_8);
		Matcher texMatcher = TEXTURE_NET_PATTERN.matcher(bodyText);
		if (texMatcher.find()) {
			String resolvedTex = SecurityUtils.validateAndSanitizeUrl(texMatcher.group(0));
			SecurityUtils.HashedPayload texPayload = SecurityUtils.fetchEncryptedAndHashedPayload(resolvedTex, SecurityUtils.MAX_SKIN_PAYLOAD_BYTES);
			byte[] texBytes = texPayload.bytes();
			if (isPng(texBytes)) {
				SecurityUtils.validatePngDimensions(texBytes, 1024);
				validateSkinImageDimensions(texBytes);
				LOGGER.info("Verified skin texture integrity (SHA-256: {})", texPayload.sha256Hex());
				return texBytes;
			}
		}

		Matcher jsonMatcher = Pattern.compile("\"url\"\\s*:\\s*\"(https?:\\\\?/\\\\?/[^\"]+)\"").matcher(bodyText);
		if (jsonMatcher.find()) {
			String unescaped = jsonMatcher.group(1).replace("\\/", "/");
			String sanitizedUnescaped = SecurityUtils.validateAndSanitizeUrl(unescaped);
			SecurityUtils.HashedPayload unescapedPayload = SecurityUtils.fetchEncryptedAndHashedPayload(sanitizedUnescaped, SecurityUtils.MAX_SKIN_PAYLOAD_BYTES);
			byte[] unescapedBytes = unescapedPayload.bytes();
			if (isPng(unescapedBytes)) {
				SecurityUtils.validatePngDimensions(unescapedBytes, 1024);
				validateSkinImageDimensions(unescapedBytes);
				LOGGER.info("Verified skin texture integrity (SHA-256: {})", unescapedPayload.sha256Hex());
				return unescapedBytes;
			}
		}

		throw new IOException("URL did not return a valid PNG skin image (" + (bodyText.contains("<html") ? "HTML page returned" : "invalid format") + ")");
	}

	private static void validateSkinImageDimensions(byte[] bytes) throws IOException {
		try (NativeImage img = NativeImage.read(bytes)) {
			int w = img.getWidth();
			int h = img.getHeight();
			if (w <= 0 || h <= 0) {
				throw new IOException("Skin texture has invalid dimensions: " + w + "x" + h);
			}
		}
	}

	public static CompletableFuture<String> fetchSkinFromUsername(String username) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				if (username == null || username.trim().isEmpty()) {
					return "Username cannot be empty";
				}
				String safeUsername = SecurityUtils.sanitizeFileName(username.trim());

				HttpURLConnection conn = SecurityUtils.openEncryptedConnection("https://api.mojang.com/users/profiles/minecraft/" + safeUsername, 6000, 6000);
				if (conn.getResponseCode() != 200) {
					return "Mojang player '" + safeUsername + "' not found (HTTP " + conn.getResponseCode() + ")";
				}
				JsonObject profileObj;
				try (InputStream is = conn.getInputStream(); InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
					profileObj = GSON.fromJson(reader, JsonObject.class);
				}
				if (profileObj == null || !profileObj.has("id")) {
					return "Failed to parse player profile for " + safeUsername;
				}
				String uuidStr = profileObj.get("id").getAsString();
				if (!uuidStr.matches("^[a-zA-Z0-9]+$")) {
					return "Invalid UUID format received from Mojang";
				}

				HttpURLConnection sessionConn = SecurityUtils.openEncryptedConnection("https://sessionserver.mojang.com/session/minecraft/profile/" + uuidStr, 6000, 6000);
				if (sessionConn.getResponseCode() != 200) {
					return "Failed to fetch session profile from Mojang (HTTP " + sessionConn.getResponseCode() + ")";
				}
				JsonObject sessionObj;
				try (InputStream is = sessionConn.getInputStream(); InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
					sessionObj = GSON.fromJson(reader, JsonObject.class);
				}
				if (sessionObj == null || !sessionObj.has("properties")) {
					return "Player " + safeUsername + " has no textures";
				}
				String textureBase64 = null;
				for (var elem : sessionObj.getAsJsonArray("properties")) {
					JsonObject prop = elem.getAsJsonObject();
					if ("textures".equals(prop.get("name").getAsString())) {
						textureBase64 = prop.get("value").getAsString();
						break;
					}
				}
				if (textureBase64 == null) {
					return "Player " + safeUsername + " has no skin property";
				}
				String decodedJson = new String(Base64.getDecoder().decode(textureBase64), StandardCharsets.UTF_8);
				JsonObject textureObj = GSON.fromJson(decodedJson, JsonObject.class);
				if (!textureObj.has("textures") || !textureObj.getAsJsonObject("textures").has("SKIN")) {
					return "Player " + safeUsername + " has no skin texture defined";
				}
				String skinUrl = SecurityUtils.validateAndSanitizeUrl(textureObj.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString());

				byte[] skinBytes = downloadSkinBytes(skinUrl);
				if (skinBytes == null || skinBytes.length == 0) {
					return "Failed to download skin image from " + skinUrl;
				}
				Path targetFile = getSkinsDir().resolve(safeUsername + ".png");
				Files.write(targetFile, skinBytes);
				selectSkin(targetFile);

				triggerServerSkinCommand(safeUsername, skinUrl);

				return "SUCCESS:" + safeUsername + ".png";
			} catch (Exception e) {
				LOGGER.error("Error fetching skin for username: {}", username, e);
				return "Error: " + e.getMessage();
			}
		});
	}

	public static CompletableFuture<String> fetchSkinFromUrl(String urlStr, @Nullable String customName) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				if (urlStr == null || urlStr.trim().isEmpty()) {
					return "URL cannot be empty";
				}
				String cleanUrl = SecurityUtils.validateAndSanitizeUrl(urlStr);
				byte[] bytes = downloadSkinBytes(cleanUrl);

				String name = customName != null && !customName.trim().isEmpty()
					? SecurityUtils.sanitizeFileName(customName.trim())
					: null;
				if (name == null) {
					Matcher msMatcher = MINESKIN_PATTERN.matcher(cleanUrl);
					if (msMatcher.find()) {
						name = "mineskin_" + msMatcher.group(1);
					} else {
						String pathPart = cleanUrl;
						int qIdx = pathPart.indexOf('?');
						if (qIdx != -1) pathPart = pathPart.substring(0, qIdx);
						int slashIdx = pathPart.lastIndexOf('/');
						if (slashIdx != -1 && pathPart.substring(slashIdx + 1).toLowerCase(Locale.ROOT).endsWith(".png")) {
							name = pathPart.substring(slashIdx + 1, pathPart.length() - 4);
						} else {
							name = "skin_url_" + (System.currentTimeMillis() % 100000);
						}
						name = SecurityUtils.sanitizeFileName(name);
					}
				}
				if (!name.toLowerCase(Locale.ROOT).endsWith(".png")) {
					name += ".png";
				}
				Path targetFile = getSkinsDir().resolve(name);
				Files.write(targetFile, bytes);
				selectSkin(targetFile);

				triggerServerSkinCommand(null, cleanUrl);

				return "SUCCESS:" + name;
			} catch (Exception e) {
				LOGGER.error("Error fetching skin from URL: {}", urlStr, e);
				return "Error: " + e.getMessage();
			}
		});
	}

	public static boolean triggerServerSkinCommand(@Nullable String username, @Nullable String skinUrl) {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.getConnection() == null) {
			return false;
		}

		if (client.isSingleplayer() || client.hasSingleplayerServer() || client.getCurrentServer() == null) {
			LOGGER.info("In singleplayer world - skipping /skin command (local skins apply directly).");
			return false;
		}

		if (skinUrl != null && !skinUrl.isEmpty()) {
			ServerSkinManager.CommandResult res = ServerSkinManager.triggerServerSkin(skinUrl, false);
			return res.success();
		}

		try {
			var dispatcher = client.getConnection().getCommands();
			if (dispatcher == null) {
				return false;
			}
			var root = dispatcher.getRoot();

			String commandToRun = null;
			var skinNode = root.getChild("skin");
			var srNode = root.getChild("skinrestorer");
			if (srNode == null) {
				srNode = root.getChild("sr");
			}

			if (username != null && !username.isEmpty()) {
				if (skinNode != null) {
					if (skinNode.getChild("set") != null) {
						commandToRun = "skin set " + username;
					} else {
						commandToRun = "skin " + username;
					}
				} else if (srNode != null) {
					if (srNode.getChild("set") != null) {
						commandToRun = srNode.getName() + " set " + username;
					} else {
						commandToRun = srNode.getName() + " " + username;
					}
				}
			}

			if (commandToRun != null) {
				String finalCmd = commandToRun;
				client.execute(() -> {
					if (client.getConnection() != null) {
						client.getConnection().sendCommand(finalCmd);
						LOGGER.info("Auto-executed server skin command: /{}", finalCmd);
					}
				});
				return true;
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to check server skin commands: {}", e.getMessage());
		}
		return false;
	}

	public static String getDetectedServerPluginInfo() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.getConnection() == null) {
			return "Singleplayer / Offline";
		}
		try {
			var dispatcher = client.getConnection().getCommands();
			if (dispatcher != null) {
				var root = dispatcher.getRoot();
				if (root.getChild("skin") != null) {
					return "Detected (/skin plugin)";
				}
				if (root.getChild("skinrestorer") != null) {
					return "Detected (/skinrestorer)";
				}
				if (root.getChild("sr") != null) {
					return "Detected (/sr)";
				}
			}
		} catch (Exception ignored) {}
		return "None detected";
	}

	private static void loadConfig() {
		Path configFile = getSkinsDir().resolve("config.properties");
		if (Files.exists(configFile)) {
			try (InputStream is = Files.newInputStream(configFile)) {
				Properties props = new Properties();
				props.load(is);
				String modeStr = props.getProperty("model_mode", "AUTO");
				try {
					currentModelMode = ModelMode.valueOf(modeStr.toUpperCase(Locale.ROOT));
				} catch (IllegalArgumentException ignored) {
					currentModelMode = ModelMode.AUTO;
				}
				selectedSkinFileName = props.getProperty("selected_skin", null);
				String authlibUrl = props.getProperty("authlib_server_url", "");
				com.primeauthlib.client.authlib.AuthlibInjector.setAuthlibServerUrl(authlibUrl);

				String serverUrl = props.getProperty("server_skin_url", "");
				ServerSkinManager.setServerSkinUrl(serverUrl);
				boolean autoSync = Boolean.parseBoolean(props.getProperty("auto_sync_server_skin", "true"));
				ServerSkinManager.setAutoSyncOnJoin(autoSync);
			} catch (IOException e) {
				LOGGER.warn("Failed to load config.properties", e);
			}
		}
	}

	public static void saveConfig() {
		Path configFile = getSkinsDir().resolve("config.properties");
		try (FileOutputStream os = new FileOutputStream(configFile.toFile())) {
			Properties props = new Properties();
			String mcVersion = FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("1.21.1");
			props.setProperty("configured_for_version", mcVersion);
			props.setProperty("model_mode", currentModelMode.name());
			if (selectedSkinFileName != null && !selectedSkinFileName.isEmpty()) {
				props.setProperty("selected_skin", selectedSkinFileName);
			}
			props.setProperty("authlib_server_url", com.primeauthlib.client.authlib.AuthlibInjector.getAuthlibServerUrl());
			props.setProperty("server_skin_url", ServerSkinManager.getServerSkinUrl());
			props.setProperty("auto_sync_server_skin", String.valueOf(ServerSkinManager.isAutoSyncOnJoin()));
			props.store(os, "PrimeAuthLib Configuration");
		} catch (IOException e) {
			LOGGER.warn("Failed to save config.properties", e);
		}
	}

	public static UUID getOfflinePlayerUuid(String username) {
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
	}

	@Nullable
	public static PlayerSkin getCustomSkin(GameProfile profile, @Nullable PlayerSkin originalSkin) {
		Path skinFile = findSkinFileForProfile(profile);
		if (skinFile == null || !Files.exists(skinFile)) {
			return originalSkin;
		}

		try {
			String cacheKey = skinFile.toAbsolutePath().toString();
			long now = System.currentTimeMillis();
			Long lastCheck = LAST_CHECK_TIMES.get(cacheKey);

			CachedSkin cached = CACHED_SKINS.get(cacheKey);
			long lastModified = Files.getLastModifiedTime(skinFile).toMillis();

			if (lastCheck != null && (now - lastCheck < CHECK_INTERVAL_MS) && cached != null && cached.lastModified() == lastModified) {
				return createPlayerSkin(cached, originalSkin);
			}

			LAST_CHECK_TIMES.put(cacheKey, now);

			if (cached == null || cached.lastModified() != lastModified) {
				cached = loadAndRegisterSkin(skinFile, lastModified);
				if (cached != null) {
					CACHED_SKINS.put(cacheKey, cached);
				}
			}

			if (cached != null) {
				return createPlayerSkin(cached, originalSkin);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to load skin from {}", skinFile, e);
		}

		return originalSkin;
	}

	@Nullable
	public static PlayerSkin getCustomSkinByUuid(UUID uuid, @Nullable PlayerSkin originalSkin) {
		Path skinFile = findSkinFileForUuid(uuid);
		if (skinFile == null || !Files.exists(skinFile)) {
			return originalSkin;
		}

		try {
			String cacheKey = skinFile.toAbsolutePath().toString();
			long now = System.currentTimeMillis();
			Long lastCheck = LAST_CHECK_TIMES.get(cacheKey);

			CachedSkin cached = CACHED_SKINS.get(cacheKey);
			long lastModified = Files.getLastModifiedTime(skinFile).toMillis();

			if (lastCheck != null && (now - lastCheck < CHECK_INTERVAL_MS) && cached != null && cached.lastModified() == lastModified) {
				return createPlayerSkin(cached, originalSkin);
			}

			LAST_CHECK_TIMES.put(cacheKey, now);

			if (cached == null || cached.lastModified() != lastModified) {
				cached = loadAndRegisterSkin(skinFile, lastModified);
				if (cached != null) {
					CACHED_SKINS.put(cacheKey, cached);
				}
			}

			if (cached != null) {
				return createPlayerSkin(cached, originalSkin);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to load skin from {}", skinFile, e);
		}

		return originalSkin;
	}

	private static PlayerSkin createPlayerSkin(CachedSkin cached, @Nullable PlayerSkin originalSkin) {
		PlayerSkin.Model modelToUse = switch (currentModelMode) {
			case SLIM -> PlayerSkin.Model.SLIM;
			case WIDE -> PlayerSkin.Model.WIDE;
			case AUTO -> cached.model();
		};

		return new PlayerSkin(
			cached.texturePath(),
			null,
			originalSkin != null ? originalSkin.capeTexture() : null,
			originalSkin != null ? originalSkin.elytraTexture() : null,
			modelToUse,
			true
		);
	}

	@Nullable
	public static PlayerSkin getLocalPlayerPreviewSkin() {
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return null;
		}

		GameProfile profile = null;
		if (client.player != null) {
			profile = client.player.getGameProfile();
		} else if (client.getUser() != null) {
			profile = new GameProfile(client.getUser().getProfileId(), client.getUser().getName());
		}

		if (profile == null) {
			profile = new GameProfile(UUID.randomUUID(), "Player");
		}

		return getCustomSkin(profile, null);
	}

	@Nullable
	public static Path getActiveLocalSkinFile() {
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return null;
		}

		GameProfile profile = null;
		if (client.player != null) {
			profile = client.player.getGameProfile();
		} else if (client.getUser() != null) {
			profile = new GameProfile(client.getUser().getProfileId(), client.getUser().getName());
		}

		if (profile == null) {
			profile = new GameProfile(UUID.randomUUID(), "Player");
		}

		Path skinFile = findSkinFileForProfile(profile);
		if (skinFile != null && Files.exists(skinFile)) {
			return skinFile;
		}

		if (selectedSkinFileName != null && !selectedSkinFileName.isEmpty()) {
			Path selected = resolveFileCaseInsensitive(getSkinsDir(), selectedSkinFileName);
			if (selected != null && Files.exists(selected)) {
				return selected;
			}
		}

		return null;
	}

	public static void reloadAll() {
		CACHED_SKINS.clear();
		LAST_CHECK_TIMES.clear();
		LOGGER.info("Cleared custom skin cache; reloading all skins from {}", SKINS_DIR_NAME);
	}

	@Nullable
	public static Map<com.mojang.authlib.minecraft.MinecraftProfileTexture.Type, com.mojang.authlib.minecraft.MinecraftProfileTexture> getInjectedTextures(GameProfile profile) {
		Path skinFile = findSkinFileForProfile(profile);
		if (skinFile == null || !Files.exists(skinFile)) {
			return null;
		}

		try {
			String url = skinFile.toUri().toURL().toString();
			PlayerSkin.Model model = getModelMode() == ModelMode.SLIM ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
			Map<String, String> metadata = new HashMap<>();
			if (model == PlayerSkin.Model.SLIM) {
				metadata.put("model", "slim");
			}

			com.mojang.authlib.minecraft.MinecraftProfileTexture skinTexture =
				new com.mojang.authlib.minecraft.MinecraftProfileTexture(url, metadata);

			Map<com.mojang.authlib.minecraft.MinecraftProfileTexture.Type, com.mojang.authlib.minecraft.MinecraftProfileTexture> map = new HashMap<>();
			map.put(com.mojang.authlib.minecraft.MinecraftProfileTexture.Type.SKIN, skinTexture);
			return map;
		} catch (Exception e) {
			LOGGER.warn("Failed to build injected textures map for {}", profile.getName(), e);
			return null;
		}
	}

	@Nullable
	public static GameProfile getInjectedProfile(GameProfile original) {
		Map<com.mojang.authlib.minecraft.MinecraftProfileTexture.Type, com.mojang.authlib.minecraft.MinecraftProfileTexture> textures = getInjectedTextures(original);
		if (textures == null || textures.isEmpty()) {
			return null;
		}

		try {
			JsonObject root = new JsonObject();
			root.addProperty("timestamp", System.currentTimeMillis());
			root.addProperty("profileId", original.getId().toString().replace("-", ""));
			root.addProperty("profileName", original.getName());

			JsonObject texturesObj = new JsonObject();
			for (Map.Entry<com.mojang.authlib.minecraft.MinecraftProfileTexture.Type, com.mojang.authlib.minecraft.MinecraftProfileTexture> entry : textures.entrySet()) {
				JsonObject texObj = new JsonObject();
				texObj.addProperty("url", entry.getValue().getUrl());
				if (entry.getValue().getMetadata("model") != null) {
					JsonObject metaObj = new JsonObject();
					metaObj.addProperty("model", entry.getValue().getMetadata("model"));
					texObj.add("metadata", metaObj);
				}
				texturesObj.add(entry.getKey().name(), texObj);
			}
			root.add("textures", texturesObj);

			String json = GSON.toJson(root);
			String base64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

			GameProfile newProfile = new GameProfile(original.getId(), original.getName());
			if (original.getProperties() != null) {
				newProfile.getProperties().putAll(original.getProperties());
			}
			newProfile.getProperties().removeAll("textures");
			newProfile.getProperties().put("textures", new com.mojang.authlib.properties.Property("textures", base64));

			return newProfile;
		} catch (Exception e) {
			LOGGER.warn("Failed to create injected GameProfile for {}", original.getName(), e);
			return null;
		}
	}

	public static PlayerSkin.Model getModelForSkinFile(@Nullable Path skinFile) {
		if (currentModelMode == ModelMode.SLIM) {
			return PlayerSkin.Model.SLIM;
		}
		if (currentModelMode == ModelMode.WIDE) {
			return PlayerSkin.Model.WIDE;
		}
		if (skinFile != null && Files.exists(skinFile)) {
			CachedSkin cached = CACHED_SKINS.get(skinFile.toAbsolutePath().toString());
			if (cached != null) {
				return cached.model();
			}
			try (InputStream is = Files.newInputStream(skinFile)) {
				NativeImage img = NativeImage.read(is);
				PlayerSkin.Model model = detectModel(img);
				img.close();
				return model;
			} catch (Exception ignored) {
			}
		}
		return PlayerSkin.Model.WIDE;
	}

	@Nullable
	public static Path findSkinFileForProfile(GameProfile profile) {
		Path dir = getSkinsDir();
		if (!Files.exists(dir)) {
			return null;
		}

		String name = profile.getName();
		UUID id = profile.getId();

		if (name != null && !name.isEmpty()) {
			Path byName = resolveFileCaseInsensitive(dir, name + ".png");
			if (byName != null) {
				return byName;
			}
		}

		if (id != null) {
			Path byUuid = resolveUuidFile(dir, id);
			if (byUuid != null) {
				return byUuid;
			}
		}

		if (name != null && !name.isEmpty()) {
			UUID offlineUuid = getOfflinePlayerUuid(name);
			Path byOfflineUuid = resolveUuidFile(dir, offlineUuid);
			if (byOfflineUuid != null) {
				return byOfflineUuid;
			}
		}

		if (isLocalPlayer(name, id)) {
			if (selectedSkinFileName != null && !selectedSkinFileName.isEmpty()) {
				Path selected = resolveFileCaseInsensitive(dir, selectedSkinFileName);
				if (selected != null && Files.exists(selected)) {
					return selected;
				}
			}

			Path skinPng = resolveFileCaseInsensitive(dir, "skin.png");
			if (skinPng != null) {
				return skinPng;
			}

			Path singlePng = findSingleSkinFile(dir);
			if (singlePng != null) {
				return singlePng;
			}
		}

		Path defaultPng = resolveFileCaseInsensitive(dir, "default.png");
		if (defaultPng != null) {
			return defaultPng;
		}

		return null;
	}

	@Nullable
	private static Path findSkinFileForUuid(UUID uuid) {
		Path dir = getSkinsDir();
		if (!Files.exists(dir)) {
			return null;
		}

		Path byUuid = resolveUuidFile(dir, uuid);
		if (byUuid != null) {
			return byUuid;
		}

		Minecraft client = Minecraft.getInstance();
		if (client != null && isLocalPlayer(null, uuid)) {
			if (selectedSkinFileName != null && !selectedSkinFileName.isEmpty()) {
				Path selected = resolveFileCaseInsensitive(dir, selectedSkinFileName);
				if (selected != null && Files.exists(selected)) {
					return selected;
				}
			}

			Path skinPng = resolveFileCaseInsensitive(dir, "skin.png");
			if (skinPng != null) {
				return skinPng;
			}

			Path singlePng = findSingleSkinFile(dir);
			if (singlePng != null) {
				return singlePng;
			}
		}

		Path defaultPng = resolveFileCaseInsensitive(dir, "default.png");
		if (defaultPng != null) {
			return defaultPng;
		}

		return null;
	}

	private static boolean isLocalPlayer(@Nullable String name, @Nullable UUID id) {
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return false;
		}

		if (client.getUser() != null) {
			String localUsername = client.getUser().getName();
			UUID localUserUuid = client.getUser().getProfileId();

			if (name != null && localUsername != null && name.equalsIgnoreCase(localUsername)) {
				return true;
			}

			if (id != null && localUserUuid != null && id.equals(localUserUuid)) {
				return true;
			}

			if (id != null && localUsername != null && id.equals(getOfflinePlayerUuid(localUsername))) {
				return true;
			}
		}

		if (client.player != null) {
			if (id != null && id.equals(client.player.getUUID())) {
				return true;
			}
			if (name != null && client.player.getGameProfile() != null && name.equalsIgnoreCase(client.player.getGameProfile().getName())) {
				return true;
			}
		}

		return false;
	}

	@Nullable
	private static Path resolveUuidFile(Path dir, UUID uuid) {
		String uuidStr = uuid.toString();
		Path byUuid = resolveFileCaseInsensitive(dir, uuidStr + ".png");
		if (byUuid != null) {
			return byUuid;
		}

		String compactUuid = uuidStr.replace("-", "");
		return resolveFileCaseInsensitive(dir, compactUuid + ".png");
	}

	@Nullable
	private static Path resolveFileCaseInsensitive(Path dir, String fileName) {
		Path direct = dir.resolve(fileName);
		if (Files.exists(direct)) {
			return direct;
		}

		try (Stream<Path> stream = Files.list(dir)) {
			Optional<Path> found = stream
				.filter(p -> p.getFileName().toString().equalsIgnoreCase(fileName))
				.findFirst();
			return found.orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	@Nullable
	private static Path findSingleSkinFile(Path dir) {
		try (Stream<Path> stream = Files.list(dir)) {
			List<Path> pngFiles = stream
				.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
				.toList();
			if (pngFiles.size() == 1) {
				return pngFiles.get(0);
			}
		} catch (IOException ignored) {}
		return null;
	}

	@Nullable
	private static CachedSkin loadAndRegisterSkin(Path skinFile, long lastModified) {
		try (InputStream is = Files.newInputStream(skinFile)) {
			NativeImage original = NativeImage.read(is);
			NativeImage processed = ensureStandardDimensions(original);

			PlayerSkin.Model detectedModel = detectModel(processed);

			DynamicTexture dynamicTexture = new DynamicTexture(processed);
			dynamicTexture.upload();

			String fileName = skinFile.getFileName().toString();
			if (fileName.toLowerCase(Locale.ROOT).endsWith(".png")) {
				fileName = fileName.substring(0, fileName.length() - 4);
			}
			String sanitizedName = fileName.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase(Locale.ROOT);

			ResourceLocation assetId = ResourceLocation.fromNamespaceAndPath(
				PrimeAuthLib.MOD_ID,
				"skins/" + sanitizedName + "_" + lastModified
			);

			ResourceLocation texturePath = ResourceLocation.fromNamespaceAndPath(
				PrimeAuthLib.MOD_ID,
				"textures/skins/" + sanitizedName + "_" + lastModified + ".png"
			);

			Minecraft.getInstance().getTextureManager().register(texturePath, dynamicTexture);
			Minecraft.getInstance().getTextureManager().register(assetId, dynamicTexture);

			LOGGER.info("Loaded custom skin from {} (Detected Model: {})", skinFile.getFileName(), detectedModel);
			return new CachedSkin(skinFile, lastModified, assetId, texturePath, dynamicTexture, detectedModel);
		} catch (Exception e) {
			LOGGER.error("Failed to read skin image from {}", skinFile, e);
			return null;
		}
	}

	private static NativeImage ensureStandardDimensions(NativeImage image) {
		int width = image.getWidth();
		int height = image.getHeight();

		if (width == 64 && height == 64) {
			return image;
		}

		if (width == 64 && height == 32) {
			NativeImage expanded = new NativeImage(64, 64, true);
			for (int x = 0; x < 64; x++) {
				for (int y = 0; y < 32; y++) {
					expanded.setPixelRGBA(x, y, image.getPixelRGBA(x, y));
				}
			}

			// Copy right leg to left leg
			copyRect(expanded, 0, 16, 16, 48, 16, 16, true);
			// Copy right arm to left arm
			copyRect(expanded, 40, 16, 32, 48, 16, 16, true);

			image.close();
			return expanded;
		}

		return image;
	}

	private static void copyRect(NativeImage src, int srcX, int srcY, int dstX, int dstY, int width, int height, boolean mirror) {
		copyRect(src, src, srcX, srcY, dstX, dstY, width, height, mirror);
	}

	private static void copyRect(NativeImage src, NativeImage dst, int srcX, int srcY, int dstX, int dstY, int width, int height, boolean mirror) {
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int readX = mirror ? (width - 1 - x) : x;
				int pixel = src.getPixelRGBA(srcX + readX, srcY + y);
				dst.setPixelRGBA(dstX + x, dstY + y, pixel);
			}
		}
	}

	public static PlayerSkin.Model detectModel(NativeImage image) {
		int width = image.getWidth();
		int height = image.getHeight();

		if (width != 64 || height != 64) {
			if (width == height && width % 64 == 0) {
				int scale = width / 64;
				if (hasTransparency(image, 50 * scale, 16 * scale, 2 * scale, 4 * scale) ||
					hasTransparency(image, 54 * scale, 20 * scale, 2 * scale, 12 * scale) ||
					hasTransparency(image, 42 * scale, 48 * scale, 2 * scale, 4 * scale) ||
					hasTransparency(image, 46 * scale, 52 * scale, 2 * scale, 12 * scale)) {
					return PlayerSkin.Model.SLIM;
				}
			}
			return PlayerSkin.Model.WIDE;
		}

		if (hasTransparency(image, 50, 16, 2, 4) ||
			hasTransparency(image, 54, 20, 2, 12) ||
			hasTransparency(image, 42, 48, 2, 4) ||
			hasTransparency(image, 46, 52, 2, 12)) {
			return PlayerSkin.Model.SLIM;
		}

		if ((isSolidColor(image, 54, 20, 2, 12, 0xFF000000) && isSolidColor(image, 46, 52, 2, 12, 0xFF000000)) ||
			(isSolidColor(image, 54, 20, 2, 12, 0xFFFFFFFF) && isSolidColor(image, 46, 52, 2, 12, 0xFFFFFFFF))) {
			return PlayerSkin.Model.SLIM;
		}

		return PlayerSkin.Model.WIDE;
	}

	private static boolean hasTransparency(NativeImage image, int startX, int startY, int w, int h) {
		for (int x = startX; x < startX + w; x++) {
			for (int y = startY; y < startY + h; y++) {
				int pixel = image.getPixelRGBA(x, y);
				int alpha = (pixel >> 24) & 0xFF;
				if (alpha < 128) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isSolidColor(NativeImage image, int startX, int startY, int w, int h, int color) {
		for (int x = startX; x < startX + w; x++) {
			for (int y = startY; y < startY + h; y++) {
				if (image.getPixelRGBA(x, y) != color) {
					return false;
				}
			}
		}
		return true;
	}
}
