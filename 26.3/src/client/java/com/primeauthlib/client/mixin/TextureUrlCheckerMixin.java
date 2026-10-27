package com.primeauthlib.client.mixin;

import com.primeauthlib.client.security.SecurityUtils;
import com.primeauthlib.client.skin.CustomSkinManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into com.mojang.authlib.services.MinecraftServicesDiscoveryService (Authlib v10+ in Minecraft 26.3).
 * Allows custom texture domains, local file:// schemes, and custom Authlib-Injector
 * API servers without triggering texture rejection, while enforcing strict OpSec
 * HTTPS validation and blocking dangerous schemes.
 */
@Mixin(targets = "com.mojang.authlib.services.MinecraftServicesDiscoveryService", remap = false, priority = 1500)
public class TextureUrlCheckerMixin {
	@Inject(method = "isAllowedTextureDomain", at = @At("HEAD"), cancellable = true)
	private void primeauthlib$onIsAllowedTextureDomain(String url, CallbackInfoReturnable<Boolean> cir) {
		if (SecurityUtils.isAllowedTextureUrl(url, CustomSkinManager.getSkinsDir())) {
			cir.setReturnValue(true);
		}
	}
}
