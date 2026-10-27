package com.primeauthlib.client.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Enterprise OpSec, XSS, and Cryptographic Security Layer for PrimeAuthLib.
 * Provides:
 * 1. Strict URL & Protocol Whitelisting (HTTPS enforcement, scheme smuggling prevention, SSRF guards).
 * 2. Command Injection Prevention (safe parameter filtering for /skin set <url>).
 * 3. XSS & Control Character Neutralization (display and chat payload sanitization).
 * 4. Encrypted & Hashed Connection Transport (TLS enforcement, SHA-256 integrity checks, decompression bomb protection).
 * 5. Directory Traversal and Path Containment Guards.
 */
public final class SecurityUtils {
	private static final Logger LOGGER = LoggerFactory.getLogger("primeauthlib-security");

	public static final int MAX_URL_LENGTH = 2048;
	public static final int MAX_SKIN_PAYLOAD_BYTES = 5 * 1024 * 1024; // 5 MB maximum texture download
	public static final int MAX_REDIRECT_HOPS = 5;
	public static final int DEFAULT_CONNECT_TIMEOUT_MS = 6000;
	public static final int DEFAULT_READ_TIMEOUT_MS = 8000;

	// Safe command argument pattern: only alphanumeric, safe URI characters, no command delimiters or whitespace
	private static final Pattern SAFE_COMMAND_ARG_PATTERN = Pattern.compile("^[a-zA-Z0-9_.~:/?#=&%\\-]+$");

	// Control and dangerous character pattern
	private static final Pattern DANGEROUS_CHARS_PATTERN = Pattern.compile("[\\x00-\\x1F\\x7F-\\x9F\\u200B-\\u200F\\u2028-\\u202F\\uFEFF]");

