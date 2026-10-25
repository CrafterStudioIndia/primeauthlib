package com.primeauthlib.client.gui;

import com.primeauthlib.client.skin.CustomSkinManager;
import net.minecraft.util.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * Settings GUI for PrimeAuthLib / auth-libskins.
 * Features an interactive 3D skin model preview, model mode toggling (Auto/Slim/Wide),
 * instant hot-reloading, and quick opening of the skins folder.
 */
public class AuthLibSkinScreen extends Screen {
	private final Screen lastScreen;
	private PlayerSkinWidget skinPreviewWidget;
	private Button modelModeButton;

	public AuthLibSkinScreen(Screen lastScreen) {
		super(Component.literal("Auth-Lib Skin Settings"));
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		super.init();

		// Interactive 3D Player Model Preview Widget (click and drag to rotate)
		if (this.minecraft != null) {
			this.skinPreviewWidget = new PlayerSkinWidget(
				85,
				120,
				this.minecraft.getEntityModels(),
				() -> {
					PlayerSkin customSkin = CustomSkinManager.getLocalPlayerPreviewSkin();
					if (customSkin != null) {
						return customSkin;
					}
					return DefaultPlayerSkin.getDefaultSkin();
				}
			);
			this.skinPreviewWidget.setPosition(this.width / 2 - 42, 36);
			this.addRenderableWidget(this.skinPreviewWidget);
		}

		// Model Mode Cycle Button: Auto-Detect -> Slim -> Wide
		this.modelModeButton = Button.builder(getModelButtonText(), button -> {
			CustomSkinManager.ModelMode nextMode = switch (CustomSkinManager.getModelMode()) {
				case AUTO -> CustomSkinManager.ModelMode.SLIM;
				case SLIM -> CustomSkinManager.ModelMode.WIDE;
				case WIDE -> CustomSkinManager.ModelMode.AUTO;
			};
			CustomSkinManager.setModelMode(nextMode);
			button.setMessage(getModelButtonText());
		})
		.bounds(this.width / 2 - 100, this.height - 82, 200, 20)
		.build();
		this.addRenderableWidget(this.modelModeButton);

		// Open Folder Button
		Button openFolderButton = Button.builder(Component.literal("Open Skins Folder"), button -> {
			Util.getPlatform().openPath(CustomSkinManager.getSkinsDir());
		})
		.bounds(this.width / 2 - 155, this.height - 56, 150, 20)
		.build();
		this.addRenderableWidget(openFolderButton);

		// Reload Button
		Button reloadButton = Button.builder(Component.literal("Reload Skins"), button -> {
			CustomSkinManager.reloadAll();
		})
		.bounds(this.width / 2 + 5, this.height - 56, 150, 20)
		.build();
		this.addRenderableWidget(reloadButton);

		// Done Button
		Button doneButton = Button.builder(Component.literal("Done"), button -> {
			this.onClose();
		})
		.bounds(this.width / 2 - 100, this.height - 28, 200, 20)
		.build();
		this.addRenderableWidget(doneButton);
	}

	private Component getModelButtonText() {
		return Component.literal("Model: " + CustomSkinManager.getModelMode().getDisplayName());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);

		// Screen Title
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);

		// Active skin file information
		Path activeFile = CustomSkinManager.getActiveLocalSkinFile();
		String fileStatus = activeFile != null
			? "Active Skin: §a" + activeFile.getFileName()
			: "Active Skin: §cNone (Place skin.png in folder)";
		graphics.drawCenteredString(this.font, Component.literal(fileStatus), this.width / 2, 162, 0xCCCCCC);

		// Folder hint
		String folderHint = "Folder: §7" + CustomSkinManager.SKINS_DIR_NAME;
		graphics.drawCenteredString(this.font, Component.literal(folderHint), this.width / 2, 174, 0x888888);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(this.lastScreen);
		}
	}
}

