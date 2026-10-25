package com.primeauthlib.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into com.mojang.authlib.services.MinecraftServicesDiscoveryService (Authlib v10+ in Minecraft 26.3).
 * Allows custom texture domains, local file:// schemes, and custom Authlib-Injector
 * API servers without triggering texture rejection.
 */
@Mixin(targets = "com.mojang.authlib.services.MinecraftServicesDiscoveryService", remap = false, priority = 1500)
public class TextureUrlCheckerMixin {
	@Inject(method = "isAllowedTextureDomain", at = @At("HEAD"), cancellable = true)
	private void primeauthlib$onIsAllowedTextureDomain(String url, CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(true);
	}
}
