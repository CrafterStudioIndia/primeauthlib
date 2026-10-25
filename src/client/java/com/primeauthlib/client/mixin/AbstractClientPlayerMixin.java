package com.primeauthlib.client.mixin;

import com.primeauthlib.client.skin.CustomSkinManager;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AbstractClientPlayer.class, priority = 1500)
public abstract class AbstractClientPlayerMixin {
	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void primeauthlib$overrideEntitySkin(CallbackInfoReturnable<PlayerSkin> cir) {
		Player player = (Player) (Object) this;
		PlayerSkin customSkin = CustomSkinManager.getCustomSkin(player.getGameProfile(), cir.getReturnValue());
		if (customSkin != null) {
			cir.setReturnValue(customSkin);
		}
	}
}
