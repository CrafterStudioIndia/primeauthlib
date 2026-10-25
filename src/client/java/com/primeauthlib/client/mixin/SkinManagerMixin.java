package com.primeauthlib.client.mixin;

import com.mojang.authlib.GameProfile;
import com.primeauthlib.client.skin.CustomSkinManager;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.SkinManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SkinManager.class)
public class SkinManagerMixin {
	@Inject(method = "getInsecureSkin(Lcom/mojang/authlib/GameProfile;)Lnet/minecraft/client/resources/PlayerSkin;", at = @At("RETURN"), cancellable = true)
	private void primeauthlib$onGetInsecureSkin(GameProfile profile, CallbackInfoReturnable<PlayerSkin> cir) {
		PlayerSkin customSkin = CustomSkinManager.getCustomSkin(profile, cir.getReturnValue());
		if (customSkin != null) {
			cir.setReturnValue(customSkin);
		}
	}
}
