package com.primeauthlib.client.skin;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.primeauthlib.PrimeAuthLib;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Manages loading, auto-detecting model (Slim vs Wide), caching, and hot-reloading
 * player skins placed in the instance's 'auth-libskins' directory.
 * Fully supports online accounts, offline (cracked) accounts, and offline servers.
 */
public class CustomSkinManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("primeauthlib-skins");
	public static final String SKINS_DIR_NAME = "auth-libskins";

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
	private static final Map<String, CachedSkin> CACHED_SKINS = new ConcurrentHashMap<>();
	private static final Map<String, Long> LAST_CHECK_TIMES = new ConcurrentHashMap<>();
	private static final long CHECK_INTERVAL_MS = 1000; // Check disk timestamp at most once per second per file

	private record CachedSkin(
		Path path,
		long lastModified,
		Identifier textureId,
		DynamicTexture texture,
		PlayerSkin.Model model
	) {}

	public static void init() {
		skinsDir = FabricLoader.getInstance().getGameDir().resolve(SKINS_DIR_NAME);
		try {
			if (!Files.exists(skinsDir)) {
				Files.createDirectories(skinsDir);
				LOGGER.info("Created skins directory: {}", skinsDir.toAbsolutePath());
			}

			loadConfig();

			Path readme = skinsDir.resolve("README.txt");
			if (!Files.exists(readme)) {
				String readmeContent = """
					=== PrimeAuthLib - auth-libskins ===
					
					Place your custom skin PNG file(s) here!
					Works seamlessly for BOTH online and offline (cracked) accounts and servers.
					
					Supported File Names:
					1. skin.png          -> Applies to YOUR local player everywhere (singleplayer, online & offline servers).
					2. <username>.png    -> Applies to a specific player name (e.g. Crafter.png, Steve.png). Case-insensitive.
					3. <uuid>.png        -> Applies to a specific player UUID (online or offline UUID, with or without dashes).
					4. default.png       -> Fallback skin for players without a custom skin.
					
					Note for Offline Accounts:
					- You can simply drop 'skin.png' or '<your_offline_nickname>.png' here!
					- If you drop only a single .png skin in this folder, it automatically applies to your player!
					- Offline UUIDs (nameUUIDFromBytes) are automatically calculated and matched.
					
					Features:
					- Model Auto-Detection: Automatically detects Slim (Alex / 3-pixel arms) vs Wide (Steve / 4-pixel arms).
					- In-Game GUI: Access via Options -> "Auth-Lib Skin..." or Skin Customization -> "Auth-Lib Skin...".
					- Hot-Reloading: Replacing or saving the PNG file updates in-game immediately without restarting!
					- Completely bypasses server-side skin limits, offline mode limits, and third-party skin APIs.
					""";
				Files.writeString(readme, readmeContent);
			}
		} catch (IOException e) {
			LOGGER.error("Failed to initialize {} directory", SKINS_DIR_NAME, e);
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
			} catch (IOException e) {
				LOGGER.warn("Failed to load config.properties", e);
			}
		}
	}

	private static void saveConfig() {
		Path configFile = getSkinsDir().resolve("config.properties");
		try (FileOutputStream os = new FileOutputStream(configFile.toFile())) {
			Properties props = new Properties();
			props.setProperty("model_mode", currentModelMode.name());
			props.store(os, "PrimeAuthLib Configuration");
		} catch (IOException e) {
			LOGGER.warn("Failed to save config.properties", e);
		}
	}

	/**
	 * Computes the deterministic offline-mode UUID for a player nickname,
	 * matching standard vanilla Minecraft offline server logic.
	 */
	public static UUID getOfflinePlayerUuid(String username) {
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Retrieves or loads custom skin for a given GameProfile.
	 */
	@Nullable
	public static PlayerSkin getCustomSkin(@Nullable GameProfile profile, @Nullable PlayerSkin originalSkin) {
		// Prevent redundant wrapping if already using our custom skin
		if (originalSkin != null && originalSkin.texture() != null && originalSkin.texture().getNamespace().equals(PrimeAuthLib.MOD_ID)) {
			return originalSkin;
		}

		if (profile == null) {
			return originalSkin;
		}

		Path skinFile = findSkinFileForProfile(profile);
		return applySkinFromFile(skinFile, originalSkin);
	}

	/**
	 * Retrieves or loads custom skin by UUID (used by DefaultPlayerSkin fallbacks).
	 */
	@Nullable
	public static PlayerSkin getCustomSkinByUuid(@Nullable UUID uuid, @Nullable PlayerSkin originalSkin) {
		if (originalSkin != null && originalSkin.texture() != null && originalSkin.texture().getNamespace().equals(PrimeAuthLib.MOD_ID)) {
			return originalSkin;
		}

		if (uuid == null) {
			return originalSkin;
		}

		Path skinFile = findSkinFileForUuid(uuid);
		return applySkinFromFile(skinFile, originalSkin);
	}

	@Nullable
	private static PlayerSkin applySkinFromFile(@Nullable Path skinFile, @Nullable PlayerSkin originalSkin) {
		if (skinFile == null || !Files.exists(skinFile)) {
			return originalSkin;
		}

		try {
			String cacheKey = skinFile.toAbsolutePath().toString();
			long now = System.currentTimeMillis();
			Long lastCheck = LAST_CHECK_TIMES.get(cacheKey);
			CachedSkin cached = CACHED_SKINS.get(cacheKey);

			// Fast cache path: avoid touching the filesystem repeatedly every frame
			if (cached != null && lastCheck != null && (now - lastCheck < CHECK_INTERVAL_MS)) {
				return createPlayerSkin(cached, originalSkin);
			}

			// Check filesystem timestamp only when interval has elapsed or not yet cached
			LAST_CHECK_TIMES.put(cacheKey, now);
			long fileLastModified = Files.getLastModifiedTime(skinFile).toMillis();

			if (cached == null || fileLastModified != cached.lastModified()) {
				// Thread safety: texture operations must run on render thread
				if (RenderSystem.isOnRenderThread()) {
					if (cached != null && fileLastModified != cached.lastModified()) {
						Minecraft.getInstance().getTextureManager().release(cached.textureId());
						cached.texture().close();
					}
					cached = loadAndRegisterSkin(skinFile, fileLastModified);
					if (cached != null) {
						CACHED_SKINS.put(cacheKey, cached);
					}
				} else {
					// Off render thread: schedule upload on render thread and return previous or fallback
					final CachedSkin oldCached = cached;
					Minecraft.getInstance().execute(() -> {
						if (oldCached != null && fileLastModified != oldCached.lastModified()) {
							Minecraft.getInstance().getTextureManager().release(oldCached.textureId());
							oldCached.texture().close();
						}
						CachedSkin newCached = loadAndRegisterSkin(skinFile, fileLastModified);
						if (newCached != null) {
							CACHED_SKINS.put(cacheKey, newCached);
						}
					});
				}
			}

			if (cached != null) {
				return createPlayerSkin(cached, originalSkin);
			}
		} catch (Exception e) {
			LOGGER.error("Error loading custom skin from {}", skinFile, e);
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
			cached.textureId(),
			null,
			originalSkin != null ? originalSkin.capeTexture() : null,
			originalSkin != null ? originalSkin.elytraTexture() : null,
			modelToUse,
			true // secure: true to bypass any client security warnings
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

		return findSkinFileForProfile(profile);
	}

	@Nullable
	private static Path findSkinFileForProfile(GameProfile profile) {
		Path dir = getSkinsDir();
		if (!Files.isDirectory(dir)) {
			return null;
		}

		String name = profile.getName();
		UUID id = profile.getId();
		boolean isLocalUser = isLocalPlayer(name, id);

		// 1. Check exact or case-insensitive username match: <username>.png
		if (name != null && !name.isBlank()) {
			Path byName = resolveFileCaseInsensitive(dir, name + ".png");
			if (byName != null) {
				return byName;
			}

			// Also check offline UUID calculated from this username: <offline_uuid>.png
			UUID offlineUuid = getOfflinePlayerUuid(name);
			Path byOfflineUuid = resolveUuidFile(dir, offlineUuid);
			if (byOfflineUuid != null) {
				return byOfflineUuid;
			}
		}

		// 2. Check profile UUID match: <uuid>.png
		if (id != null) {
			Path byUuid = resolveUuidFile(dir, id);
			if (byUuid != null) {
				return byUuid;
			}
		}

		// 3. If local user (works for both online & offline accounts)
		if (isLocalUser) {
			Path skinPng = resolveFileCaseInsensitive(dir, "skin.png");
			if (skinPng != null) {
				return skinPng;
			}

			// If user dropped exactly ONE .png in auth-libskins, automatically apply it to local user
			Path singlePng = findSingleSkinFile(dir);
			if (singlePng != null) {
				return singlePng;
			}
		}

		// 4. Fallback to default.png
		Path defaultPng = resolveFileCaseInsensitive(dir, "default.png");
		if (defaultPng != null) {
			return defaultPng;
		}

		return null;
	}

	@Nullable
	private static Path findSkinFileForUuid(UUID uuid) {
		Path dir = getSkinsDir();
		if (!Files.isDirectory(dir)) {
			return null;
		}

		// 1. Direct UUID match
		Path byUuid = resolveUuidFile(dir, uuid);
		if (byUuid != null) {
			return byUuid;
		}

		// 2. Check if this UUID belongs to local player
		if (isLocalPlayer(null, uuid)) {
			Path skinPng = resolveFileCaseInsensitive(dir, "skin.png");
			if (skinPng != null) {
				return skinPng;
			}

			Path singlePng = findSingleSkinFile(dir);
			if (singlePng != null) {
				return singlePng;
			}
		}

		// 3. Fallback to default.png
		Path defaultPng = resolveFileCaseInsensitive(dir, "default.png");
		if (defaultPng != null) {
			return defaultPng;
		}

		return null;
	}

	/**
	 * Checks if the given profile/UUID represents the local player playing on this client.
	 * Robustly accounts for offline launchers, offline mode servers, and UUID differences.
	 */
	private static boolean isLocalPlayer(@Nullable String name, @Nullable UUID id) {
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return false;
		}

		// Check User (present on all launchers, both online and offline)
		if (client.getUser() != null) {
			String localUsername = client.getUser().getName();
			UUID localUserUuid = client.getUser().getProfileId();

			// Match by username
			if (name != null && localUsername != null && name.equalsIgnoreCase(localUsername)) {
				return true;
			}

			// Match by direct User UUID
			if (id != null && localUserUuid != null && id.equals(localUserUuid)) {
				return true;
			}

			// Match by offline-computed UUID of local username
			if (id != null && localUsername != null && id.equals(getOfflinePlayerUuid(localUsername))) {
				return true;
			}
		}

		// Check active ClientPlayerEntity in world
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

			String sanitizedName = skinFile.getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase(Locale.ROOT);
			Identifier textureId = Identifier.fromNamespaceAndPath(
				PrimeAuthLib.MOD_ID,
				"skins/" + sanitizedName + "_" + lastModified
			);

			Minecraft.getInstance().getTextureManager().register(textureId, dynamicTexture);

			LOGGER.info("Loaded custom skin from {} (Detected Model: {})", skinFile.getFileName(), detectedModel);
			return new CachedSkin(skinFile, lastModified, textureId, dynamicTexture, detectedModel);
		} catch (Exception e) {
			LOGGER.error("Failed to read skin image from {}", skinFile, e);
			return null;
		}
	}

	private static NativeImage ensureStandardDimensions(NativeImage image) {
		int width = image.getWidth();
		int height = image.getHeight();

		// Standard 64x64 skin
		if (width == 64 && height == 64) {
			return image;
		}

		// Legacy 64x32 skin - expand to 64x64
		if (width == 64 && height == 32) {
			NativeImage expanded = new NativeImage(64, 64, true);
			// Copy top 64x32 half directly
			for (int x = 0; x < 64; x++) {
				for (int y = 0; y < 32; y++) {
					expanded.setPixelRGBA(x, y, image.getPixelRGBA(x, y));
				}
			}

			// Copy right leg to left leg (0-16, 16-32) -> (16-32, 48-64)
			copyArea(image, expanded, 0, 16, 16, 48, 16, 16, true);
			// Copy right arm to left arm (40-56, 16-32) -> (32-48, 48-64)
			copyArea(image, expanded, 40, 16, 32, 48, 16, 16, true);

			image.close();
			return expanded;
		}

		return image;
	}

	private static void copyArea(NativeImage src, NativeImage dst, int srcX, int srcY, int dstX, int dstY, int width, int height, boolean mirror) {
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int readX = mirror ? (width - 1 - x) : x;
				int pixel = src.getPixelRGBA(srcX + readX, srcY + y);
				dst.setPixelRGBA(dstX + x, dstY + y, pixel);
			}
		}
	}

	/**
	 * Automatically detects whether a skin is SLIM (Alex / 3-pixel arms)
	 * or WIDE (Steve / 4-pixel arms) by checking transparency in the unused arm regions.
	 */
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

		// Standard 64x64 check:
		// Right Arm Top/Bottom Gap: x in [50, 52), y in [16, 20)
		// Right Arm Seam/Side Gap:  x in [54, 56), y in [20, 32)
		// Left Arm Top/Bottom Gap:  x in [42, 44), y in [48, 52)
		// Left Arm Seam/Side Gap:   x in [46, 48), y in [52, 64)
		if (hasTransparency(image, 50, 16, 2, 4) ||
			hasTransparency(image, 54, 20, 2, 12) ||
			hasTransparency(image, 42, 48, 2, 4) ||
			hasTransparency(image, 46, 52, 2, 12)) {
			return PlayerSkin.Model.SLIM;
		}

		// Check if unused arm regions are solid black/white filler from certain skin tools
		if ((isSolidColor(image, 54, 20, 2, 12, 0xFF000000) && isSolidColor(image, 46, 52, 2, 12, 0xFF000000)) ||
			(isSolidColor(image, 54, 20, 2, 12, 0xFFFFFFFF) && isSolidColor(image, 46, 52, 2, 12, 0xFFFFFFFF))) {
			return PlayerSkin.Model.SLIM;
		}

		return PlayerSkin.Model.WIDE;
	}

	private static boolean hasTransparency(NativeImage image, int x0, int y0, int w, int h) {
		for (int x = x0; x < x0 + w; x++) {
			for (int y = y0; y < y0 + h; y++) {
				int pixel = image.getPixelRGBA(x, y);
				int alpha = (pixel >> 24) & 0xFF;
				if (alpha < 128) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isSolidColor(NativeImage image, int x0, int y0, int w, int h, int targetColor) {
		for (int x = x0; x < x0 + w; x++) {
			for (int y = y0; y < y0 + h; y++) {
				if (image.getPixelRGBA(x, y) != targetColor) {
					return false;
				}
			}
		}
		return true;
	}

	public static void reloadAll() {
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return;
		}
		client.execute(() -> {
			for (CachedSkin cached : CACHED_SKINS.values()) {
				client.getTextureManager().release(cached.textureId());
				cached.texture().close();
			}
			CACHED_SKINS.clear();
			LAST_CHECK_TIMES.clear();
			LOGGER.info("Reloaded all custom skins from {}", getSkinsDir());
		});
	}
}
