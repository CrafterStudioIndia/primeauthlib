package com.primeauthlib.client.mixin;

import com.mojang.authlib.GameProfile;
import com.primeauthlib.client.skin.CustomSkinManager;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PlayerInfo.class, priority = 1500)
public abstract class PlayerInfoMixin {
	@Shadow
	public abstract GameProfile getProfile();

	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void primeauthlib$overridePlayerSkin(CallbackInfoReturnable<PlayerSkin> cir) {
		PlayerSkin customSkin = CustomSkinManager.getCustomSkin(this.getProfile(), cir.getReturnValue());
		if (customSkin != null) {
			cir.setReturnValue(customSkin);
		}
	}
}
