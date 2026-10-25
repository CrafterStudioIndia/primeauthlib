# PrimeAuthLib

A client-side Fabric mod for **Minecraft 1.21.11** that loads custom player skins directly from an `auth-libskins` folder in the instance directory.

It automatically detects whether a skin is **Slim (Alex)** or **Wide/Normal (Steve)** and applies it in-game, completely bypassing third-party skin APIs, offline-mode limitations, or server-side skin restrictions. Includes an in-game settings GUI accessible directly from the Minecraft options menu!

---

## 🎨 How It Works

Upon client startup, the mod automatically creates an `auth-libskins` folder inside your Minecraft instance directory (e.g. `.minecraft/auth-libskins` or your launcher instance folder).

### Skin File Naming:
| File Name | Description |
| :--- | :--- |
| `skin.png` | Applies to **YOUR** local player everywhere (singleplayer, offline/cracked servers, online servers). |
| `<username>.png` | Applies to a specific player name (e.g., `Crafter.png`, `Steve.png`). Case-insensitive. |
| `<uuid>.png` | Applies to a specific player UUID (online or offline UUID, with or without dashes). |
| `default.png` | Fallback skin for any player who does not have an official or custom skin. |

> [!TIP]
> **Zero-Config for Offline Accounts**: If you drop only a single `.png` skin file into `auth-libskins/`, the mod automatically binds it to your offline player!

---

## 🖥️ In-Game Settings GUI

Access the custom skin menu via:
- **Options...** -> Click **"Auth-Lib Skin..."**
- Or **Options...** -> **Skin Customization...** -> Click **"Auth-Lib Skin..."**
- Or in chat: `/authlibskins`

### GUI Features:
- **Interactive 3D Skin Preview**: View your active player skin in real time; click and drag with your mouse to rotate the model!
- **Model Switcher**: Cycle between `Auto-Detect`, `Slim (Alex)`, and `Wide (Steve)` to override model detection if desired.
- **Open Skins Folder**: One-click button to open `auth-libskins` directly in your OS file explorer (Windows Explorer, macOS Finder, or Linux file manager).
- **Reload Skins**: Force an instant hot-reload and clear texture caches.

---

## 🌟 Key Features & Bug Fixes

- **Full Offline / Cracked Account Support**:
  - Automatically calculates and matches deterministic offline UUIDs (`UUID.nameUUIDFromBytes("OfflinePlayer:" + username)`).
  - Injects directly into `DefaultPlayerSkin`, `SkinManager`, `PlayerInfo`, and `AbstractClientPlayer`.
  - Works 100% offline (no internet access or Mojang session connection required).
- **Automatic Slim / Wide Model Detection**:
  - Inspects the 64x64 texture's unused arm coordinate regions (also supports HD square skins like 128x128).
  - If transparency is found in the 3-pixel arm gap, it assigns `PlayerSkin.Model.SLIM` (Alex); otherwise `PlayerSkin.Model.WIDE` (Steve).
- **Thread Safety**: Guarantees all OpenGL texture registrations and uploads run on the Render Thread to prevent netty/async worker thread crashes.
- **Optimized Disk Caching**: Fast in-memory cache with rate-limited file checks to prevent disk I/O lag and stuttering during gameplay.
- **Legacy 64x32 Support**: Automatically expands legacy 64x32 skins to modern 64x64 format.
- **Hot-Reloading**: Edit or replace a PNG file, and your skin updates in real time in-game without having to restart Minecraft!
- **Bypasses Server & API Limitations**: Custom skins take priority over vanilla Yggdrasil, offline mode, and other skin mods.

---

## 🛠️ Tech Stack & Requirements

- **Minecraft Version**: `1.21.11`
- **Fabric Loader**: `>=0.20.1`
- **Fabric API**: `>=0.141.6+1.21.11`
- **Java**: OpenJDK 21
- **Mappings**: Mojang Official Mappings (`loom.officialMojangMappings()`)

---

## 🚀 Building & Running

### Build JAR
```bash
./gradlew build
```
The compiled mod JAR is placed in `build/libs/`.

### Run in Development
```bash
./gradlew runClient
```
