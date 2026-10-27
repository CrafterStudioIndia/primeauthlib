package com.primeauthlib.client.mixin;

import com.primeauthlib.client.security.SecurityUtils;
import com.primeauthlib.client.skin.CustomSkinManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into com.mojang.authlib.yggdrasil.TextureUrlChecker.
 * Allows custom texture domains, local file:// schemes, and custom Authlib-Injector
 * API servers without triggering 'Textures payload url is invalid' errors,
 * while strictly enforcing OpSec URL validation, HTTPS transport, and blocking
 * dangerous schemes (javascript:, data:, vbscript:, arbitrary local file traversal).
 */
@Mixin(targets = "com.mojang.authlib.yggdrasil.TextureUrlChecker", remap = false, priority = 1500)
public class TextureUrlCheckerMixin {
	@Inject(method = "isAllowedTextureDomain", at = @At("HEAD"), cancellable = true)
	private static void primeauthlib$onIsAllowedTextureDomain(String url, CallbackInfoReturnable<Boolean> cir) {
		if (SecurityUtils.isAllowedTextureUrl(url, CustomSkinManager.getSkinsDir())) {
			cir.setReturnValue(true);
		}
	}
}
