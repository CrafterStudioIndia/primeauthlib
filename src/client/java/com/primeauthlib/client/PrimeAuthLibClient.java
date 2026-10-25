package com.primeauthlib.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.primeauthlib.client.gui.AuthLibSkinScreen;
import com.primeauthlib.client.skin.CustomSkinManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.SkinCustomizationScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrimeAuthLibClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("primeauthlib-client");

	private static KeyMapping openSkinScreenKey;

	@Override
	public void onInitializeClient() {
		LOGGER.info("Initializing PrimeAuthLib client components");
		CustomSkinManager.init();

		// 1. KeyBinding registration: Defaults to 'K' (configurable in Controls -> Key Binds)
		// Allows opening the skin GUI anytime, even if other MC clients or GUI overhaul mods replace menus
		openSkinScreenKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.primeauthlib.open_gui",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_K,
			KeyMapping.Category.MISC
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openSkinScreenKey.consumeClick()) {
				client.setScreen(new AuthLibSkinScreen(client.screen));
			}
		});

		// 2. Client Commands: /skin, /authlibskin, /primeskin
		// Allows instant access to the GUI on servers and clients where buttons might be moved or hidden
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(
				ClientCommandManager.literal("authlibskin")
					.executes(context -> {
						Minecraft.getInstance().execute(() -> {
							Minecraft.getInstance().setScreen(new AuthLibSkinScreen(Minecraft.getInstance().screen));
						});
						return 1;
					})
			);

			dispatcher.register(
				ClientCommandManager.literal("skin")
					.executes(context -> {
						Minecraft.getInstance().execute(() -> {
							Minecraft.getInstance().setScreen(new AuthLibSkinScreen(Minecraft.getInstance().screen));
						});
						return 1;
					})
			);

			dispatcher.register(
				ClientCommandManager.literal("primeskin")
					.executes(context -> {
						Minecraft.getInstance().execute(() -> {
							Minecraft.getInstance().setScreen(new AuthLibSkinScreen(Minecraft.getInstance().screen));
						});
						return 1;
					})
			);

			dispatcher.register(
				ClientCommandManager.literal("authlibskins")
					.then(ClientCommandManager.literal("reload").executes(context -> {
						CustomSkinManager.reloadAll();
						context.getSource().sendFeedback(Component.literal("§a[PrimeAuthLib] Reloaded all custom skins from auth-libskins/"));
						return 1;
					}))
					.executes(context -> {
						Minecraft.getInstance().execute(() -> {
							Minecraft.getInstance().setScreen(new AuthLibSkinScreen(Minecraft.getInstance().screen));
						});
						return 1;
					})
			);
		});

		// 3. ScreenEvents Integration (Safe fallback across other GUI mods and MC clients)
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof SkinCustomizationScreen) {
				boolean exists = Screens.getButtons(screen).stream()
					.anyMatch(w -> w.getMessage().getString().contains("Auth-Lib Skin"));
				if (!exists) {
					Screens.getButtons(screen).add(
						Button.builder(Component.literal("Auth-Lib Skin..."), button -> {
							client.setScreen(new AuthLibSkinScreen(screen));
						})
						.bounds(scaledWidth / 2 - 100, scaledHeight - 54, 200, 20)
						.build()
					);
				}
			}
		});
	}
}
