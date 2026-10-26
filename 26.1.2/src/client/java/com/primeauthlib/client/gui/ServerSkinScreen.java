package com.primeauthlib.client.gui;

import com.primeauthlib.client.skin.ServerSkinManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Modern, clean Screen for Multiplayer Server Skin synchronization (/skin set <url>) for Minecraft 26.3.
 * Features:
 * - Empty by default; stored individually in config.properties.
 * - Dedicated [Clear] button to clear and persist config immediately.
 * - Singleplayer guard: automatically skips in singleplayer ("no /skin in worlds").
 * - Multi-server command history list with status tracking.
 * - Error prompt: "Should I still try?" with one-click retry.
 */
public class ServerSkinScreen extends Screen {
	private final Screen parent;
	private EditBox urlField;
	private Button autoSyncButton;
	private Button retryErrorButton;
	private Button dismissErrorButton;
	private Button clearHistoryButton;
	private String statusMessage = "";
	private boolean isProcessing = false;

	public ServerSkinScreen(Screen parent) {
		super(Component.literal("Multiplayer Server Skin (/skin set)"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		super.init();

		// URL input field: starts empty unless saved in config
		this.urlField = new EditBox(
			this.font,
			this.width / 2 - 155,
			46,
			245,
			20,
			Component.literal("Server Skin URL")
		);
		this.urlField.setMaxLength(512);
		this.urlField.setValue(ServerSkinManager.getServerSkinUrl());
		this.urlField.setHint(Component.literal("e.g. https://mineskin.org/skins/..."));
		this.addRenderableWidget(this.urlField);

		// Clear URL Button
		Button clearUrlBtn = Button.builder(Component.literal("✕ Clear"), button -> {
			this.urlField.setValue("");
			ServerSkinManager.setServerSkinUrl("");
			this.statusMessage = "§7Cleared server skin URL.";
		})
		.bounds(this.width / 2 + 95, 46, 60, 20)
		.build();
		this.addRenderableWidget(clearUrlBtn);

		// Row 2 Action Buttons
		Button applySyncBtn = Button.builder(Component.literal("🚀 Apply & Sync (/skin set)"), button -> {
			String url = this.urlField.getValue().trim();
			if (url.isEmpty()) {
				this.statusMessage = "§cPlease enter a skin URL or MineSkin link.";
				return;
			}
			if (isProcessing) return;
			isProcessing = true;
			this.statusMessage = "§eDownloading skin and syncing to server...";

			ServerSkinManager.setServerSkinUrl(url);

			ServerSkinManager.fetchAndCacheSkin(url).thenAccept(res -> {
				isProcessing = false;
				ServerSkinManager.CommandResult cmdRes = ServerSkinManager.triggerServerSkin(url, false);
				if (cmdRes.success()) {
					this.statusMessage = "§a" + cmdRes.message();
				} else {
					this.statusMessage = cmdRes.isError() ? "§c" + cmdRes.message() : "§e" + cmdRes.message();
				}
			});
		})
		.bounds(this.width / 2 - 155, 72, 165, 20)
		.build();
		this.addRenderableWidget(applySyncBtn);

		this.autoSyncButton = Button.builder(getAutoSyncButtonText(), button -> {
			ServerSkinManager.setAutoSyncOnJoin(!ServerSkinManager.isAutoSyncOnJoin());
			button.setMessage(getAutoSyncButtonText());
		})
		.bounds(this.width / 2 + 15, 72, 140, 20)
		.build();
		this.addRenderableWidget(this.autoSyncButton);

		// Error Prompt Buttons: "Should I still try?"
		this.retryErrorButton = Button.builder(Component.literal("🔁 Still Try / Retry"), button -> {
			ServerSkinManager.CommandResult res = ServerSkinManager.retryLastCommand();
			this.statusMessage = res.success() ? "§a" + res.message() : "§c" + res.message();
		})
		.bounds(this.width / 2 - 110, 134, 110, 16)
		.build();
		this.addRenderableWidget(this.retryErrorButton);

		this.dismissErrorButton = Button.builder(Component.literal("✕ Dismiss"), button -> {
			ServerSkinManager.dismissError();
		})
		.bounds(this.width / 2 + 5, 134, 105, 16)
		.build();
		this.addRenderableWidget(this.dismissErrorButton);

		// Clear History Button
		this.clearHistoryButton = Button.builder(Component.literal("🗑️ Clear History"), button -> {
			ServerSkinManager.clearHistory();
			this.statusMessage = "§7Cleared server command history.";
		})
		.bounds(this.width / 2 + 65, 122, 90, 16)
		.build();
		this.addRenderableWidget(this.clearHistoryButton);

		// Bottom: Done Button
		Button backBtn = Button.builder(Component.literal("Done"), button -> {
			this.onClose();
		})
		.bounds(this.width / 2 - 100, this.height - 30, 200, 20)
		.build();
		this.addRenderableWidget(backBtn);
	}

	private Component getAutoSyncButtonText() {
		return Component.literal("⚡ Auto-Sync: " + (ServerSkinManager.isAutoSyncOnJoin() ? "§aON" : "§cOFF"));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		// Title & Subtitle
		graphics.centeredText(this.font, Component.literal("§lMultiplayer Server Skin Sync (/skin set)"), this.width / 2, 12, 0xFFFFFF);
		graphics.centeredText(this.font, Component.literal("§7Sync custom skins on servers running SkinsRestorer or /skin plugins"), this.width / 2, 22, 0x888888);

		// Field label
		graphics.text(this.font, Component.literal("§fSkin URL for Servers (/skin set <url>):"), this.width / 2 - 155, 36, 0xDDDDDD);

		// Environment status check: Singleplayer vs Multiplayer
		Minecraft mc = Minecraft.getInstance();
		boolean inSingleplayer = mc != null && (mc.hasSingleplayerServer() || mc.isLocalServer() || mc.getCurrentServer() == null);
		if (inSingleplayer) {
			graphics.fill(this.width / 2 - 155, 96, this.width / 2 + 155, 108, 0x44FFAA00);
			graphics.centeredText(this.font, Component.literal("§eℹ️ Singleplayer: /skin set is skipped (local skins apply directly)"), this.width / 2, 98, 0xFFAA00);
		} else if (mc != null && mc.getCurrentServer() != null) {
			graphics.centeredText(this.font, Component.literal("Connected Server: §a" + mc.getCurrentServer().ip), this.width / 2, 98, 0xAAAAAA);
		}

		// Status / Error message
		if (!this.statusMessage.isEmpty()) {
			graphics.centeredText(this.font, Component.literal(this.statusMessage), this.width / 2, 110, 0xFFFFFF);
		}

		// Active Error / "Should I still try?" panel
		boolean hasError = ServerSkinManager.hasActiveErrorPrompt();
		this.retryErrorButton.visible = hasError;
		this.dismissErrorButton.visible = hasError;

		int histHeaderY = 124;
		if (hasError) {
			String err = ServerSkinManager.getActiveErrorMessage();
			graphics.fill(this.width / 2 - 155, 120, this.width / 2 + 155, 154, 0x66DD2222);
			drawCardOutline(graphics, this.width / 2 - 155, 120, this.width / 2 + 155, 154, 0xAAFF4444);
			graphics.centeredText(this.font, Component.literal("§c⚠️ Server Error: §f" + err), this.width / 2, 122, 0xFF5555);
			graphics.centeredText(this.font, Component.literal("§eShould I still try?"), this.width / 2, 131, 0xFFFF55);
			histHeaderY = 160;
		}

		this.clearHistoryButton.setY(histHeaderY - 2);

		// Server Command History
		graphics.text(this.font, Component.literal("§6📋 Recent Server Commands:"), this.width / 2 - 155, histHeaderY, 0xFFAA00);

		int histBoxTop = histHeaderY + 14;
		int histBoxBottom = this.height - 40;
		if (histBoxBottom > histBoxTop + 15) {
			graphics.fill(this.width / 2 - 155, histBoxTop, this.width / 2 + 155, histBoxBottom, 0x55000000);
			drawCardOutline(graphics, this.width / 2 - 155, histBoxTop, this.width / 2 + 155, histBoxBottom, 0x33FFFFFF);

			List<ServerSkinManager.ServerCommandRecord> history = ServerSkinManager.getHistory();
			if (history.isEmpty()) {
				graphics.centeredText(this.font, Component.literal("§7(No server skin commands issued yet)"), this.width / 2, histBoxTop + 12, 0x777777);
			} else {
				int maxRows = Math.min(history.size(), Math.max(1, (histBoxBottom - histBoxTop - 6) / 14));
				int rowY = histBoxTop + 4;
				for (int i = 0; i < maxRows; i++) {
					ServerSkinManager.ServerCommandRecord rec = history.get(i);
					String statusBadge = switch (rec.status) {
						case "SUCCESS" -> "§a[SUCCESS]";
						case "PENDING" -> "§e[PENDING]";
						case "ERROR" -> "§c[ERROR]";
						case "SKIPPED" -> "§7[SKIPPED]";
						default -> "§f[" + rec.status + "]";
					};

					String line = statusBadge + " §f" + rec.server + " §8| §7" + rec.command;
					graphics.text(this.font, Component.literal(line), this.width / 2 - 150, rowY, 0xDDDDDD);
					rowY += 12;

					if (rec.errorMessage != null && !rec.errorMessage.isEmpty() && rowY < histBoxBottom - 8) {
						graphics.text(this.font, Component.literal("  §c↳ " + rec.errorMessage), this.width / 2 - 145, rowY, 0xFF6666);
						rowY += 12;
					}
				}
			}
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
			this.minecraft.setScreen(this.parent);
		}
	}
}