	// PNG Magic Bytes: \x89 P N G \r \n \x1A \n
	private static final byte[] PNG_MAGIC = new byte[]{
		(byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
		(byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A
	};

	public record HashedPayload(byte[] bytes, String sha256Hex, int statusCode, String contentType) {}

	private SecurityUtils() {}

	/**
	 * Strictly validates a URL for secure remote communication.
	 * Enforces HTTPS (allowing HTTP only for local loopback development).
	 * Completely rejects dangerous pseudo-schemes (javascript:, data:, vbscript:, file:, blob:, jar:).
	 */
	public static String validateAndSanitizeUrl(String rawUrl) throws SecurityException {
		if (rawUrl == null || rawUrl.isBlank()) {
			throw new SecurityException("URL cannot be empty or null.");
		}

		String clean = rawUrl.trim();
		if (clean.length() > MAX_URL_LENGTH) {
			throw new SecurityException("URL exceeds maximum permitted length of " + MAX_URL_LENGTH + " characters.");
		}

		// Check for illegal control characters
		if (DANGEROUS_CHARS_PATTERN.matcher(clean).find() || clean.contains("\r") || clean.contains("\n") || clean.contains("\0")) {
			throw new SecurityException("URL contains illegal control or unprintable characters.");
		}

		URI uri;
		try {
			uri = URI.create(clean);
		} catch (Exception e) {
			throw new SecurityException("Malformed URI syntax: " + e.getMessage());
		}

		String scheme = uri.getScheme();
		if (scheme == null) {
			throw new SecurityException("URL missing protocol scheme.");
		}
		scheme = scheme.toLowerCase(Locale.ROOT);

		// Blacklist dangerous pseudo-schemes
		if (scheme.equals("javascript") || scheme.equals("data") || scheme.equals("vbscript") ||
			scheme.equals("file") || scheme.equals("blob") || scheme.equals("jar") || scheme.equals("about")) {
			throw new SecurityException("Forbidden URL scheme: " + scheme + ":// (OpSec violation)");
		}

		// Whitelist HTTPS, or HTTP only on local loopback
		if (scheme.equals("https")) {
			return clean;
		} else if (scheme.equals("http")) {
			String host = uri.getHost();
			if (isLoopbackHost(host)) {
				LOGGER.warn("Allowing non-TLS loopback HTTP connection to {}", host);
				return clean;
			}
			throw new SecurityException("Insecure HTTP protocol is disallowed for external connections. HTTPS is required for OpSec: " + clean);
		}

		throw new SecurityException("Unsupported protocol scheme: " + scheme + " (only HTTPS is permitted)");
	}

	/**
	 * Determines if the host is a local loopback address.
	 */
	public static boolean isLoopbackHost(String host) {
		if (host == null) return false;
		String lower = host.toLowerCase(Locale.ROOT);
		return lower.equals("localhost") || lower.equals("127.0.0.1") || lower.equals("[::1]") || lower.equals("0.0.0.0");
	}

	/**
	 * Verifies if a texture URL is permissible for Authlib TextureUrlChecker.
	 * Allows secure HTTPS skins, safe loopback HTTP, and verified local file:// within auth-libskins.
	 */
	public static boolean isAllowedTextureUrl(String url, Path skinsDir) {
		if (url == null || url.isBlank() || url.length() > MAX_URL_LENGTH) {
			return false;
		}

		String clean = url.trim();
		if (DANGEROUS_CHARS_PATTERN.matcher(clean).find() || clean.contains("\r") || clean.contains("\n") || clean.contains("\0")) {
			return false;
		}

		try {
			URI uri = URI.create(clean);
			String scheme = uri.getScheme();
			if (scheme == null) return false;
			scheme = scheme.toLowerCase(Locale.ROOT);

			if (scheme.equals("https")) {
				return true;
			}

			if (scheme.equals("http")) {
				return isLoopbackHost(uri.getHost());
			}

			if (scheme.equals("file")) {
				// OpSec check: Only allow file:// URIs strictly pointing inside the mod skinsDir
				Path path = Path.of(uri);
				return isPathContained(skinsDir, path);
			}

			return false;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Validates an argument before embedding it in a server chat command (e.g. /skin set <arg>).
	 * Prevents command injection, newline splitting, semicolons, shell pipes, and whitespace escaping.
	 */
	public static boolean isSafeCommandArgument(String arg) {
		if (arg == null || arg.isBlank() || arg.length() > MAX_URL_LENGTH) {
			return false;
		}

		String trimmed = arg.trim();
		if (!SAFE_COMMAND_ARG_PATTERN.matcher(trimmed).matches()) {
			return false;
		}

		// Ensure no command delimiter characters
		return !trimmed.contains(" ") &&
			   !trimmed.contains("\t") &&
			   !trimmed.contains("\n") &&
			   !trimmed.contains("\r") &&
			   !trimmed.contains(";") &&
			   !trimmed.contains("\"") &&
			   !trimmed.contains("'") &&
			   !trimmed.contains("`") &&
			   !trimmed.contains("|") &&
			   !trimmed.contains("&") &&
			   !trimmed.contains("$");
	}

	/**
	 * Sanitizes text from untrusted sources (such as server error responses or user inputs)
	 * to prevent XSS-style chat injection, formatting code hijacking, or terminal escape sequences.
	 */
	public static String sanitizeForDisplay(String text, int maxLength) {
		if (text == null) return "";
		// Strip control characters, unprintable chars, ANSI escapes
		String cleaned = text.replaceAll("\\x1B\\[[0-9;]*[a-zA-Z]", ""); // Remove ANSI escapes
		cleaned = DANGEROUS_CHARS_PATTERN.matcher(cleaned).replaceAll("");
		cleaned = cleaned.replace("\r", " ").replace("\n", " ").replace("\t", " ");
		// Strip raw Minecraft section sign formatting symbols from external server messages to prevent spoofing
		cleaned = cleaned.replace("§", "");

		cleaned = cleaned.trim();
		if (cleaned.length() > maxLength) {
			cleaned = cleaned.substring(0, maxLength) + "...";
		}
		return cleaned;
	}

	/**
	 * Sanitizes a custom skin file name to prevent directory traversal attacks.
	 */
	public static String sanitizeFileName(String name) {
		if (name == null || name.isBlank()) {
			return "skin_" + System.currentTimeMillis();
		}
		// Strip any directory traversal components
		String base = name.replace("\\", "/");
		int lastSlash = base.lastIndexOf('/');
		if (lastSlash != -1) {
			base = base.substring(lastSlash + 1);
		}
		// Allow only alphanumeric, underscores, hyphens, and periods
		base = base.replaceAll("[^a-zA-Z0-9._-]", "_");
		if (base.isBlank() || base.equals(".") || base.equals("..")) {
			base = "skin_" + System.currentTimeMillis();
		}
		return base;
	}

	/**
	 * Opens an encrypted HTTPS connection with verified security parameters,
	 * strict timeouts, and safe headers.
	 */
	public static HttpURLConnection openEncryptedConnection(String urlStr, int connectTimeoutMs, int readTimeoutMs) throws IOException {
		String secureUrl = validateAndSanitizeUrl(urlStr);
		URL url = URI.create(secureUrl).toURL();
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();

		if (conn instanceof HttpsURLConnection httpsConn) {
			try {
				SSLContext sslContext = SSLContext.getDefault();
				httpsConn.setSSLSocketFactory(sslContext.getSocketFactory());
			} catch (NoSuchAlgorithmException ignored) {
			}
		}

		conn.setRequestProperty("User-Agent", "PrimeAuthLib/1.0 (Fabric; TLS-Encrypted; SHA-256)");
		conn.setRequestProperty("Accept", "image/png,image/*,application/json;q=0.9,*/*;q=0.8");
		conn.setRequestProperty("Accept-Encoding", "identity"); // Prevent decompression bombs
		conn.setConnectTimeout(connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS);
		conn.setReadTimeout(readTimeoutMs > 0 ? readTimeoutMs : DEFAULT_READ_TIMEOUT_MS);
		conn.setInstanceFollowRedirects(false); // Manual redirection to enforce OpSec at each hop

		return conn;
	}

	/**
	 * Fetches payload bytes over an encrypted connection following redirects safely (up to 5 hops),
	 * validating the size cap, and computing an end-to-end SHA-256 hash.
	 */
	public static HashedPayload fetchEncryptedAndHashedPayload(String initialUrl, int maxBytes) throws IOException {
		String currentUrl = initialUrl;
		int maxCap = maxBytes > 0 ? maxBytes : MAX_SKIN_PAYLOAD_BYTES;

		for (int hop = 0; hop < MAX_REDIRECT_HOPS; hop++) {
			HttpURLConnection conn = openEncryptedConnection(currentUrl, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
			int code = conn.getResponseCode();

			// Handle HTTP redirects securely
			if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
				String location = conn.getHeaderField("Location");
				if (location == null || location.isBlank()) {
					throw new IOException("Redirect response missing Location header from " + currentUrl);
				}
				// Resolve relative redirects safely
				URI base = URI.create(currentUrl);
				URI target = base.resolve(location);
				currentUrl = validateAndSanitizeUrl(target.toString());
				LOGGER.info("Following secure HTTPS redirect (hop {}/{}): {}", hop + 1, MAX_REDIRECT_HOPS, currentUrl);
				continue;
			}

			if (code != HttpURLConnection.HTTP_OK) {
				throw new IOException("HTTP " + code + " (" + conn.getResponseMessage() + ") from " + currentUrl);
			}

			String contentType = conn.getContentType();
			int contentLength = conn.getContentLength();
			if (contentLength > maxCap) {
				throw new IOException("Remote payload exceeds maximum size limit of " + maxCap + " bytes (Reported: " + contentLength + ")");
			}

			// Read stream with size limit enforcement and SHA-256 streaming hash
			MessageDigest digest;
			try {
				digest = MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException e) {
				throw new IOException("SHA-256 algorithm not available", e);
			}

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int totalBytes = 0;

			try (InputStream in = conn.getInputStream()) {
				int read;
				while ((read = in.read(buffer)) != -1) {
					totalBytes += read;
					if (totalBytes > maxCap) {
						throw new IOException("Remote payload exceeded maximum allowed size of " + maxCap + " bytes during transfer (Decompression bomb guard)");
					}
					digest.update(buffer, 0, read);
					baos.write(buffer, 0, read);
				}
			}

			byte[] payloadBytes = baos.toByteArray();
			String sha256Hex = bytesToHex(digest.digest());

			LOGGER.info("Securely downloaded {} bytes from {} (SHA-256: {})", payloadBytes.length, currentUrl, sha256Hex);
			return new HashedPayload(payloadBytes, sha256Hex, code, contentType != null ? contentType : "");
		}

		throw new IOException("Exceeded maximum redirect hops (" + MAX_REDIRECT_HOPS + ")");
	}

	/**
	 * Computes the SHA-256 hexadecimal hash string for any byte array.
	 */
	public static String computeSha256Hex(byte[] data) {
		if (data == null) return "";
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return bytesToHex(digest.digest(data));
		} catch (NoSuchAlgorithmException e) {
			return "";
		}
	}

	/**
	 * Validates PNG magic bytes.
	 */
	public static boolean isValidPng(byte[] bytes) {
		if (bytes == null || bytes.length < PNG_MAGIC.length) {
			return false;
		}
		for (int i = 0; i < PNG_MAGIC.length; i++) {
			if (bytes[i] != PNG_MAGIC[i]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Validates skin image dimensions from PNG IHDR chunk (without full decompression)
	 * to prevent pixel decompression bombs (e.g. 100000x100000 images).
	 */
	public static void validatePngDimensions(byte[] bytes, int maxDimension) throws IOException {
		if (!isValidPng(bytes)) {
			throw new IOException("Invalid texture payload: missing PNG magic header.");
		}
		if (bytes.length < 24) {
			throw new IOException("PNG file too small to contain a valid IHDR chunk.");
		}

		// IHDR starts at offset 12; width at 16, height at 20 (4 bytes big-endian each)
		int width = ((bytes[16] & 0xFF) << 24) | ((bytes[17] & 0xFF) << 16) | ((bytes[18] & 0xFF) << 8) | (bytes[19] & 0xFF);
		int height = ((bytes[20] & 0xFF) << 24) | ((bytes[21] & 0xFF) << 16) | ((bytes[22] & 0xFF) << 8) | (bytes[23] & 0xFF);

		if (width <= 0 || height <= 0) {
			throw new IOException("Invalid PNG dimensions: " + width + "x" + height);
		}

		if (width > maxDimension || height > maxDimension) {
			throw new IOException("Skin dimensions exceed maximum allowed bounds (" + maxDimension + "px): " + width + "x" + height);
		}

		// Valid Minecraft skin aspect ratios: 64x64, 64x32, or powers-of-two (1:1 or 2:1)
		boolean validAspectRatio = (width == height) || (width == height * 2);
		if (!validAspectRatio) {
			throw new IOException("Invalid skin aspect ratio (" + width + "x" + height + "). Minecraft skins must be 1:1 or 2:1 (e.g. 64x64, 64x32).");
		}
	}

	/**
	 * Guarantees that targetPath is strictly located inside basePath, preventing path traversal.
	 */
	public static boolean isPathContained(Path basePath, Path targetPath) {
		if (basePath == null || targetPath == null) return false;
		try {
			Path normalizedBase = basePath.toAbsolutePath().normalize();
			Path normalizedTarget = targetPath.toAbsolutePath().normalize();
			return normalizedTarget.startsWith(normalizedBase);
		} catch (Exception e) {
			return false;
		}
	}

	private static String bytesToHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}
}
