package com.primeauthlib.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.primeauthlib.client.gui.AuthLibSkinScreen;
import com.primeauthlib.client.skin.CustomSkinManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.options.SkinCustomizationScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrimeAuthLibClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("primeauthlib-client");

	private static KeyMapping openSkinScreenKey;

	@Override
	public void onInitializeClient() {
		LOGGER.info("Initializing PrimeAuthLib client components for Minecraft 26.3");
		CustomSkinManager.init();

		// 1. KeyMapping registration: Defaults to 'K' requiring Left Shift modifier (Left Shift + K)
		// Allows opening the skin GUI anytime, even if other MC clients or GUI overhaul mods replace menus
		openSkinScreenKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.primeauthlib.open_gui",
			InputConstants.KEY_K,
			KeyMapping.Category.MISC
		));

		// In-game tick listener: Requires Left Shift + K
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openSkinScreenKey.consumeClick()) {
				if (InputConstants.isKeyDown(InputConstants.KEY_LSHIFT)) {
					client.gui.setScreen(new AuthLibSkinScreen(client.gui.screen()));
				}
			}
		});

		// 2. Client Commands: /skin, /authlibskin, /primeskin
		// Allows instant access to the GUI on servers and clients where buttons might be moved or hidden
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(
				ClientCommands.literal("authlibskin")
					.executes(context -> {
						Minecraft.getInstance().execute(() -> {
							Minecraft.getInstance().gui.setScreen(new AuthLibSkinScreen(Minecraft.getInstance().gui.screen()));
						});
						return 1;
					})
			);

			dispatcher.register(
				ClientCommands.literal("primeskin")
					.executes(context -> {
						Minecraft.getInstance().execute(() -> {
							Minecraft.getInstance().gui.setScreen(new AuthLibSkinScreen(Minecraft.getInstance().gui.screen()));
						});
						return 1;
					})
			);

			dispatcher.register(
				ClientCommands.literal("authlibskins")
					.then(ClientCommands.literal("reload").executes(context -> {
						CustomSkinManager.reloadAll();
						context.getSource().sendFeedback(Component.literal("§a[PrimeAuthLib] Reloaded all custom skins from auth-libskins/"));
						return 1;
					}))
					.then(ClientCommands.literal("retryserver").executes(context -> {
						var res = com.primeauthlib.client.skin.ServerSkinManager.retryLastCommand();
						context.getSource().sendFeedback(Component.literal(res.success() ? "§a[PrimeAuthLib] " + res.message() : "§c[PrimeAuthLib] " + res.message()));
						return 1;
					}))
					.executes(context -> {
						Minecraft.getInstance().execute(() -> {
							Minecraft.getInstance().gui.setScreen(new AuthLibSkinScreen(Minecraft.getInstance().gui.screen()));
						});
						return 1;
					})
			);
		});

		// 3. ScreenEvents Integration (Safe fallback across other GUI mods and MC clients)
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			// Add button to SkinCustomizationScreen if not present
			if (screen instanceof SkinCustomizationScreen) {
				boolean exists = Screens.getWidgets(screen).stream()
					.anyMatch(w -> w.getMessage().getString().contains("Auth-Lib Skin"));
				if (!exists) {
					Screens.getWidgets(screen).add(
						Button.builder(Component.literal("Auth-Lib Skin..."), button -> {
							client.gui.setScreen(new AuthLibSkinScreen(screen));
						})
						.bounds(scaledWidth / 2 - 100, scaledHeight - 54, 200, 20)
						.build()
					);
				}
			}

			// Screen-level keyboard event: Left Shift + K works inside Pause Menu, Title Screen, etc.
			// (Safely skips when typing in chat or inside text input boxes)
			ScreenKeyboardEvents.beforeKeyPress(screen).register((s, keyEvent) -> {
				if (!(s instanceof AuthLibSkinScreen) && !(s instanceof ChatScreen)) {
					if (s.getFocused() instanceof EditBox) {
						return;
					}
					if (keyEvent.key() == InputConstants.KEY_K && keyEvent.hasShiftDown()) {
						if (InputConstants.isKeyDown(InputConstants.KEY_LSHIFT)) {
							client.gui.setScreen(new AuthLibSkinScreen(s));
						}
					}
				}
			});
		});
	}
}
