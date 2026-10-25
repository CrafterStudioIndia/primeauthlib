package com.primeauthlib.client.mixin;

import com.primeauthlib.client.gui.AuthLibSkinScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = OptionsScreen.class, priority = 1100)
public abstract class OptionsScreenMixin extends Screen {
	protected OptionsScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void primeauthlib$addAuthLibSkinButton(CallbackInfo ci) {
		// Prevent adding multiple times if init is recalled by other mods
		boolean alreadyExists = this.children().stream().anyMatch(w ->
			w instanceof AbstractWidget widget && widget.getMessage().getString().contains("Auth-Lib Skin")
		);
		if (alreadyExists) {
			return;
		}

		int btnWidth = 110;
		int btnX = this.width / 2 + 105;
		int btnY = this.height - 28;

		// Detect if another mod (Sodium, Bedrockify, ModMenu, etc.) placed a button in this slot
		boolean slotOccupied = isSlotOccupied(btnX, btnY, btnWidth, 20);

		if (slotOccupied || btnX + btnWidth > this.width - 5) {
			// Alternate position: above the Done button
			btnX = this.width / 2 - 100;
			btnY = this.height - 52;
			btnWidth = 200;

			// If that's also occupied, place to the left side
			if (isSlotOccupied(btnX, btnY, btnWidth, 20)) {
				btnWidth = 110;
				btnX = Math.max(5, this.width / 2 - 215);
				btnY = this.height - 28;
			}
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

	private boolean isSlotOccupied(int x, int y, int width, int height) {
		return this.children().stream().anyMatch(w -> {
			if (w instanceof AbstractWidget widget) {
				return widget.getX() < x + width &&
				       widget.getX() + widget.getWidth() > x &&
				       widget.getY() < y + height &&
				       widget.getY() + widget.getHeight() > y;
			}
			return false;
		});
	}
}
