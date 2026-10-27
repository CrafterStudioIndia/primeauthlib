package com.primeauthlib.client.authlib;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.SignatureState;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.primeauthlib.client.security.SecurityUtils;
import com.primeauthlib.client.skin.CustomSkinManager;
import net.minecraft.client.resources.PlayerSkin;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-game Authlib Injector implementation for Minecraft 1.21.1.
 * Intercepts com.mojang.authlib Yggdrasil sessions directly in-game,
 * supporting both local auth-libskins injection and remote Authlib-Injector API servers
 * (such as LittleSkin, Ely.by, Blessing Skin, etc.) without requiring an external -javaagent.
 */
public class AuthlibInjector {
	private static final Logger LOGGER = LoggerFactory.getLogger("primeauthlib-injector");
	private static final Gson GSON = new Gson();

	private static String authlibServerUrl = "";

	public static String getAuthlibServerUrl() {
		return authlibServerUrl;
	}

	public static void setAuthlibServerUrl(String url) {
		authlibServerUrl = url != null ? url.trim() : "";
	}

	public static boolean hasServerUrl() {
		return authlibServerUrl != null && !authlibServerUrl.isBlank();
	}

	/**
	 * Injects a valid base64-encoded Yggdrasil 'textures' Property into Authlib.
	 */
	@Nullable
	public static Property getInjectedPackedTextures(@Nullable GameProfile profile) {
		if (profile == null) {
			return null;
		}

		Path skinFile = CustomSkinManager.findSkinFileForProfile(profile);
		if (skinFile == null || !Files.exists(skinFile)) {
			return null;
		}

		try {
			PlayerSkin.Model model = CustomSkinManager.getModelForSkinFile(skinFile);
			String modelName = model == PlayerSkin.Model.SLIM ? "slim" : "default";

			JsonObject skinObj = new JsonObject();
			skinObj.addProperty("url", skinFile.toUri().toString());
			JsonObject metaObj = new JsonObject();
			metaObj.addProperty("model", modelName);
			skinObj.add("metadata", metaObj);

			JsonObject texturesObj = new JsonObject();
			texturesObj.add("SKIN", skinObj);

			JsonObject root = new JsonObject();
			root.addProperty("timestamp", System.currentTimeMillis());
			root.addProperty("profileId", profile.getId() != null ? profile.getId().toString() : UUID.randomUUID().toString());
			root.addProperty("profileName", profile.getName() != null ? profile.getName() : "Player");
			root.addProperty("signatureRequired", false);
			root.add("textures", texturesObj);

			String json = GSON.toJson(root);
			String base64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

			return new Property("textures", base64);
		} catch (Exception e) {
			LOGGER.error("Failed to pack textures for profile {}", profile.getName(), e);
			return null;
		}
	}

	/**
	 * Injects a local skin into a GameProfile's properties.
	 */
	public static boolean injectProfileSkin(GameProfile profile) {
		if (profile == null) {
			return false;
		}

		Path skinFile = CustomSkinManager.findSkinFileForProfile(profile);
		if (skinFile == null || !Files.exists(skinFile)) {
			return false;
		}

		try {
			Property texturesProp = getInjectedPackedTextures(profile);
			if (texturesProp != null) {
				profile.getProperties().removeAll("textures");
				profile.getProperties().put("textures", texturesProp);
				return true;
			}
		} catch (Exception e) {
			LOGGER.error("Failed to inject textures for profile {}", profile.getName(), e);
		}

		return false;
	}

