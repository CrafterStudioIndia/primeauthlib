# PrimeAuthLib

[![Minecraft Versions](https://img.shields.io/badge/Minecraft-1.21.1%20%7C%201.21.11%20%7C%2026.1.2%20%7C%2026.3-brightgreen.svg)](https://minecraft.net/)
[![Fabric Loader](https://img.shields.io/badge/Fabric-0.16.0+--0.20.1+-blue.svg)](https://fabricmc.net/)
[![Java Compatibility](https://img.shields.io/badge/Java-21%20%7C%2025-orange.svg)](https://openjdk.org/)
[![Security & OpSec](https://img.shields.io/badge/Security-TLS%20%7C%20SHA--256%20%7C%20XSS--Protected-success.svg)](#-enterprise-opsec--security)
[![License MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**PrimeAuthLib** is a lightweight, high-performance client-side Fabric mod that loads custom player skins directly from an `auth-libskins` folder in your instance directory—with **full multi-version support**, **enterprise OpSec & XSS protection**, **TLS-encrypted & SHA-256 hashed connections**, and an in-game **Authlib Injector**!

It features **Slim (Alex) vs Wide (Steve) auto-detection**, an interactive **3D player model preview GUI**, and a **built-in in-game Authlib Injector** that hooks directly into Mojang's session services—completely bypassing third-party skin API limitations, offline-mode restrictions, and server-side skin limits without requiring any external `-javaagent`!

---

## 📦 Supported Minecraft Versions

| Version | Directory | Java | Fabric Loader | Fabric API | Status |
| :--- | :--- | :---: | :---: | :---: | :---: |
| **Minecraft 1.21.11** | Root (`./`) | Java 21+ | `>=0.20.1` | `0.141.6+1.21.11` | ✅ Verified Stable |
| **Minecraft 1.21.1** | `1.21.1/` | Java 21+ | `>=0.16.0` | `0.106.0+1.21.1` | ✅ Verified Stable |
| **Minecraft 26.1.2** | `26.1.2/` | Java 25+ | `>=0.19.3` | `0.155.3+26.1.2` | ✅ Verified Stable |
| **Minecraft 26.3** | `26.3/` | Java 25+ | `>=0.20.1` | `0.165.0+26.3` | ✅ Verified Stable |

---

## 🛡️ Enterprise OpSec & Security Architecture

PrimeAuthLib includes a defense-in-depth security layer (`SecurityUtils`) built for adversarial multiplayer and internet environments:

- 🔒 **Hashed & Encrypted Connection Transport**:
  - **TLS Enforcement**: External network connections (Mojang API, MineSkin, session servers, and Authlib-Injector API servers) strictly require **HTTPS / TLS 1.2+ / TLS 1.3**. Insecure plain HTTP over the internet is blocked. Non-TLS HTTP is only permitted on local loopback (`localhost`, `127.0.0.1`, `[::1]`).
  - **SHA-256 Cryptographic Checksumming**: Every downloaded texture payload is streamed and verified through a real-time SHA-256 digest engine. Tamper-evident logging ensures complete integrity.
  - **Decompression Bomb Protection**: Hard payload cap (5 MB maximum) and strict streaming guards protect client memory against infinite gzip/decompression bomb attacks and memory exhaustion denial of service.
  - **Strict Image Dimension & Magic Byte Verification**: Validates PNG magic header bytes (`0x89 50 4E 47 0D 0A 1A 0A`) and IHDR bounds (16px to 1024px; strictly 1:1 or 2:1 aspect ratio) prior to texture upload.
- 🛡️ **XSS & Output Sanitization**:
  - Neutralizes unprintable control codes, terminal escape sequences (`\x1B[...]`), bidi override spoofing (`\u202E`), and formatting bypasses.
  - Sanitizes server error feedback before injecting into Minecraft's chat component engine, preventing chat injection or link spoofing.
- 🚫 **Command Injection Prevention**:
  - Validates and sanitizes parameters passed to `/skin set <url>`. Rejects any payload containing whitespace, tabs, newlines (`\r`, `\n`), semicolons (`;`), quotes, pipes (`|`), ampersands (`&`), or command chaining syntax.
- 📂 **Directory Traversal Guards**:
  - Local `file://` skin URLs and imported files are strictly normalized and canonicalized to guarantee containment within `auth-libskins/`.

---

## ✨ Core Features

- 📁 **Dedicated Skins Folder (`auth-libskins/`)**:
  - Automatically configured for the detected Minecraft version upon launch.
  - Drop any `.png` skin file into `auth-libskins/` to use it immediately.
- 👤 **Intelligent Model Auto-Detection**:
  - Automatically detects whether a skin uses **Slim (Alex / 3-pixel arms)** or **Wide (Steve / 4-pixel arms)**.
  - Supports standard 64x64 textures, legacy 64x32 textures (automatically upgraded), and high-resolution (HD) square skins.
  - Override option (Auto / Slim / Wide) available in the GUI.
- 🌐 **Multiplayer Server Skin Synchronization (`/skin set <url>`)**:
  - Synchronizes skins with server-side plugins (SkinsRestorer, `/skin`, `/skinrestorer`, `/sr`).
  - Dual fetch: download from Mojang usernames OR direct image / MineSkin URLs.
  - Multi-server history list tracking statuses (`SUCCESS`, `PENDING`, `ERROR`, `SKIPPED`).
  - **Singleplayer Guard**: Completely prevents issuing `/skin` commands in singleplayer local worlds ("no /skin in the worlds").
  - **Interactive Error Recovery**: If a server plugin returns an error, the mod captures it, prompts the user: *"This is the error. Should I still try?"*, and offers a one-click `[Retry]` action.
- 🔓 **Built-In In-Game Authlib Injector**:
  - Directly hooks `com.mojang.authlib` session verification and texture unpacking in-game.
  - Bypasses Mojang's strict `.minecraft.net` / `.mojang.com` domain check (`TextureUrlChecker`) while enforcing HTTPS OpSec.
  - Seamlessly integrates with remote **Authlib-Injector API servers** (e.g., **LittleSkin**, **Ely.by**, **Blessing Skin**).
- 🔄 **Real-Time Hot-Reloading**:
  - Modify or replace any `.png` on disk while Minecraft is running, and it refreshes in-game within 1 second.
- 🖥️ **Interactive 3D Preview Settings Screen**:
  - Balanced native 2-column layout with a 3D rotatable player model preview card.
  - Carousel navigation arrows (◀ / ▶) to quickly switch between local skins.
  - Drag-and-drop `.png` skin files directly onto the preview card to import them.

---

## 🎨 Skin File Naming Conventions

Place your `.png` skin files into the `auth-libskins/` directory in your `.minecraft` instance:

| File Name | Description |
| :--- | :--- |
| `skin.png` | **Default Local Player Skin**: Applies to your player everywhere (singleplayer, online servers, offline/cracked servers). |
| `<username>.png` | **Username Match**: Applies to a specific player name (e.g. `Crafter.png`, `Steve.png`). Case-insensitive. |
| `<uuid>.png` | **UUID Match**: Applies to a specific player UUID (with or without dashes; online or offline UUID). |
| `default.png` | **Fallback Skin**: Fallback skin for players who do not have an official or custom skin. |

---

## ⌨️ Accessing the In-Game GUI

You can open the **"Auth-Lib Skin"** settings screen in any of the following ways:

1. **Hotkey**: Press **`Left Shift + K`** anywhere in-game or in menus.
2. **Options Menu**: Click **"Auth-Lib Skin..."** at the bottom of the vanilla **Options** screen.
3. **Skin Customization**: Click **"Auth-Lib Skin..."** inside **Options -> Skin Customization...**.
4. **ModMenu**: Click the config button next to **PrimeAuthLib** in the ModMenu list.

---

## 🛠️ Building from Source

### Requirements
- **JDK 21** (for Minecraft 1.21.1 and 1.21.11)
- **JDK 25** (for Minecraft 26.1.2 and 26.3)
- Git

### Build Commands

```bash
# Clone the repository
git clone https://github.com/crafterstudioindia/primeauthlib.git
cd primeauthlib

# Build Minecraft 1.21.11 (Root Base Project - Java 21)
./gradlew build --no-daemon
# Output: build/libs/primeauthlib-1.0.0.jar

# Build Minecraft 1.21.1 (Java 21)
cd 1.21.1 && ./gradlew build --no-daemon && cd ..
# Output: 1.21.1/build/libs/primeauthlib-1.21.1-1.0.0.jar

# Build Minecraft 26.1.2 (Java 25)
cd 26.1.2 && ./gradlew build --no-daemon && cd ..
# Output: 26.1.2/build/libs/primeauthlib-26.1.2-1.0.0.jar

# Build Minecraft 26.3 (Java 25)
cd 26.3 && ./gradlew build --no-daemon && cd ..
# Output: 26.3/build/libs/primeauthlib-26.3-1.0.0.jar
```

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
