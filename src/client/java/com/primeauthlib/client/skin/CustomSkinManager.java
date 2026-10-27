package com.primeauthlib.client.skin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.primeauthlib.PrimeAuthLib;
import com.primeauthlib.client.security.SecurityUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
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
 * drag-and-drop importing, Mojang/URL fetching, and hot-reloading player skins placed in 'auth-libskins'.
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
	private static final long CHECK_INTERVAL_MS = 1000; // Check disk timestamp at most once per second per file

	// In-memory directory index and negative/positive lookup caches for 144+ FPS on servers
	private static final Map<String, Path> FILE_NAME_INDEX = new ConcurrentHashMap<>();
	private static volatile List<Path> cachedAvailableSkins = null;
	private static volatile long lastDirCheckTime = 0;
	private static volatile long lastKnownDirModified = -1;
	private static final long DIR_POLL_INTERVAL_MS = 3000;

	private static final Map<UUID, PlayerSkin> UUID_SKIN_CACHE = new ConcurrentHashMap<>();
	private static final java.util.Set<UUID> NEGATIVE_UUID_CACHE = ConcurrentHashMap.newKeySet();

	private static volatile String cachedLocalName = null;
	private static volatile UUID cachedLocalUuid = null;
	private static volatile UUID cachedLocalOfflineUuid = null;

	private record CachedSkin(
		Path path,
		long lastModified,
		Identifier assetId,
		Identifier texturePath,
		DynamicTexture texture,
		PlayerModelType model,
		PlayerSkin baseSkin
	) {}

	public static void init() {
		autoConfigureFolderForVersion();
		ServerSkinManager.init();
	}

	public static void autoConfigureFolderForVersion() {
		String mcVersion = FabricLoader.getInstance().getModContainer("minecraft")
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");

		Path gameDir = FabricLoader.getInstance().getGameDir();
		skinsDir = gameDir.resolve(SKINS_DIR_NAME);

		try {
			boolean isNewDir = !Files.exists(skinsDir);
			if (isNewDir) {
				Files.createDirectories(skinsDir);
				LOGGER.info("Created auth-libskins directory for version {}: {}", mcVersion, skinsDir.toAbsolutePath());
			}

			// If current version folder has no skins yet, search and auto-migrate from existing versions or .minecraft
			refreshFileIndex();
			List<Path> existingSkins = getAvailableSkins();
			if (existingSkins.isEmpty()) {
				checkAndMigrateFromOtherVersions(skinsDir, gameDir);
				refreshFileIndex();
			}

			loadConfig();
			saveConfig();

			Path readme = skinsDir.resolve("README.txt");
			if (!Files.exists(readme)) {
				String readmeContent = """
					=== PrimeAuthLib - auth-libskins ===
					Auto-Configured for Minecraft: """ + mcVersion + """


					Place your custom skin PNG file(s) here!
					Works seamlessly for BOTH online and offline (cracked) accounts and servers.

					Features:
					1. Multiple Skins & Carousel:
					   - Place as many .png skins as you want in this folder.
					   - Use in-game GUI Left/Right arrow buttons (< and >) to cycle through skins instantly!
					   - Drag & drop any .png image directly onto the GUI to import it immediately.
					2. Fetch from Username:
					   - Click "Fetch Skin..." in-game to download any player's skin by username.
					3. Server Skin Sync:
					   - Use "Server Skin (/skin)..." tab for MineSkin or direct URLs (e.g. https://mineskin.org/skins/828c0e10e02c4f02921b2b8244f3ae35).
					   - Auto-executes server skin commands (/skin url <url>, /sr, etc.) when on a multiplayer server.
					   - Singleplayer worlds automatically skip /skin commands ("no /skin in the worlds").
					4. Model Auto-Detection:
					   - Automatically detects Slim (Alex / 3-pixel arms) vs Wide (Steve / 4-pixel arms).
					5. Hot-Reloading:
					   - Replacing or saving files updates in-game immediately without restarting!
					""";
				Files.writeString(readme, readmeContent);
			}

			LOGGER.info("PrimeAuthLib folder auto-configured for Minecraft {} at: {}", mcVersion, skinsDir.toAbsolutePath());
		} catch (IOException e) {
			LOGGER.error("Failed to auto-configure skins directory for Minecraft {}", mcVersion, e);
		}
	}

	private static void checkAndMigrateFromOtherVersions(Path targetDir, Path currentGameDir) {
		List<Path> candidateDirs = new java.util.ArrayList<>();

		// 1. User home default .minecraft/auth-libskins
		String userHome = System.getProperty("user.home");
		if (userHome != null) {
			candidateDirs.add(Path.of(userHome, ".minecraft", SKINS_DIR_NAME));
			candidateDirs.add(Path.of(userHome, ".var", "app", "org.prismlauncher.PrismLauncher", "data", "PrismLauncher", SKINS_DIR_NAME));
		}

		// 2. Parent directory (e.g. PrismLauncher/instances/ or MultiMC/instances/)
		if (currentGameDir.getParent() != null) {
			candidateDirs.add(currentGameDir.getParent().resolve(SKINS_DIR_NAME));
			if (currentGameDir.getParent().getParent() != null) {
				candidateDirs.add(currentGameDir.getParent().getParent().resolve(SKINS_DIR_NAME));
				candidateDirs.add(currentGameDir.getParent().getParent().resolve(".minecraft").resolve(SKINS_DIR_NAME));
			}
		}

		for (Path candidate : candidateDirs) {
			if (candidate != null && Files.exists(candidate) && !candidate.equals(targetDir)) {
				try (Stream<Path> stream = Files.list(candidate)) {
					List<Path> pngs = stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")).toList();
					if (!pngs.isEmpty()) {
						LOGGER.info("Auto-importing {} skin(s) from existing installation: {}", pngs.size(), candidate.toAbsolutePath());
						for (Path png : pngs) {
							Path dest = targetDir.resolve(png.getFileName());
							if (!Files.exists(dest)) {
								Files.copy(png, dest);
							}
						}
						Path oldConfig = candidate.resolve("config.properties");
						Path newConfig = targetDir.resolve("config.properties");
						if (Files.exists(oldConfig) && !Files.exists(newConfig)) {
							Files.copy(oldConfig, newConfig);
						}
						break;
					}
				} catch (Exception e) {
					LOGGER.debug("Could not read candidate skins dir {}", candidate, e);
				}
			}
		}
	}

	public static Path getSkinsDir() {
		if (skinsDir == null) {
			init();
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

	public static synchronized void refreshFileIndex() {
		Path dir = getSkinsDir();
		if (!Files.exists(dir)) {
			FILE_NAME_INDEX.clear();
			cachedAvailableSkins = List.of();
			return;
		}
		Map<String, Path> newIndex = new HashMap<>();
		List<Path> skins = new java.util.ArrayList<>();
		try (Stream<Path> stream = Files.list(dir)) {
			stream.filter(Files::isRegularFile).forEach(p -> {
				String name = p.getFileName().toString();
				String lower = name.toLowerCase(Locale.ROOT);
				if (lower.endsWith(".png")) {
					newIndex.put(lower, p);
					skins.add(p);
				}
			});
		} catch (IOException e) {
			LOGGER.error("Failed to index skins directory", e);
		}
		skins.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
		FILE_NAME_INDEX.clear();
		FILE_NAME_INDEX.putAll(newIndex);
		cachedAvailableSkins = java.util.Collections.unmodifiableList(skins);
		try {
			lastKnownDirModified = Files.getLastModifiedTime(dir).toMillis();
		} catch (Exception ignored) {}
	}

	private static void checkDirectoryUpdateIfNeeded() {
		long now = System.currentTimeMillis();
		if (now - lastDirCheckTime < DIR_POLL_INTERVAL_MS) {
			return;
		}
		lastDirCheckTime = now;
		try {
			Path dir = getSkinsDir();
			if (Files.exists(dir)) {
				long modified = Files.getLastModifiedTime(dir).toMillis();
				if (modified != lastKnownDirModified) {
					refreshFileIndex();
					invalidateSkinCaches();
				}
			}
		} catch (Exception ignored) {}
	}

	public static List<Path> getAvailableSkins() {
		checkDirectoryUpdateIfNeeded();
		if (cachedAvailableSkins == null) {
			refreshFileIndex();
		}
		return cachedAvailableSkins;
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
			Path targetFile = targetDir.resolve(fileName);
			if (Files.exists(targetFile) && !Files.isSameFile(sourcePath, targetFile)) {
				String base = fileName.substring(0, fileName.length() - 4);
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

		// 1. Try v2 API over encrypted HTTPS connection
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

		// 2. Try v1 / get / uuid API
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

		// If input is a MineSkin URL, attempt to resolve via API
		Matcher msMatcher = MINESKIN_PATTERN.matcher(cleanUrl);
		if (msMatcher.find()) {
			String skinId = msMatcher.group(1);
			String apiResolved = resolveMineSkinTexture(skinId);
			if (apiResolved != null && !apiResolved.isBlank()) {
				targetUrl = SecurityUtils.validateAndSanitizeUrl(apiResolved);
			}
		}

		// Download bytes over encrypted HTTPS with SHA-256 integrity check and size cap
		SecurityUtils.HashedPayload payload = SecurityUtils.fetchEncryptedAndHashedPayload(targetUrl, SecurityUtils.MAX_SKIN_PAYLOAD_BYTES);
		byte[] bytes = payload.bytes();

		// If it's already a valid PNG, validate dimensions and return
		if (isPng(bytes)) {
			SecurityUtils.validatePngDimensions(bytes, 1024);
			validateSkinImageDimensions(bytes);
			LOGGER.info("Verified skin texture integrity (SHA-256: {})", payload.sha256Hex());
			return bytes;
		}

		// If NOT a PNG, check if the response body contains a textures.minecraft.net/texture/<hash> link
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

		// Check for unescaped JSON texture URL
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

				// 1. Get UUID from Mojang API over encrypted HTTPS connection
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

				// 2. Get Session Profile with skin URL over encrypted HTTPS connection
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

				// 3. Download the skin PNG with SHA-256 integrity verification
				byte[] skinBytes = downloadSkinBytes(skinUrl);
				if (skinBytes == null || skinBytes.length == 0) {
					return "Failed to download skin image from " + skinUrl;
				}
				Path targetFile = getSkinsDir().resolve(safeUsername + ".png");
				Files.write(targetFile, skinBytes);
				selectSkin(targetFile);

				// Auto-trigger server plugin command if server supports it
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
						// Extract basename if URL ends with .png
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

				// Auto-trigger server plugin command if server supports it
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

		// NO /SKIN IN THE WORLDS: Never issue /skin command in singleplayer worlds
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
				.orElse("unknown");
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

	private static boolean isPrimeAuthSkin(@Nullable PlayerSkin skin) {
		return skin != null && skin.body() != null && skin.body().texturePath().getNamespace().equals(PrimeAuthLib.MOD_ID);
	}

	@Nullable
	public static PlayerSkin getCustomSkin(@Nullable GameProfile profile, @Nullable PlayerSkin originalSkin) {
		if (originalSkin != null && isPrimeAuthSkin(originalSkin)) {
			return originalSkin;
		}

		if (profile == null) {
			return originalSkin;
		}

		UUID id = profile.id();
		if (id != null) {
			if (NEGATIVE_UUID_CACHE.contains(id)) {
				return originalSkin;
			}
			PlayerSkin cached = UUID_SKIN_CACHE.get(id);
			if (cached != null) {
				return combineAccessories(cached, originalSkin);
			}
		}

		if (FILE_NAME_INDEX.isEmpty()) {
			checkDirectoryUpdateIfNeeded();
			if (FILE_NAME_INDEX.isEmpty()) {
				if (id != null) NEGATIVE_UUID_CACHE.add(id);
				return originalSkin;
			}
		}

		Path skinFile = findSkinFileForProfile(profile, originalSkin);
		if (skinFile == null) {
			if (id != null) {
				NEGATIVE_UUID_CACHE.add(id);
			}
			return originalSkin;
		}

		PlayerSkin customSkin = applySkinFromFile(skinFile, originalSkin);
		if (customSkin != null && customSkin != originalSkin) {
			if (id != null) {
				UUID_SKIN_CACHE.put(id, customSkin);
			}
			return customSkin;
		} else {
			if (id != null) {
				NEGATIVE_UUID_CACHE.add(id);
			}
			return originalSkin;
		}
	}

	@Nullable
	public static PlayerSkin getCustomSkinByUuid(@Nullable UUID uuid, @Nullable PlayerSkin originalSkin) {
		if (originalSkin != null && isPrimeAuthSkin(originalSkin)) {
			return originalSkin;
		}

		if (uuid == null) {
			return originalSkin;
		}

		if (NEGATIVE_UUID_CACHE.contains(uuid)) {
			return originalSkin;
		}

		PlayerSkin cached = UUID_SKIN_CACHE.get(uuid);
		if (cached != null) {
			return combineAccessories(cached, originalSkin);
		}

		if (FILE_NAME_INDEX.isEmpty()) {
			checkDirectoryUpdateIfNeeded();
			if (FILE_NAME_INDEX.isEmpty()) {
				NEGATIVE_UUID_CACHE.add(uuid);
				return originalSkin;
			}
		}

		Path skinFile = findSkinFileForUuid(uuid, originalSkin);
		if (skinFile == null) {
			NEGATIVE_UUID_CACHE.add(uuid);
			return originalSkin;
		}

		PlayerSkin customSkin = applySkinFromFile(skinFile, originalSkin);
		if (customSkin != null && customSkin != originalSkin) {
			UUID_SKIN_CACHE.put(uuid, customSkin);
			return customSkin;
		} else {
			NEGATIVE_UUID_CACHE.add(uuid);
			return originalSkin;
		}
	}

	@Nullable
	private static PlayerSkin applySkinFromFile(@Nullable Path skinFile, @Nullable PlayerSkin originalSkin) {
		if (skinFile == null) {
			return originalSkin;
		}

		try {
			String cacheKey = skinFile.toAbsolutePath().toString();
			CachedSkin cached = CACHED_SKINS.get(cacheKey);

			if (cached != null) {
				return createPlayerSkin(cached, originalSkin);
			}

			long lastModified = Files.exists(skinFile) ? Files.getLastModifiedTime(skinFile).toMillis() : 0;
			cached = loadAndRegisterSkin(skinFile, lastModified);
			if (cached != null) {
				CACHED_SKINS.put(cacheKey, cached);
				return createPlayerSkin(cached, originalSkin);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to load skin from {}", skinFile, e);
		}

		return originalSkin;
	}

	private static PlayerSkin combineAccessories(PlayerSkin cached, @Nullable PlayerSkin originalSkin) {
		if (originalSkin == null || (originalSkin.cape() == null && originalSkin.elytra() == null)) {
			return cached;
		}
		return new PlayerSkin(
			cached.body(),
			originalSkin.cape(),
			originalSkin.elytra(),
			cached.model(),
			true
		);
	}

	private static PlayerSkin createPlayerSkin(CachedSkin cached, @Nullable PlayerSkin originalSkin) {
		PlayerModelType modelToUse = switch (currentModelMode) {
			case SLIM -> PlayerModelType.SLIM;
			case WIDE -> PlayerModelType.WIDE;
			case AUTO -> cached.model();
		};

		boolean hasCape = originalSkin != null && originalSkin.cape() != null;
		boolean hasElytra = originalSkin != null && originalSkin.elytra() != null;

		if (!hasCape && !hasElytra && modelToUse == cached.model()) {
			return cached.baseSkin();
		}

		return new PlayerSkin(
			new ClientAsset.ResourceTexture(cached.assetId(), cached.texturePath()),
			hasCape ? originalSkin.cape() : null,
			hasElytra ? originalSkin.elytra() : null,
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
			Path selected = resolveFileIndexed(selectedSkinFileName);
			if (selected != null) {
				return selected;
			}
		}

		return null;
	}

	public static void invalidateSkinCaches() {
		UUID_SKIN_CACHE.clear();
		NEGATIVE_UUID_CACHE.clear();
	}

	public static void reloadAll() {
		CACHED_SKINS.clear();
		LAST_CHECK_TIMES.clear();
		invalidateSkinCaches();
		refreshFileIndex();
		LOGGER.info("Cleared custom skin cache; re-indexed skins from {}", SKINS_DIR_NAME);
	}

	@Nullable
	public static Map<com.mojang.authlib.minecraft.MinecraftProfileTexture.Type, com.mojang.authlib.minecraft.MinecraftProfileTexture> getInjectedTextures(GameProfile profile) {
		Path skinFile = findSkinFileForProfile(profile);
		if (skinFile == null || !Files.exists(skinFile)) {
			return null;
		}

		try {
			String url = skinFile.toUri().toURL().toString();
			PlayerModelType model = getModelMode() == ModelMode.SLIM ? PlayerModelType.SLIM : PlayerModelType.WIDE;
			Map<String, String> metadata = new HashMap<>();
			if (model == PlayerModelType.SLIM) {
				metadata.put("model", "slim");
			}

			com.mojang.authlib.minecraft.MinecraftProfileTexture skinTexture =
				new com.mojang.authlib.minecraft.MinecraftProfileTexture(url, metadata);

			Map<com.mojang.authlib.minecraft.MinecraftProfileTexture.Type, com.mojang.authlib.minecraft.MinecraftProfileTexture> map = new HashMap<>();
			map.put(com.mojang.authlib.minecraft.MinecraftProfileTexture.Type.SKIN, skinTexture);
			return map;
		} catch (Exception e) {
			LOGGER.warn("Failed to build injected textures map for {}", profile.name(), e);
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
			root.addProperty("profileId", original.id().toString().replace("-", ""));
			root.addProperty("profileName", original.name());

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

			com.google.common.collect.Multimap<String, com.mojang.authlib.properties.Property> backingMap = com.google.common.collect.LinkedHashMultimap.create();
			if (original.properties() != null) {
				backingMap.putAll(original.properties());
			}
			backingMap.removeAll("textures");
			backingMap.put("textures", new com.mojang.authlib.properties.Property("textures", base64));

			com.mojang.authlib.properties.PropertyMap propMap = new com.mojang.authlib.properties.PropertyMap(backingMap);

			return new GameProfile(original.id(), original.name(), propMap);
		} catch (Exception e) {
			LOGGER.warn("Failed to create injected GameProfile for {}", original.name(), e);
			return null;
		}
	}

	public static PlayerModelType getModelForSkinFile(@Nullable Path skinFile) {
		if (currentModelMode == ModelMode.SLIM) {
			return PlayerModelType.SLIM;
		}
		if (currentModelMode == ModelMode.WIDE) {
			return PlayerModelType.WIDE;
		}
		if (skinFile != null && Files.exists(skinFile)) {
			CachedSkin cached = CACHED_SKINS.get(skinFile.toAbsolutePath().toString());
			if (cached != null) {
				return cached.model();
			}
			try (InputStream is = Files.newInputStream(skinFile)) {
				NativeImage img = NativeImage.read(is);
				PlayerModelType model = detectModel(img);
				img.close();
				return model;
			} catch (Exception ignored) {
			}
		}
		return PlayerModelType.WIDE;
	}

	@Nullable
	public static Path findSkinFileForProfile(GameProfile profile) {
		return findSkinFileForProfile(profile, null);
	}

	@Nullable
	public static Path findSkinFileForProfile(GameProfile profile, @Nullable PlayerSkin originalSkin) {
		checkDirectoryUpdateIfNeeded();
		if (FILE_NAME_INDEX.isEmpty() || profile == null) {
			return null;
		}

		String name = profile.name();
		UUID id = profile.id();

		// 1. Check exact player name
		if (name != null && !name.isEmpty()) {
			Path byName = resolveFileIndexed(name + ".png");
			if (byName != null) {
				return byName;
			}
		}

		// 2. Check exact player UUID
		if (id != null) {
			Path byUuid = resolveUuidIndexed(id);
			if (byUuid != null) {
				return byUuid;
			}
		}

		// 3. Check offline UUID (for cracked accounts)
		if (name != null && !name.isEmpty()) {
			UUID offlineUuid = getOfflinePlayerUuid(name);
			Path byOfflineUuid = resolveUuidIndexed(offlineUuid);
			if (byOfflineUuid != null) {
				return byOfflineUuid;
			}
		}

		// 4. If it's the local client player:
		if (isLocalPlayer(name, id)) {
			// Check user-selected skin from carousel first
			if (selectedSkinFileName != null && !selectedSkinFileName.isEmpty()) {
				Path selected = resolveFileIndexed(selectedSkinFileName);
				if (selected != null) {
					return selected;
				}
			}

			// Then check default skin.png
			Path skinPng = resolveFileIndexed("skin.png");
			if (skinPng != null) {
				return skinPng;
			}

			// If only one skin file exists in the directory, use it
			if (FILE_NAME_INDEX.size() == 1) {
				return FILE_NAME_INDEX.values().iterator().next();
			}
		}

		// 5. Fallback to default.png ONLY if player has no original skin (cracked/offline)
		if (originalSkin == null) {
			Path defaultPng = resolveFileIndexed("default.png");
			if (defaultPng != null) {
				return defaultPng;
			}
		}

		return null;
	}

	@Nullable
	public static Path findSkinFileForUuid(UUID uuid) {
		return findSkinFileForUuid(uuid, null);
	}

	@Nullable
	public static Path findSkinFileForUuid(UUID uuid, @Nullable PlayerSkin originalSkin) {
		checkDirectoryUpdateIfNeeded();
		if (FILE_NAME_INDEX.isEmpty() || uuid == null) {
			return null;
		}

		Path byUuid = resolveUuidIndexed(uuid);
		if (byUuid != null) {
			return byUuid;
		}

		if (isLocalPlayer(null, uuid)) {
			if (selectedSkinFileName != null && !selectedSkinFileName.isEmpty()) {
				Path selected = resolveFileIndexed(selectedSkinFileName);
				if (selected != null) {
					return selected;
				}
			}

			Path skinPng = resolveFileIndexed("skin.png");
			if (skinPng != null) {
				return skinPng;
			}

			if (FILE_NAME_INDEX.size() == 1) {
				return FILE_NAME_INDEX.values().iterator().next();
			}
		}

		if (originalSkin == null) {
			Path defaultPng = resolveFileIndexed("default.png");
			if (defaultPng != null) {
				return defaultPng;
			}
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

			if (localUsername != null && !localUsername.equals(cachedLocalName)) {
				cachedLocalName = localUsername;
				cachedLocalUuid = localUserUuid;
				cachedLocalOfflineUuid = getOfflinePlayerUuid(localUsername);
			}

			if (name != null && localUsername != null && name.equalsIgnoreCase(localUsername)) {
				return true;
			}
			if (id != null && localUserUuid != null && id.equals(localUserUuid)) {
				return true;
			}
			if (id != null && cachedLocalOfflineUuid != null && id.equals(cachedLocalOfflineUuid)) {
				return true;
			}
		}

		if (client.player != null) {
			if (id != null && id.equals(client.player.getUUID())) {
				return true;
			}
			if (name != null && client.player.getGameProfile() != null && name.equalsIgnoreCase(client.player.getGameProfile().name())) {
				return true;
			}
		}

		return false;
	}

	@Nullable
	private static Path resolveUuidIndexed(UUID uuid) {
		if (uuid == null) return null;
		String uuidStr = uuid.toString();
		Path byUuid = resolveFileIndexed(uuidStr + ".png");
		if (byUuid != null) {
			return byUuid;
		}

		String compactUuid = uuidStr.replace("-", "");
		return resolveFileIndexed(compactUuid + ".png");
	}

	@Nullable
	private static Path resolveFileIndexed(String fileName) {
		if (fileName == null || fileName.isEmpty()) {
			return null;
		}
		return FILE_NAME_INDEX.get(fileName.toLowerCase(Locale.ROOT));
	}

	@Nullable
	public static Path resolveFileCaseInsensitive(Path dir, String fileName) {
		return resolveFileIndexed(fileName);
	}

	@Nullable
	private static CachedSkin loadAndRegisterSkin(Path skinFile, long lastModified) {
		try (InputStream is = Files.newInputStream(skinFile)) {
			NativeImage original = NativeImage.read(is);
			NativeImage processed = ensureStandardDimensions(original);

			PlayerModelType detectedModel = detectModel(processed);

			DynamicTexture dynamicTexture = new DynamicTexture(() -> "primeauthlib_skin", processed);
			dynamicTexture.upload();

			String fileName = skinFile.getFileName().toString();
			if (fileName.toLowerCase(Locale.ROOT).endsWith(".png")) {
				fileName = fileName.substring(0, fileName.length() - 4);
			}
			String sanitizedName = fileName.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase(Locale.ROOT);

			Identifier assetId = Identifier.fromNamespaceAndPath(
				PrimeAuthLib.MOD_ID,
				"skins/" + sanitizedName + "_" + lastModified
			);

			Identifier texturePath = Identifier.fromNamespaceAndPath(
				PrimeAuthLib.MOD_ID,
				"textures/skins/" + sanitizedName + "_" + lastModified + ".png"
			);

			PlayerSkin baseSkin = new PlayerSkin(
				new ClientAsset.ResourceTexture(assetId, texturePath),
				null,
				null,
				detectedModel,
				true
			);

			Minecraft.getInstance().getTextureManager().register(texturePath, dynamicTexture);
			Minecraft.getInstance().getTextureManager().register(assetId, dynamicTexture);

			LOGGER.info("Loaded custom skin from {} (Detected Model: {})", skinFile.getFileName(), detectedModel);
			return new CachedSkin(skinFile, lastModified, assetId, texturePath, dynamicTexture, detectedModel, baseSkin);
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
					expanded.setPixel(x, y, image.getPixel(x, y));
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
				int pixel = src.getPixel(srcX + readX, srcY + y);
				dst.setPixel(dstX + x, dstY + y, pixel);
			}
		}
	}

	public static PlayerModelType detectModel(NativeImage image) {
		int width = image.getWidth();
		int height = image.getHeight();

		if (width != 64 || height != 64) {
			if (width == height && width % 64 == 0) {
				int scale = width / 64;
				if (hasTransparency(image, 50 * scale, 16 * scale, 2 * scale, 4 * scale) ||
					hasTransparency(image, 54 * scale, 20 * scale, 2 * scale, 12 * scale) ||
					hasTransparency(image, 42 * scale, 48 * scale, 2 * scale, 4 * scale) ||
					hasTransparency(image, 46 * scale, 52 * scale, 2 * scale, 12 * scale)) {
					return PlayerModelType.SLIM;
				}
			}
			return PlayerModelType.WIDE;
		}

		// Standard 64x64 check:
		if (hasTransparency(image, 50, 16, 2, 4) ||
			hasTransparency(image, 54, 20, 2, 12) ||
			hasTransparency(image, 42, 48, 2, 4) ||
			hasTransparency(image, 46, 52, 2, 12)) {
			return PlayerModelType.SLIM;
		}

		if ((isSolidColor(image, 54, 20, 2, 12, 0xFF000000) && isSolidColor(image, 46, 52, 2, 12, 0xFF000000)) ||
			(isSolidColor(image, 54, 20, 2, 12, 0xFFFFFFFF) && isSolidColor(image, 46, 52, 2, 12, 0xFFFFFFFF))) {
			return PlayerModelType.SLIM;
		}

		return PlayerModelType.WIDE;
	}

	private static boolean hasTransparency(NativeImage image, int startX, int startY, int w, int h) {
		for (int x = startX; x < startX + w; x++) {
			for (int y = startY; y < startY + h; y++) {
				int pixel = image.getPixel(x, y);
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
				if (image.getPixel(x, y) != color) {
					return false;
				}
			}
		}
		return true;
	}
}
