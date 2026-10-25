package com.primeauthlib.client.mixin;

import com.primeauthlib.client.gui.AuthLibSkinScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {
	protected OptionsScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void primeauthlib$addAuthLibSkinButton(CallbackInfo ci) {
		int btnWidth = 110;
		int btnX = this.width / 2 + 105;
		int btnY = this.height - 28;

		// Adapt cleanly if screen width is compact
		if (btnX + btnWidth > this.width - 5) {
			btnX = this.width / 2 - 100;
			btnY = this.height - 52;
			btnWidth = 200;
		}

		this.addRenderableWidget(
			Button.builder(Component.literal("Auth-Lib Skin..."), button -> {
				if (this.minecraft != null) {
					this.minecraft.setScreen(new AuthLibSkinScreen(this));
				}
			})
			.bounds(btnX, btnY, btnWidth, 20)
			.build()
		);
	}
}
