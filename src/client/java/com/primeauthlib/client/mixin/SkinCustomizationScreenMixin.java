package com.primeauthlib.client.mixin;

import com.primeauthlib.client.gui.AuthLibSkinScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.SkinCustomizationScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkinCustomizationScreen.class)
public abstract class SkinCustomizationScreenMixin extends Screen {
	protected SkinCustomizationScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void primeauthlib$addAuthLibSkinButton(CallbackInfo ci) {
		this.addRenderableWidget(
			Button.builder(Component.literal("Auth-Lib Skin..."), button -> {
				if (this.minecraft != null) {
					this.minecraft.setScreen(new AuthLibSkinScreen(this));
				}
			})
			.bounds(this.width / 2 - 100, this.height - 54, 200, 20)
			.build()
		);
	}
}

