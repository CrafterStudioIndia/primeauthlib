package com.primeauthlib.client.authlib;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.SignatureState;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.primeauthlib.client.skin.CustomSkinManager;
import net.minecraft.world.entity.player.PlayerModelType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-game Authlib Injector implementation.
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
			PlayerModelType model = CustomSkinManager.getModelForSkinFile(skinFile);
			String modelName = model == PlayerModelType.SLIM ? "slim" : "default";

			JsonObject skinObj = new JsonObject();
			skinObj.addProperty("url", skinFile.toUri().toString());
			JsonObject metaObj = new JsonObject();
			metaObj.addProperty("model", modelName);
			skinObj.add("metadata", metaObj);

			JsonObject texturesObj = new JsonObject();
			texturesObj.add("SKIN", skinObj);

			JsonObject root = new JsonObject();
			root.addProperty("timestamp", System.currentTimeMillis());
			root.addProperty("profileId", profile.id() != null ? profile.id().toString() : UUID.randomUUID().toString());
			root.addProperty("profileName", profile.name() != null ? profile.name() : "Player");
			root.addProperty("signatureRequired", false);
			root.add("textures", texturesObj);

			String json = GSON.toJson(root);
			String base64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

			return new Property("textures", base64);
		} catch (Exception e) {
			LOGGER.error("Failed to pack textures for profile {}", profile.name(), e);
			return null;
		}
	}

	/**
	 * Injects skin textures directly into Authlib's MinecraftProfileTextures record.
	 */
	@Nullable
	public static MinecraftProfileTextures getInjectedTextures(@Nullable GameProfile profile) {
		if (profile == null) {
			return null;
		}

		Path skinFile = CustomSkinManager.findSkinFileForProfile(profile);
		if (skinFile == null || !Files.exists(skinFile)) {
			return null;
		}

		try {
			PlayerModelType model = CustomSkinManager.getModelForSkinFile(skinFile);
			String modelName = model == PlayerModelType.SLIM ? "slim" : "default";
			Map<String, String> metadata = Map.of("model", modelName);
			MinecraftProfileTexture skin = new MinecraftProfileTexture(skinFile.toUri().toString(), metadata);

			return new MinecraftProfileTextures(skin, null, null, SignatureState.SIGNED);
		} catch (Exception e) {
			LOGGER.error("Failed to inject textures for profile {}", profile.name(), e);
			return null;
		}
	}

	/**
	 * Unpacks a 'textures' Property into MinecraftProfileTextures, supporting
	 * local 'file://' URIs and custom server textures without signature errors.
	 */
	@Nullable
	public static MinecraftProfileTextures unpackInjectedTextures(@Nullable Property property) {
		if (property == null || property.value() == null || property.value().isEmpty()) {
			return null;
		}

		try {
			byte[] decoded = Base64.getDecoder().decode(property.value());
			String json = new String(decoded, StandardCharsets.UTF_8);
			JsonObject obj = GSON.fromJson(json, JsonObject.class);
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
			String endpoint = authlibServerUrl.replaceAll("/+$", "") + "/sessionserver/session/minecraft/profile/" + uuid.toString().replace("-", "") + "?unsigned=false";
			URL url = URI.create(endpoint).toURL();
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setConnectTimeout(5000);
			conn.setReadTimeout(5000);

			if (conn.getResponseCode() == 200) {
				try (InputStream is = conn.getInputStream(); InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
					JsonObject obj = GSON.fromJson(reader, JsonObject.class);
					if (obj != null && obj.has("id") && obj.has("name")) {
						String idStr = obj.get("id").getAsString();
						UUID profileId = UUID.fromString(idStr.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
						String name = obj.get("name").getAsString();

						Multimap<String, Property> multimap = LinkedHashMultimap.create();
						if (obj.has("properties")) {
							obj.getAsJsonArray("properties").forEach(el -> {
								JsonObject p = el.getAsJsonObject();
								String pName = p.get("name").getAsString();
								String pVal = p.get("value").getAsString();
								String pSig = p.has("signature") ? p.get("signature").getAsString() : null;
								multimap.put(pName, pSig != null ? new Property(pName, pVal, pSig) : new Property(pName, pVal));
							});
						}
						PropertyMap propMap = new PropertyMap(multimap);

						GameProfile gp = new GameProfile(profileId, name, propMap);
						LOGGER.info("Successfully fetched profile {} from Authlib-Injector server", name);
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
			String endpoint = authlibServerUrl.replaceAll("/+$", "") + "/sessionserver/session/minecraft/hasJoined?username=" + profileName + "&serverId=" + serverId;
			if (address != null) {
				endpoint += "&ip=" + address.getHostAddress();
			}

			URL url = URI.create(endpoint).toURL();
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setConnectTimeout(5000);
			conn.setReadTimeout(5000);

			if (conn.getResponseCode() == 200) {
				try (InputStream is = conn.getInputStream(); InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
					JsonObject obj = GSON.fromJson(reader, JsonObject.class);
					if (obj != null && obj.has("id") && obj.has("name")) {
						String idStr = obj.get("id").getAsString();
						UUID profileId = UUID.fromString(idStr.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
						String name = obj.get("name").getAsString();

						Multimap<String, Property> multimap = LinkedHashMultimap.create();
						if (obj.has("properties")) {
							obj.getAsJsonArray("properties").forEach(el -> {
								JsonObject p = el.getAsJsonObject();
								String pName = p.get("name").getAsString();
								String pVal = p.get("value").getAsString();
								String pSig = p.has("signature") ? p.get("signature").getAsString() : null;
								multimap.put(pName, pSig != null ? new Property(pName, pVal, pSig) : new Property(pName, pVal));
							});
						}
						PropertyMap propMap = new PropertyMap(multimap);

						return new ProfileResult(new GameProfile(profileId, name, propMap));
					}
				}
			}
		} catch (Exception e) {
			LOGGER.warn("hasJoinedServer via Authlib server failed: {}", e.getMessage());
		}

		return null;
	}
}
