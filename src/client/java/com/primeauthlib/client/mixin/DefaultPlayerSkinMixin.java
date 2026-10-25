package com.primeauthlib.client.mixin;

import com.mojang.authlib.GameProfile;
import com.primeauthlib.client.skin.CustomSkinManager;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(DefaultPlayerSkin.class)
public class DefaultPlayerSkinMixin {
	@Inject(method = "get(Ljava/util/UUID;)Lnet/minecraft/client/resources/PlayerSkin;", at = @At("RETURN"), cancellable = true)
	private static void primeauthlib$onGetDefaultSkinByUuid(UUID uuid, CallbackInfoReturnable<PlayerSkin> cir) {
		PlayerSkin customSkin = CustomSkinManager.getCustomSkinByUuid(uuid, cir.getReturnValue());
		if (customSkin != null) {
			cir.setReturnValue(customSkin);
		}
	}

	@Inject(method = "get(Lcom/mojang/authlib/GameProfile;)Lnet/minecraft/client/resources/PlayerSkin;", at = @At("RETURN"), cancellable = true)
	private static void primeauthlib$onGetDefaultSkinByProfile(GameProfile profile, CallbackInfoReturnable<PlayerSkin> cir) {
		PlayerSkin customSkin = CustomSkinManager.getCustomSkin(profile, cir.getReturnValue());
		if (customSkin != null) {
			cir.setReturnValue(customSkin);
		}
	}
}

