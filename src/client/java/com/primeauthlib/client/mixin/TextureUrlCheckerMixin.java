package com.primeauthlib.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into com.mojang.authlib.yggdrasil.TextureUrlChecker.
 * Allows custom texture domains, local file:// schemes, and custom Authlib-Injector
 * API servers without triggering 'Textures payload url is invalid' errors.
 */
@Mixin(targets = "com.mojang.authlib.yggdrasil.TextureUrlChecker", remap = false)
public class TextureUrlCheckerMixin {
	@Inject(method = "isAllowedTextureDomain", at = @At("HEAD"), cancellable = true)
	private static void primeauthlib$onIsAllowedTextureDomain(String url, CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(true);
	}
}
