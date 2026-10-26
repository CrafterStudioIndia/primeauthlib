package com.primeauthlib.client.gui;

import com.primeauthlib.client.skin.CustomSkinManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Dedicated Screen to fetch skins from Mojang usernames and direct URLs / MineSkin for Minecraft 26.3.
 * Features:
 * - Dual-input interface: Fetch by username OR fetch by image/MineSkin URL.
 * - Downloads directly into auth-libskins/ and automatically equips the skin.
 * - Auto-detects Slim vs Classic model arms.
 */
public class FetchSkinScreen extends Screen {
	private final Screen parent;
	private EditBox usernameField;
	private EditBox urlField;
	private String statusMessage = "";
	private boolean isFetching = false;

	public FetchSkinScreen(Screen parent) {
		super(Component.literal("Fetch & Import Player Skin"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		super.init();

		// Section 1: Username Input
		this.usernameField = new EditBox(
			this.font,
			this.width / 2 - 155,
			46,
			195,
			20,
			Component.literal("Username")
		);
		this.usernameField.setMaxLength(32);
		this.usernameField.setHint(Component.literal("e.g. Technoblade, Notch"));
		this.addRenderableWidget(this.usernameField);

		Button fetchUserBtn = Button.builder(Component.literal("📥 Download"), button -> {
			String name = this.usernameField.getValue();
			if (name.isBlank() || isFetching) return;
			isFetching = true;
			this.statusMessage = "§eDownloading skin for player '" + name.trim() + "'...";
			CustomSkinManager.fetchSkinFromUsername(name).thenAccept(res -> {
				isFetching = false;
				if (res.startsWith("SUCCESS:")) {
					this.statusMessage = "§aDownloaded & equipped: " + res.substring(8);
				} else {
					this.statusMessage = "§c" + res;
				}
			});
		})
		.bounds(this.width / 2 + 45, 46, 110, 20)
		.build();
		this.addRenderableWidget(fetchUserBtn);

		// Section 2: URL Input (MineSkin or direct PNG)
		this.urlField = new EditBox(
			this.font,
			this.width / 2 - 155,
			84,
			195,
			20,
			Component.literal("Skin URL")
		);
		this.urlField.setMaxLength(512);
		this.urlField.setHint(Component.literal("e.g. https://mineskin.org/skins/..."));
		this.addRenderableWidget(this.urlField);

		Button fetchUrlBtn = Button.builder(Component.literal("🌐 Fetch URL"), button -> {
			String url = this.urlField.getValue();
			if (url.isBlank() || isFetching) return;
			isFetching = true;
			this.statusMessage = "§eDownloading skin from URL...";
			CustomSkinManager.fetchSkinFromUrl(url, null).thenAccept(res -> {
				isFetching = false;
				if (res.startsWith("SUCCESS:")) {
					this.statusMessage = "§aDownloaded & equipped: " + res.substring(8);
				} else {
					this.statusMessage = "§c" + res;
				}
			});
		})
		.bounds(this.width / 2 + 45, 84, 110, 20)
		.build();
		this.addRenderableWidget(fetchUrlBtn);

		// Bottom Buttons (width 150 each)
		Button openFolderBtn = Button.builder(Component.literal("📂 Open Skins Folder"), button -> {
			com.mojang.blaze3d.Blaze3D.openPath(CustomSkinManager.getSkinsDir());
		})
		.bounds(this.width / 2 - 155, this.height - 30, 150, 20)
		.build();
		this.addRenderableWidget(openFolderBtn);

		Button backBtn = Button.builder(Component.literal("Done"), button -> {
			this.onClose();
		})
		.bounds(this.width / 2 + 5, this.height - 30, 150, 20)
		.build();
		this.addRenderableWidget(backBtn);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		// Screen Title & Subtitle
		graphics.centeredText(this.font, Component.literal("§lFetch & Import Skin"), this.width / 2, 12, 0xFFFFFF);
		graphics.centeredText(this.font, Component.literal("§7Download and equip skins from Mojang usernames or direct URLs"), this.width / 2, 22, 0x888888);

		// Field Labels
		graphics.text(this.font, Component.literal("§fMojang Player Username:"), this.width / 2 - 155, 36, 0xDDDDDD);
		graphics.text(this.font, Component.literal("§fSkin Image or MineSkin URL:"), this.width / 2 - 155, 74, 0xDDDDDD);

		// Status pill
		int statusY = 112;
		graphics.fill(this.width / 2 - 155, statusY, this.width / 2 + 155, statusY + 16, 0x66000000);
		String displayStatus = this.statusMessage.isEmpty() ? "§7Enter a username or URL above to download." : this.statusMessage;
		graphics.centeredText(this.font, Component.literal(displayStatus), this.width / 2, statusY + 4, 0xFFFFFF);

		// Info & Tips Box
		int infoY = 134;
		int infoBottom = this.height - 40;
		if (infoBottom > infoY + 20) {
			graphics.fill(this.width / 2 - 155, infoY, this.width / 2 + 155, infoBottom, 0x44000000);
			drawCardOutline(graphics, this.width / 2 - 155, infoY, this.width / 2 + 155, infoBottom, 0x33FFFFFF);

			graphics.text(this.font, Component.literal("§6💡 Info & Features:"), this.width / 2 - 147, infoY + 6, 0xFFAA00);
			graphics.text(this.font, Component.literal("§7• Automatically detects §bSlim (3px) §7or §6Classic (4px) §7arm model"), this.width / 2 - 147, infoY + 18, 0xCCCCCC);
			graphics.text(this.font, Component.literal("§7• Resolves MineSkin links, direct PNGs, and Mojang textures"), this.width / 2 - 147, infoY + 30, 0xCCCCCC);
			graphics.text(this.font, Component.literal("§7• Saves skin file into §fauth-libskins/ §7and equips immediately"), this.width / 2 - 147, infoY + 42, 0xCCCCCC);
			graphics.text(this.font, Component.literal("§7• Switch between skins using §e◀ §7and §e▶ §7in the skin menu"), this.width / 2 - 147, infoY + 54, 0xCCCCCC);
		}
	}

	private void drawCardOutline(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int borderColor) {
		graphics.fill(left, top, right, top + 1, borderColor);
		graphics.fill(left, bottom - 1, right, bottom, borderColor);
		graphics.fill(left, top, left + 1, bottom, borderColor);
		graphics.fill(right - 1, top, right, bottom, borderColor);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(this.parent);
		}
	}
}
