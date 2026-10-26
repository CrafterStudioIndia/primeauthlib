package com.primeauthlib.client.gui;

import com.primeauthlib.client.authlib.AuthlibInjector;
import com.primeauthlib.client.skin.CustomSkinManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Screen to configure custom remote Authlib-Injector API servers (e.g. LittleSkin, Ely.by, Blessing Skin) for Minecraft 26.3.
 * Clean native Minecraft style without clunky tab bars.
 */
public class AuthlibServerScreen extends Screen {
	private final Screen parent;
	private EditBox urlField;

	public AuthlibServerScreen(Screen parent) {
		super(Component.literal("Authlib Injector Server Configuration"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		super.init();

		this.urlField = new EditBox(
			this.font,
			this.width / 2 - 155,
			50,
			310,
			20,
			Component.literal("Authlib Server URL")
		);
		this.urlField.setMaxLength(512);
		this.urlField.setValue(AuthlibInjector.getAuthlibServerUrl());
		this.urlField.setHint(Component.literal("e.g. https://littleskin.cn/api/yggdrasil"));
		this.addRenderableWidget(this.urlField);

		// Preset buttons (width 150 each)
		Button littleSkinBtn = Button.builder(Component.literal("⚡ Preset: LittleSkin"), b -> {
			this.urlField.setValue("https://littleskin.cn/api/yggdrasil");
		})
		.bounds(this.width / 2 - 155, 78, 150, 20)
		.build();
		this.addRenderableWidget(littleSkinBtn);

		Button elyByBtn = Button.builder(Component.literal("⚡ Preset: Ely.by"), b -> {
			this.urlField.setValue("https://authserver.ely.by/api/authlib-injector");
		})
		.bounds(this.width / 2 + 5, 78, 150, 20)
		.build();
		this.addRenderableWidget(elyByBtn);

		// Bottom row: Save (100) | Clear (100) | Done (100)
		Button saveBtn = Button.builder(Component.literal("💾 Save"), b -> {
			AuthlibInjector.setAuthlibServerUrl(this.urlField.getValue());
			CustomSkinManager.saveConfig();
			CustomSkinManager.reloadAll();
			this.onClose();
		})
		.bounds(this.width / 2 - 155, this.height - 30, 100, 20)
		.build();
		this.addRenderableWidget(saveBtn);

		Button clearBtn = Button.builder(Component.literal("🗑️ Clear"), b -> {
			this.urlField.setValue("");
			AuthlibInjector.setAuthlibServerUrl("");
			CustomSkinManager.saveConfig();
			CustomSkinManager.reloadAll();
		})
		.bounds(this.width / 2 - 50, this.height - 30, 100, 20)
		.build();
		this.addRenderableWidget(clearBtn);

		Button doneBtn = Button.builder(Component.literal("Done"), b -> {
			this.onClose();
		})
		.bounds(this.width / 2 + 55, this.height - 30, 100, 20)
		.build();
		this.addRenderableWidget(doneBtn);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		// Title & Subtitle
		graphics.centeredText(this.font, Component.literal("§lRemote Authlib-Injector API Server"), this.width / 2, 12, 0xFFFFFF);
		graphics.centeredText(this.font, Component.literal("§7Connect custom authentication servers or leave empty for local skins"), this.width / 2, 22, 0x888888);

		graphics.text(this.font, Component.literal("§fAuthlib API Root URL:"), this.width / 2 - 155, 38, 0xDDDDDD);

		// Status card
		int statusY = 108;
		int statusBottom = this.height - 40;
		if (statusBottom > statusY + 20) {
			graphics.fill(this.width / 2 - 155, statusY, this.width / 2 + 155, statusBottom, 0x44000000);
			drawCardOutline(graphics, this.width / 2 - 155, statusY, this.width / 2 + 155, statusBottom, 0x33FFFFFF);

			String status = AuthlibInjector.hasServerUrl()
				? "§aConfigured: " + AuthlibInjector.getAuthlibServerUrl()
				: "§eStatus: Local In-Game Authlib Injection Active";
			graphics.centeredText(this.font, Component.literal(status), this.width / 2, statusY + 8, 0xCCCCCC);
			graphics.centeredText(this.font, Component.literal("§7Bypasses server restrictions and renders custom skins in-game."), this.width / 2, statusY + 22, 0x777777);
			graphics.centeredText(this.font, Component.literal("§7No external authlib server is needed for normal skin usage."), this.width / 2, statusY + 34, 0x777777);
		}
	}

	private void drawCardOutline(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int borderColor) {
		graphics.fill(left, top, right, bottom, borderColor);
		graphics.fill(left, top, right, top + 1, borderColor);
		graphics.fill(left, bottom - 1, right, bottom, borderColor);
		graphics.fill(left, top, left + 1, bottom, borderColor);
		graphics.fill(right - 1, top, right, bottom, borderColor);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(this.parent);
		}
	}
}
