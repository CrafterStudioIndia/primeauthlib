package com.primeauthlib.client.gui;

import com.primeauthlib.client.skin.CustomSkinManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.nio.file.Path;
import java.util.List;

/**
 * Modern, clean Settings GUI for PrimeAuthLib / auth-libskins for Minecraft 26.3.
 * Features:
 * - Native Minecraft styled 2-column balanced layout.
 * - Interactive 3D skin model preview with left/right carousel arrows.
 * - Drag-and-drop skin importing with dropzone indication.
 * - Quick access to Fetch Skin (Username & URL), Server Skin sync, and Authlib server settings.
 */
public class AuthLibSkinScreen extends Screen {
	private final Screen lastScreen;
	private PlayerSkinWidget skinPreviewWidget;
	private Button modelModeButton;
	private Button prevSkinButton;
	private Button nextSkinButton;
	private String dropStatusMessage = "";

	public AuthLibSkinScreen(Screen lastScreen) {
		super(Component.literal("PrimeAuthLib Skin Settings"));
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		super.init();

		// Interactive 3D Player Model Preview Widget (click and drag to rotate)
		if (this.minecraft != null) {
			this.skinPreviewWidget = new PlayerSkinWidget(
				85,
				105,
				this.minecraft.getEntityModels(),
				() -> {
					PlayerSkin customSkin = CustomSkinManager.getLocalPlayerPreviewSkin();
					if (customSkin != null) {
						return customSkin;
					}
					return DefaultPlayerSkin.getDefaultSkin();
				}
			);
			this.skinPreviewWidget.setPosition(this.width / 2 - 42, 46);
			this.addRenderableWidget(this.skinPreviewWidget);
		}

		// Carousel arrows flanking the 3D preview card
		this.prevSkinButton = Button.builder(Component.literal("◀"), button -> {
			CustomSkinManager.selectPreviousSkin();
			this.dropStatusMessage = "";
		})
		.bounds(this.width / 2 - 124, 80, 26, 40)
		.build();
		this.addRenderableWidget(this.prevSkinButton);

		this.nextSkinButton = Button.builder(Component.literal("▶"), button -> {
			CustomSkinManager.selectNextSkin();
			this.dropStatusMessage = "";
		})
		.bounds(this.width / 2 + 98, 80, 26, 40)
		.build();
		this.addRenderableWidget(this.nextSkinButton);

		// Action Buttons: 2-column balanced grid (width 150 each, total 310, centered at width/2 - 155)
		// Row 1: Fetch Skin (Name/URL) & Server Skin (/skin set)
		Button fetchSkinBtn = Button.builder(Component.literal("📥 Fetch Skin (Name/URL)..."), button -> {
			if (this.minecraft != null) {
				this.minecraft.gui.setScreen(new FetchSkinScreen(this));
			}
		})
		.bounds(this.width / 2 - 155, this.height - 78, 150, 20)
		.build();
		this.addRenderableWidget(fetchSkinBtn);

		Button serverSkinBtn = Button.builder(Component.literal("🌐 Server Skin (/skin set)..."), button -> {
			if (this.minecraft != null) {
				this.minecraft.gui.setScreen(new ServerSkinScreen(this));
			}
		})
		.bounds(this.width / 2 + 5, this.height - 78, 150, 20)
		.build();
		this.addRenderableWidget(serverSkinBtn);

		// Row 2: Model Mode & Open Folder
		this.modelModeButton = Button.builder(getModelButtonText(), button -> {
			CustomSkinManager.ModelMode nextMode = switch (CustomSkinManager.getModelMode()) {
				case AUTO -> CustomSkinManager.ModelMode.SLIM;
				case SLIM -> CustomSkinManager.ModelMode.WIDE;
				case WIDE -> CustomSkinManager.ModelMode.AUTO;
			};
			CustomSkinManager.setModelMode(nextMode);
			button.setMessage(getModelButtonText());
		})
		.bounds(this.width / 2 - 155, this.height - 54, 150, 20)
		.build();
		this.addRenderableWidget(this.modelModeButton);

		Button openFolderButton = Button.builder(Component.literal("📂 Open Skins Folder"), button -> {
			com.mojang.blaze3d.Blaze3D.openPath(CustomSkinManager.getSkinsDir());
		})
		.bounds(this.width / 2 + 5, this.height - 54, 150, 20)
		.build();
		this.addRenderableWidget(openFolderButton);

		// Row 3: Reload (72) | Authlib Server (72) | Done (150)
		Button reloadButton = Button.builder(Component.literal("🔄 Reload"), button -> {
			CustomSkinManager.reloadAll();
			this.dropStatusMessage = "§aReloaded all skins from disk!";
		})
		.bounds(this.width / 2 - 155, this.height - 30, 72, 20)
		.build();
		this.addRenderableWidget(reloadButton);

		Button authlibServerBtn = Button.builder(Component.literal("⚙️ Authlib"), button -> {
			if (this.minecraft != null) {
				this.minecraft.gui.setScreen(new AuthlibServerScreen(this));
			}
		})
		.bounds(this.width / 2 - 79, this.height - 30, 74, 20)
		.build();
		this.addRenderableWidget(authlibServerBtn);

		Button doneButton = Button.builder(Component.literal("Done"), button -> {
			this.onClose();
		})
		.bounds(this.width / 2 + 5, this.height - 30, 150, 20)
		.build();
		this.addRenderableWidget(doneButton);
	}

