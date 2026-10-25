package com.primeauthlib.client.skin;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import com.primeauthlib.PrimeAuthLib;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Manages loading, auto-detecting model, caching, and hot-reloading
 * skins placed in the instance's 'auth-libskins' directory.
 */
public class CustomSkinManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("primeauthlib-skins");
	public static final String SKINS_DIR_NAME = "auth-libskins";

	private static Path skinsDir;
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

			Path readme = skinsDir.resolve("README.txt");
			if (!Files.exists(readme)) {
				String readmeContent = """
					=== PrimeAuthLib - auth-libskins ===
					
					Place your custom skin PNG file here!
					
					Supported File Names:
					1. skin.png          -> Applies to YOUR local player everywhere (singleplayer & any server).
					2. <username>.png    -> Applies to a specific player username (e.g. Crafter.png, Steve.png).
					3. <uuid>.png        -> Applies to a specific player UUID (e.g. 069a79f4-44e9-4726-a5be-fca90e38aaf5.png).
					4. default.png       -> Fallback skin for players without a custom skin.
					
					Features:
					- Model Auto-Detection: Automatically detects Slim (Alex / 3-pixel arms) vs Wide (Steve / 4-pixel arms).
					- Hot-Reloading: Replacing or saving the PNG file updates in-game immediately without restarting!
					- Bypasses any server skin restrictions or offline-mode limitations.
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

	@Nullable
	public static PlayerSkin getCustomSkin(@Nullable GameProfile profile, @Nullable PlayerSkin originalSkin) {
		if (profile == null) {
			return originalSkin;
		}

		Path skinFile = findSkinFileForProfile(profile);
		if (skinFile == null || !Files.exists(skinFile)) {
			return originalSkin;
		}

		try {
			String cacheKey = skinFile.toAbsolutePath().toString();
			long now = System.currentTimeMillis();
			Long lastCheck = LAST_CHECK_TIMES.get(cacheKey);

			CachedSkin cached = CACHED_SKINS.get(cacheKey);
			long fileLastModified = Files.getLastModifiedTime(skinFile).toMillis();

			if (cached == null || (lastCheck == null || (now - lastCheck > CHECK_INTERVAL_MS) && fileLastModified != cached.lastModified())) {
				LAST_CHECK_TIMES.put(cacheKey, now);

				if (cached != null && fileLastModified != cached.lastModified()) {
					// Release previous texture
					final CachedSkin oldCached = cached;
					Minecraft.getInstance().execute(() -> {
						Minecraft.getInstance().getTextureManager().release(oldCached.textureId());
						oldCached.texture().close();
					});
				}

				cached = loadAndRegisterSkin(skinFile, fileLastModified);
				if (cached != null) {
					CACHED_SKINS.put(cacheKey, cached);
				}
			}

			if (cached != null) {
				return new PlayerSkin(
					cached.textureId(),
					null,
					originalSkin != null ? originalSkin.capeTexture() : null,
					originalSkin != null ? originalSkin.elytraTexture() : null,
					cached.model(),
					true
				);
			}
		} catch (Exception e) {
			LOGGER.error("Error loading custom skin from {}", skinFile, e);
		}

		return originalSkin;
	}

	@Nullable
	private static Path findSkinFileForProfile(GameProfile profile) {
		Path dir = getSkinsDir();
		if (!Files.isDirectory(dir)) {
			return null;
		}

		boolean isLocalUser = isLocalPlayer(profile);

		// 1. Check exact or case-insensitive username match: <username>.png
		if (profile.getName() != null && !profile.getName().isBlank()) {
			Path byName = resolveFileCaseInsensitive(dir, profile.getName() + ".png");
			if (byName != null) {
				return byName;
			}
		}

		// 2. Check UUID match: <uuid>.png or <uuid-without-dashes>.png
		if (profile.getId() != null) {
			String uuidStr = profile.getId().toString();
			Path byUuid = resolveFileCaseInsensitive(dir, uuidStr + ".png");
			if (byUuid != null) {
				return byUuid;
			}

			String compactUuid = uuidStr.replace("-", "");
			Path byCompactUuid = resolveFileCaseInsensitive(dir, compactUuid + ".png");
			if (byCompactUuid != null) {
				return byCompactUuid;
			}
		}

		// 3. If local user, check skin.png
		if (isLocalUser) {
			Path skinPng = resolveFileCaseInsensitive(dir, "skin.png");
			if (skinPng != null) {
				return skinPng;
			}
		}

		// 4. Fallback to default.png
		Path defaultPng = resolveFileCaseInsensitive(dir, "default.png");
		if (defaultPng != null) {
			return defaultPng;
		}

		return null;
	}

	private static boolean isLocalPlayer(GameProfile profile) {
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return false;
		}

		if (client.getUser() != null) {
			if (profile.getName() != null && profile.getName().equalsIgnoreCase(client.getUser().getName())) {
				return true;
			}
			if (profile.getId() != null && profile.getId().equals(client.getUser().getProfileId())) {
				return true;
			}
		}

		if (client.player != null && profile.getId() != null && profile.getId().equals(client.player.getUUID())) {
			return true;
		}

		return false;
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
	private static CachedSkin loadAndRegisterSkin(Path skinFile, long lastModified) {
		try (InputStream is = Files.newInputStream(skinFile)) {
			NativeImage original = NativeImage.read(is);
			NativeImage processed = ensureStandardDimensions(original);

			PlayerSkin.Model model = detectModel(processed);

			DynamicTexture dynamicTexture = new DynamicTexture(processed);
			dynamicTexture.upload();

			String sanitizedName = skinFile.getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase(Locale.ROOT);
			Identifier textureId = Identifier.fromNamespaceAndPath(
				PrimeAuthLib.MOD_ID,
				"skins/" + sanitizedName + "_" + lastModified
			);

			Minecraft.getInstance().getTextureManager().register(textureId, dynamicTexture);

			LOGGER.info("Loaded custom skin from {} (Detected Model: {})", skinFile.getFileName(), model);
			return new CachedSkin(skinFile, lastModified, textureId, dynamicTexture, model);
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
	 * Automatically detects whether a 64x64 skin is SLIM (Alex / 3-pixel arms)
	 * or WIDE (Steve / 4-pixel arms) by checking transparency in the unused arm regions.
	 */
	public static PlayerSkin.Model detectModel(NativeImage image) {
		int width = image.getWidth();
		int height = image.getHeight();

		if (width != 64 || height != 64) {
			// Check HD square skins (e.g. 128x128, 256x256)
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

		// Check if unused arm regions are solid black/white filler from certain skin creators
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
