package com.primeauthlib.client.gui;

import com.primeauthlib.client.authlib.AuthlibInjector;
import com.primeauthlib.client.skin.CustomSkinManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Screen to configure custom remote Authlib-Injector API servers (e.g. LittleSkin, Ely.by, Blessing Skin).
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
			this.width / 2 - 150,
			70,
			300,
			20,
			Component.literal("Authlib Server URL")
		);
		this.urlField.setMaxLength(512);
		this.urlField.setValue(AuthlibInjector.getAuthlibServerUrl());
		this.addRenderableWidget(this.urlField);

		// Preset: LittleSkin
		Button littleSkinBtn = Button.builder(Component.literal("Preset: LittleSkin"), b -> {
			this.urlField.setValue("https://littleskin.cn/api/yggdrasil");
		})
		.bounds(this.width / 2 - 150, 100, 145, 20)
		.build();
		this.addRenderableWidget(littleSkinBtn);

		// Preset: Ely.by
		Button elyByBtn = Button.builder(Component.literal("Preset: Ely.by"), b -> {
			this.urlField.setValue("https://authserver.ely.by/api/authlib-injector");
		})
		.bounds(this.width / 2 + 5, 100, 145, 20)
		.build();
		this.addRenderableWidget(elyByBtn);

		// Save Button
		Button saveBtn = Button.builder(Component.literal("Save"), b -> {
			AuthlibInjector.setAuthlibServerUrl(this.urlField.getValue());
			CustomSkinManager.saveConfig();
			CustomSkinManager.reloadAll();
			this.onClose();
		})
		.bounds(this.width / 2 - 150, this.height - 40, 95, 20)
		.build();
		this.addRenderableWidget(saveBtn);

		// Clear Button
		Button clearBtn = Button.builder(Component.literal("Clear"), b -> {
			this.urlField.setValue("");
			AuthlibInjector.setAuthlibServerUrl("");
			CustomSkinManager.saveConfig();
			CustomSkinManager.reloadAll();
		})
		.bounds(this.width / 2 - 45, this.height - 40, 90, 20)
		.build();
		this.addRenderableWidget(clearBtn);

		// Cancel Button
		Button cancelBtn = Button.builder(Component.literal("Cancel"), b -> {
			this.onClose();
		})
		.bounds(this.width / 2 + 55, this.height - 40, 95, 20)
		.build();
		this.addRenderableWidget(cancelBtn);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);

		graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
		graphics.drawCenteredString(this.font, Component.literal("Enter Authlib-Injector API Root URL (or leave blank for local-only skins):"), this.width / 2, 52, 0xAAAAAA);

		String status = AuthlibInjector.hasServerUrl()
			? "Configured: §a" + AuthlibInjector.getAuthlibServerUrl()
			: "Status: §eLocal In-Game Authlib Injection Active (No external server)";
		graphics.drawCenteredString(this.font, Component.literal(status), this.width / 2, 135, 0xCCCCCC);

		graphics.drawCenteredString(this.font, Component.literal("§7Bypasses all server limitations and loads skin textures in-game."), this.width / 2, 155, 0x777777);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(this.parent);
		}
	}
}

