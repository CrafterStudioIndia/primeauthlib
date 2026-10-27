package com.primeauthlib.client.skin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.primeauthlib.client.security.SecurityUtils;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages multiplayer server skin synchronization (/skin, /skinrestorer, /sr).
 * Features:
 * - MineSkin & direct URL support (e.g. https://mineskin.org/skins/828c0e10e02c4f02921b2b8244f3ae35).
 * - Multi-server command history list with status tracking (SUCCESS, PENDING, ERROR, SKIPPED).
 * - Automatic error detection from server responses with interactive "Should I still try?" retry prompts.
 * - Singleplayer guard: completely disables /skin in local singleplayer worlds ("no /skin in the worlds").
 * - Auto-sync on multiplayer server join.
 */
public class ServerSkinManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("primeauthlib-serverskin");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);

	public static class ServerCommandRecord {
		public String server;
		public String command;
		public String skinUrl;
		public String timestamp;
		public String status; // "SUCCESS", "PENDING", "ERROR", "SKIPPED"
		public String errorMessage;

		public ServerCommandRecord() {}

		public ServerCommandRecord(String server, String command, String skinUrl, String timestamp, String status, @Nullable String errorMessage) {
			this.server = server;
			this.command = command;
			this.skinUrl = skinUrl;
			this.timestamp = timestamp;
			this.status = status;
			this.errorMessage = errorMessage;
		}
	}

	public record CommandResult(boolean success, String message, boolean isError) {}

	private static String serverSkinUrl = "";
	private static boolean autoSyncOnJoin = true;
	private static final List<ServerCommandRecord> history = new CopyOnWriteArrayList<>();
	private static final java.util.concurrent.ExecutorService ASYNC_IO = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "PrimeAuthLib-ServerSkin-IO");
		t.setDaemon(true);
		return t;
	});

	private static String lastSentCommand = null;
	private static String lastSentSkinUrl = null;
	private static String lastServerAddress = null;
	private static long lastSentCommandTime = 0;
	private static String activeErrorMessage = null;
	private static boolean hasActiveErrorPrompt = false;

	private static boolean initialized = false;

	public static synchronized void init() {
		if (initialized) return;
		initialized = true;

		loadHistory();

		// Intercept game and chat messages from server to capture command execution status and errors
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				handleIncomingServerMessage(message);
			}
		});

		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			handleIncomingServerMessage(message);
		});

		// Auto-sync on joining multiplayer servers (disabled in singleplayer)
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (!autoSyncOnJoin) return;
			// Singleplayer check: no /skin in worlds
			if (client.isSingleplayer() || client.hasSingleplayerServer() || client.getCurrentServer() == null) {
				LOGGER.info("Joined singleplayer world - skipping server skin sync.");
				return;
			}

			// Delay 3 seconds after join to allow server authentication and command registry sync
			CompletableFuture.delayedExecutor(3, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
				Minecraft mc = Minecraft.getInstance();
				if (mc != null && mc.getConnection() != null && !mc.isSingleplayer() && mc.getCurrentServer() != null) {
					if (serverSkinUrl != null && !serverSkinUrl.isBlank()) {
						LOGGER.info("Auto-syncing server skin on join for: {}", mc.getCurrentServer().ip);
						triggerServerSkin(serverSkinUrl, false);
					}
				}
			});
		});

		LOGGER.info("ServerSkinManager initialized.");
	}

	public static String getServerSkinUrl() {
		return serverSkinUrl;
	}

	public static void setServerSkinUrl(String url) {
		serverSkinUrl = url != null ? url.trim() : "";
		CustomSkinManager.saveConfig();
	}

	public static boolean isAutoSyncOnJoin() {
		return autoSyncOnJoin;
	}

	public static void setAutoSyncOnJoin(boolean enable) {
		autoSyncOnJoin = enable;
		CustomSkinManager.saveConfig();
	}

	public static List<ServerCommandRecord> getHistory() {
		return new ArrayList<>(history);
	}

	public static void clearHistory() {
		history.clear();
		saveHistory();
	}

	public static boolean hasActiveErrorPrompt() {
		return hasActiveErrorPrompt;
	}

	public static String getActiveErrorMessage() {
		return activeErrorMessage != null ? activeErrorMessage : "";
	}

	public static void dismissError() {
		hasActiveErrorPrompt = false;
		activeErrorMessage = null;
	}

	/**
	 * Retries the last issued server skin command after an error or user confirmation.
	 */
	public static CommandResult retryLastCommand() {
		dismissError();
		if (lastSentSkinUrl != null && !lastSentSkinUrl.isBlank()) {
			return triggerServerSkin(lastSentSkinUrl, true);
		} else if (serverSkinUrl != null && !serverSkinUrl.isBlank()) {
			return triggerServerSkin(serverSkinUrl, true);
		}
		return new CommandResult(false, "No skin URL to retry", true);
	}

	/**
	 * Dispatches server skin command (/skin, /skinrestorer, /sr).
	 * Strictly respects singleplayer worlds: completely bypasses command if in singleplayer.
	 */
	public static CommandResult triggerServerSkin(String targetUrl, boolean isRetry) {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.getConnection() == null) {
			return new CommandResult(false, "Not connected to any game", false);
		}

		// NO /SKIN IN THE WORLDS: Guard against executing /skin in singleplayer local worlds
		if (client.isSingleplayer() || client.hasSingleplayerServer() || client.getCurrentServer() == null) {
			LOGGER.info("Singleplayer world detected: skipping /skin command (local skins apply directly).");
			recordHistory("Singleplayer World", "(None)", targetUrl, "SKIPPED", "Singleplayer world: /skin command disabled in worlds");
			return new CommandResult(false, "Singleplayer world: /skin disabled (local skins apply directly)", false);
		}

		String serverAddress = client.getCurrentServer().ip;

		// OpSec check: validate targetUrl against command injection
		if (!SecurityUtils.isSafeCommandArgument(targetUrl)) {
			String err = "OpSec Rejection: Command argument contains disallowed characters or injection sequences";
			LOGGER.error("Prevented potential command injection: {}", targetUrl);
			recordHistory(serverAddress, "skin [BLOCKED]", targetUrl, "ERROR", err);
			return new CommandResult(false, err, true);
		}

		var connection = client.getConnection();
		var dispatcher = connection.getCommands();
		if (dispatcher == null) {
			recordHistory(serverAddress, "skin", targetUrl, "ERROR", "Server command dispatcher not available");
			return new CommandResult(false, "Command dispatcher not ready yet", true);
		}

		var root = dispatcher.getRoot();
		var skinNode = root.getChild("skin");
		var srNode = root.getChild("skinrestorer");
		if (srNode == null) {
			srNode = root.getChild("sr");
		}

		String commandToRun;
		if (skinNode != null) {
			if (skinNode.getChild("set") != null) {
				commandToRun = "skin set " + targetUrl;
			} else if (skinNode.getChild("url") != null) {
				commandToRun = "skin url " + targetUrl;
			} else {
				commandToRun = "skin set " + targetUrl;
			}
		} else if (srNode != null) {
			String baseCmd = srNode.getName();
			if (srNode.getChild("set") != null) {
				commandToRun = baseCmd + " set " + targetUrl;
			} else if (srNode.getChild("url") != null) {
				commandToRun = baseCmd + " url " + targetUrl;
			} else {
				commandToRun = baseCmd + " set " + targetUrl;
			}
		} else {
			// Default to standard SkinsRestorer command: /skin set <url>
			commandToRun = "skin set " + targetUrl;
		}

		try {
			connection.sendCommand(commandToRun);

			lastSentCommand = commandToRun;
			lastSentSkinUrl = targetUrl;
			lastServerAddress = serverAddress;
			lastSentCommandTime = System.currentTimeMillis();
			hasActiveErrorPrompt = false;
			activeErrorMessage = null;

			recordHistory(serverAddress, "/" + commandToRun, targetUrl, "PENDING", null);
			LOGGER.info("Sent skin command to server {}: /{}", serverAddress, commandToRun);

			return new CommandResult(true, "Issued: /" + commandToRun, false);
		} catch (Exception e) {
			String err = "Failed to send command: " + e.getMessage();
			recordHistory(serverAddress, "/" + commandToRun, targetUrl, "ERROR", err);
			activeErrorMessage = err;
			hasActiveErrorPrompt = true;
			return new CommandResult(false, err, true);
		}
	}

	/**
	 * Inspects incoming messages from the server to detect errors or successes from /skin commands.
	 */
	private static void handleIncomingServerMessage(Component message) {
		if (lastSentCommandTime == 0 || System.currentTimeMillis() - lastSentCommandTime > 8000) {
			return; // Only monitor for 8 seconds after command sent
		}

		String rawText = message.getString().trim();
		if (rawText.isEmpty()) {
			return;
		}

		String lower = rawText.toLowerCase(Locale.ROOT);

		// Common server skin plugin error signatures
		boolean isError = lower.contains("error") ||
			lower.contains("invalid") ||
			lower.contains("not found") ||
			lower.contains("failed") ||
			lower.contains("cooldown") ||
			lower.contains("unknown command") ||
			lower.contains("rate limit") ||
			lower.contains("could not load") ||
			(lower.contains("permission") && lower.contains("denied"));

		// Common success signatures
		boolean isSuccess = lower.contains("skin has been set") ||
			lower.contains("skin changed") ||
			lower.contains("skin updated") ||
			lower.contains("skin set") ||
			lower.contains("skin will be updated");

		if (!isError && !isSuccess) {
			return;
		}

		// Command response matched: acknowledge and stop monitoring subsequent messages
		lastSentCommandTime = 0;
		String text = SecurityUtils.sanitizeForDisplay(rawText, 160);

		if (isError) {
			LOGGER.warn("Server reported error for /skin command: {}", text);
			activeErrorMessage = text;
			hasActiveErrorPrompt = true;

			if (lastServerAddress != null) {
				updateHistoryStatus(lastServerAddress, "ERROR", text);
			}

			// Deliver interactive chat notification asking the user:
			// "This is the error. Should I still try? [Click to Retry]"
			Minecraft client = Minecraft.getInstance();
			if (client != null && client.gui != null && client.gui.getChat() != null) {
				MutableComponent prompt = Component.literal("§c[PrimeAuthLib] Server error: §f\"" + text + "\"\n§eShould I still try? ")
					.append(Component.literal("§b§n[Click Here to Retry]§r")
						.withStyle(style -> style
							.withClickEvent(new ClickEvent.RunCommand("/authlibskins retryserver"))
							.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to retry sending skin command to server")))
						)
					);
				client.gui.getChat().addMessage(prompt);
			}
		} else if (isSuccess) {
			LOGGER.info("Server reported success for /skin command: {}", text);
			if (lastServerAddress != null) {
				updateHistoryStatus(lastServerAddress, "SUCCESS", null);
			}
			hasActiveErrorPrompt = false;
			activeErrorMessage = null;
		}
	}

	/**
	 * Downloads and caches the skin locally (supporting MineSkin URLs and direct URLs).
	 */
	public static CompletableFuture<String> fetchAndCacheSkin(String inputUrl) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				if (inputUrl == null || inputUrl.isBlank()) {
					return "URL cannot be empty";
				}
				String cleanUrl = SecurityUtils.validateAndSanitizeUrl(inputUrl);
				byte[] bytes = CustomSkinManager.downloadSkinBytes(cleanUrl);

				String skinFileName;
				Matcher matcher = CustomSkinManager.MINESKIN_PATTERN.matcher(cleanUrl);
				if (matcher.find()) {
					skinFileName = "mineskin_" + matcher.group(1) + ".png";
				} else {
					skinFileName = "server_skin_" + (System.currentTimeMillis() % 100000) + ".png";
				}
				skinFileName = SecurityUtils.sanitizeFileName(skinFileName);

				Path targetFile = CustomSkinManager.getSkinsDir().resolve(skinFileName);
				Files.write(targetFile, bytes);
				CustomSkinManager.selectSkin(targetFile);

				return "SUCCESS:" + skinFileName;
			} catch (Exception e) {
				LOGGER.error("Error fetching server skin: {}", inputUrl, e);
				return "Error: " + e.getMessage();
			}
		});
	}

	private static void recordHistory(String server, String command, String skinUrl, String status, @Nullable String errorMessage) {
		String time = DATE_FORMAT.format(new Date());
		// Update existing if matching same server, or prepend new
		boolean updated = false;
		for (ServerCommandRecord rec : history) {
			if (rec.server.equalsIgnoreCase(server)) {
				rec.command = command;
				rec.skinUrl = skinUrl;
				rec.timestamp = time;
				rec.status = status;
				rec.errorMessage = errorMessage;
				updated = true;
				break;
			}
		}
		if (!updated) {
			history.add(0, new ServerCommandRecord(server, command, skinUrl, time, status, errorMessage));
		}
		if (history.size() > 50) {
			history.remove(history.size() - 1);
		}
		saveHistory();
	}

	private static void updateHistoryStatus(String server, String status, @Nullable String errorMessage) {
		for (ServerCommandRecord rec : history) {
			if (rec.server.equalsIgnoreCase(server)) {
				rec.status = status;
				if (errorMessage != null) {
					rec.errorMessage = errorMessage;
				}
				break;
			}
		}
		saveHistory();
	}

	private static void saveHistory() {
		ASYNC_IO.execute(ServerSkinManager::saveHistorySync);
	}

	private static void saveHistorySync() {
		try {
			Path file = CustomSkinManager.getSkinsDir().resolve("server_skin_history.json");
			String json = GSON.toJson(history);
			Files.writeString(file, json);
		} catch (Exception e) {
			LOGGER.warn("Failed to save server_skin_history.json", e);
		}
	}

	private static void loadHistory() {
		try {
			Path file = CustomSkinManager.getSkinsDir().resolve("server_skin_history.json");
			if (Files.exists(file)) {
				String json = Files.readString(file);
				Type type = new TypeToken<List<ServerCommandRecord>>(){}.getType();
				List<ServerCommandRecord> loaded = GSON.fromJson(json, type);
				if (loaded != null) {
					history.clear();
					history.addAll(loaded);
				}
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to load server_skin_history.json", e);
		}
	}
}
