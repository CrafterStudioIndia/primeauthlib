package com.primeauthlib.client.mixin;

import com.mojang.authlib.GameProfile;
import com.primeauthlib.client.skin.CustomSkinManager;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
	@Shadow
	public abstract GameProfile getGameProfile();

	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void primeauthlib$overrideEntitySkin(CallbackInfoReturnable<PlayerSkin> cir) {
		PlayerSkin customSkin = CustomSkinManager.getCustomSkin(this.getGameProfile(), cir.getReturnValue());
		if (customSkin != null) {
			cir.setReturnValue(customSkin);
		}
	}
}