	private Component getModelButtonText() {
		return Component.literal("🔁 " + CustomSkinManager.getModelMode().getDisplayName());
	}

	@Override
	public void onFilesDrop(List<Path> paths) {
		if (paths == null || paths.isEmpty()) return;
		int imported = 0;
		Path lastImported = null;
		for (Path path : paths) {
			if (CustomSkinManager.importSkinFile(path)) {
				imported++;
				lastImported = path;
			}
		}
		if (imported > 0 && lastImported != null) {
			this.dropStatusMessage = "§aImported and selected: " + lastImported.getFileName();
		} else {
			this.dropStatusMessage = "§cOnly .png skin files can be imported";
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		// Screen Header
		graphics.centeredText(this.font, Component.literal("§lPrimeAuthLib Skin Settings"), this.width / 2, 12, 0xFFFFFF);
		graphics.centeredText(this.font, Component.literal("§7Cycle, import, and customize player skins"), this.width / 2, 23, 0x888888);

		int cardLeft = this.width / 2 - 90;
		int cardTop = 36;
		int cardRight = this.width / 2 + 90;
		int cardBottom = 168;

		// Showcase Card Background & Subtle Border
		drawCard(graphics, cardLeft, cardTop, cardRight, cardBottom, 0x770C0D14, 0x44667799);

		// Dropzone hint in showcase card header
		boolean hoveringCard = mouseX >= cardLeft && mouseX <= cardRight && mouseY >= cardTop && mouseY <= cardBottom;
		String dropHint = hoveringCard ? "§e[ Drop .png file to import ]" : "§8⇩ Drag & Drop .png Skin ⇩";
		graphics.centeredText(this.font, Component.literal(dropHint), this.width / 2, cardTop + 4, 0x888888);

		// Carousel info pill underneath player preview
		String carouselInfo = CustomSkinManager.getSelectedSkinCarouselInfo();
		graphics.fill(cardLeft + 6, cardBottom - 15, cardRight - 6, cardBottom - 2, 0xAA000000);
		graphics.centeredText(this.font, Component.literal(carouselInfo), this.width / 2, cardBottom - 13, 0xEEEEEE);

		// Model Type indicator pill below showcase card
		Path active = CustomSkinManager.getActiveLocalSkinFile();
		PlayerModelType model = CustomSkinManager.getModelForSkinFile(active);
		String modelText = model == PlayerModelType.SLIM
			? "§bArms: Slim (Alex · 3px)"
			: "§6Arms: Classic (Steve · 4px)";
		graphics.centeredText(this.font, Component.literal(modelText), this.width / 2, 172, 0xDDDDDD);

		// Status / Feedback message or folder shortcut hint
		if (!this.dropStatusMessage.isEmpty()) {
			graphics.fill(this.width / 2 - 155, 184, this.width / 2 + 155, 196, 0x66000000);
			graphics.centeredText(this.font, Component.literal(this.dropStatusMessage), this.width / 2, 186, 0xFFFFFF);
		} else {
			String folderHint = "Folder: §7" + CustomSkinManager.SKINS_DIR_NAME + "  §8|  Shortcut: §eShift + K";
			graphics.centeredText(this.font, Component.literal(folderHint), this.width / 2, 186, 0x777777);
		}
	}

	private void drawCard(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int bgColor, int borderColor) {
		graphics.fill(left, top, right, bottom, bgColor);
		graphics.fill(left, top, right, top + 1, borderColor);
		graphics.fill(left, bottom - 1, right, bottom, borderColor);
		graphics.fill(left, top, left + 1, bottom, borderColor);
		graphics.fill(right - 1, top, right, bottom, borderColor);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(this.lastScreen);
		}
	}
}
