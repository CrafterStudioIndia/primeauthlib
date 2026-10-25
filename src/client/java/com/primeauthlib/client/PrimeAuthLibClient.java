package com.primeauthlib.client;

import com.primeauthlib.client.skin.CustomSkinManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrimeAuthLibClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("primeauthlib-client");

	@Override
	public void onInitializeClient() {
		LOGGER.info("Initializing PrimeAuthLib client components");
		CustomSkinManager.init();

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(
				ClientCommandManager.literal("authlibskins")
					.then(ClientCommandManager.literal("reload").executes(context -> {
						CustomSkinManager.reloadAll();
						context.getSource().sendFeedback(Component.literal("§a[PrimeAuthLib] Reloaded all custom skins from auth-libskins/"));
						return 1;
					}))
					.executes(context -> {
						context.getSource().sendFeedback(Component.literal("§6[PrimeAuthLib] Skins directory: §e" + CustomSkinManager.getSkinsDir().toAbsolutePath()));
						return 1;
					})
			);
		});
	}
}