	/**
	 * Unpacks textures from a base64 'textures' Property.
	 */
	@Nullable
	public static MinecraftProfileTextures unpackInjectedTextures(Property property) {
		if (property == null || !"textures".equals(property.name()) || property.value() == null || property.value().isEmpty()) {
			return null;
		}

		try {
			String decodedJson = new String(Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8);
			JsonObject obj = GSON.fromJson(decodedJson, JsonObject.class);
			if (obj != null && obj.has("textures")) {
				JsonObject textures = obj.getAsJsonObject("textures");

				MinecraftProfileTexture skin = null;
				MinecraftProfileTexture cape = null;
				MinecraftProfileTexture elytra = null;

				if (textures.has("SKIN")) {
					JsonObject skinObj = textures.getAsJsonObject("SKIN");
					String url = skinObj.get("url").getAsString();
					Map<String, String> metadata = new HashMap<>();
					if (skinObj.has("metadata")) {
						JsonObject meta = skinObj.getAsJsonObject("metadata");
						meta.keySet().forEach(k -> metadata.put(k, meta.get(k).getAsString()));
					}
					skin = new MinecraftProfileTexture(url, metadata);
				}

				if (textures.has("CAPE")) {
					JsonObject capeObj = textures.getAsJsonObject("CAPE");
					String url = capeObj.get("url").getAsString();
					cape = new MinecraftProfileTexture(url, null);
				}

				if (textures.has("ELYTRA")) {
					JsonObject elytraObj = textures.getAsJsonObject("ELYTRA");
					String url = elytraObj.get("url").getAsString();
					elytra = new MinecraftProfileTexture(url, null);
				}

				return new MinecraftProfileTextures(skin, cape, elytra, SignatureState.SIGNED);
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to unpack custom textures: {}", e.getMessage());
		}

		return null;
	}

	/**
	 * Fetches profile from remote Authlib-Injector API server if configured.
	 */
	@Nullable
	public static ProfileResult fetchInjectedProfile(UUID uuid) {
		if (!hasServerUrl()) {
			return null;
		}

		try {
			String baseUrl = SecurityUtils.validateAndSanitizeUrl(authlibServerUrl);
			String endpoint = baseUrl.replaceAll("/+$", "") + "/sessionserver/session/minecraft/profile/" + uuid.toString().replace("-", "") + "?unsigned=false";
			HttpURLConnection conn = SecurityUtils.openEncryptedConnection(endpoint, 5000, 5000);
			conn.setRequestMethod("GET");

			if (conn.getResponseCode() == 200) {
				try (InputStream is = conn.getInputStream(); InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
					JsonObject obj = GSON.fromJson(reader, JsonObject.class);
					if (obj != null && obj.has("id") && obj.has("name")) {
						String idStr = obj.get("id").getAsString();
						UUID profileId = UUID.fromString(idStr.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
						String name = obj.get("name").getAsString();

						GameProfile gp = new GameProfile(profileId, name);
						if (obj.has("properties")) {
							obj.getAsJsonArray("properties").forEach(el -> {
								JsonObject p = el.getAsJsonObject();
								String pName = p.get("name").getAsString();
								String pVal = p.get("value").getAsString();
								String pSig = p.has("signature") ? p.get("signature").getAsString() : null;
								gp.getProperties().put(pName, pSig != null ? new Property(pName, pVal, pSig) : new Property(pName, pVal));
							});
						}

						LOGGER.info("Successfully fetched profile {} from Authlib-Injector server (TLS)", name);
						return new ProfileResult(gp);
					}
				}
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to fetch profile from Authlib server: {}", e.getMessage());
		}

		return null;
	}

	@Nullable
	public static ProfileResult hasJoinedServer(String profileName, String serverId, @Nullable InetAddress address) {
		if (!hasServerUrl()) {
			return null;
		}

		try {
			String baseUrl = SecurityUtils.validateAndSanitizeUrl(authlibServerUrl);
			String safeProfileName = java.net.URLEncoder.encode(profileName, StandardCharsets.UTF_8);
			String safeServerId = java.net.URLEncoder.encode(serverId, StandardCharsets.UTF_8);
			String endpoint = baseUrl.replaceAll("/+$", "") + "/sessionserver/session/minecraft/hasJoined?username=" + safeProfileName + "&serverId=" + safeServerId;
			if (address != null) {
				endpoint += "&ip=" + java.net.URLEncoder.encode(address.getHostAddress(), StandardCharsets.UTF_8);
			}

			HttpURLConnection conn = SecurityUtils.openEncryptedConnection(endpoint, 5000, 5000);
			conn.setRequestMethod("GET");

			if (conn.getResponseCode() == 200) {
				try (InputStream is = conn.getInputStream(); InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
					JsonObject obj = GSON.fromJson(reader, JsonObject.class);
					if (obj != null && obj.has("id") && obj.has("name")) {
						String idStr = obj.get("id").getAsString();
						UUID profileId = UUID.fromString(idStr.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
						String name = obj.get("name").getAsString();

						GameProfile gp = new GameProfile(profileId, name);
						if (obj.has("properties")) {
							obj.getAsJsonArray("properties").forEach(el -> {
								JsonObject p = el.getAsJsonObject();
								String pName = p.get("name").getAsString();
								String pVal = p.get("value").getAsString();
								String pSig = p.has("signature") ? p.get("signature").getAsString() : null;
								gp.getProperties().put(pName, pSig != null ? new Property(pName, pVal, pSig) : new Property(pName, pVal));
							});
						}

						LOGGER.info("Successfully validated hasJoined for {} from Authlib-Injector server (TLS)", name);
						return new ProfileResult(gp);
					}
				}
			}
		} catch (Exception e) {
			LOGGER.warn("Failed hasJoined verification against Authlib server: {}", e.getMessage());
		}

		return null;
	}
}
