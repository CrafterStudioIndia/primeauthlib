package com.primeauthlib.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrimeAuthLibClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("primeauthlib-client");

	@Override
	public void onInitializeClient() {
		LOGGER.info("Initializing PrimeAuthLib client components");
	}
}

