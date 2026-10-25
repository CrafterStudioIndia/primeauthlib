package com.primeauthlib.client.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.services.ProfileResult;
import com.primeauthlib.client.authlib.AuthlibInjector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetAddress;
import java.util.UUID;

/**
 * Mixin into com.mojang.authlib.services.MinecraftServicesSessionService (Authlib v10+ in Minecraft 26.3).
 * Intercepts session authentication and texture resolution directly in-game,
 * providing both local offline/online custom skin injection and remote Authlib-Injector support.
 */
@Mixin(targets = "com.mojang.authlib.services.MinecraftServicesSessionService", remap = false, priority = 1500)
public class YggdrasilMinecraftSessionServiceMixin {

	@Inject(method = "getPackedTextures", at = @At("RETURN"), cancellable = true)
	private void primeauthlib$onGetPackedTextures(GameProfile profile, CallbackInfoReturnable<Property> cir) {
		if (cir.getReturnValue() == null || cir.getReturnValue().value().isEmpty()) {
			Property injected = AuthlibInjector.getInjectedPackedTextures(profile);
			if (injected != null) {
				cir.setReturnValue(injected);
			}
		}
	}

	@Inject(method = "unpackTextures", at = @At("RETURN"), cancellable = true)
	private void primeauthlib$onUnpackTextures(Property property, CallbackInfoReturnable<MinecraftProfileTextures> cir) {
		if (cir.getReturnValue() == null || cir.getReturnValue().equals(MinecraftProfileTextures.EMPTY)) {
			MinecraftProfileTextures custom = AuthlibInjector.unpackInjectedTextures(property);
			if (custom != null) {
				cir.setReturnValue(custom);
			}
		}
	}

	@Inject(method = "fetchProfile", at = @At("RETURN"), cancellable = true)
	private void primeauthlib$onFetchProfile(UUID profileId, boolean requireSecure, CallbackInfoReturnable<ProfileResult> cir) {
		if (cir.getReturnValue() == null) {
			ProfileResult result = AuthlibInjector.fetchInjectedProfile(profileId);
			if (result != null) {
				cir.setReturnValue(result);
			}
		}
	}

	@Inject(method = "hasJoinedServer", at = @At("RETURN"), cancellable = true)
	private void primeauthlib$onHasJoinedServer(String profileName, String serverId, InetAddress address, CallbackInfoReturnable<ProfileResult> cir) {
		if (cir.getReturnValue() == null) {
			ProfileResult result = AuthlibInjector.hasJoinedServer(profileName, serverId, address);
			if (result != null) {
				cir.setReturnValue(result);
			}
		}
	}
}
