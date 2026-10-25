# PrimeAuthLib

[![Minecraft 1.21.11](https://img.shields.io/badge/Minecraft-1.21.11-brightgreen.svg)](https://minecraft.net/)
[![Fabric Loader](https://img.shields.io/badge/Fabric-0.20.1+-blue.svg)](https://fabricmc.net/)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![License MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**PrimeAuthLib** is a lightweight, high-performance client-side Fabric mod for **Minecraft 1.21.11** that loads custom skins directly from an `auth-libskins` folder in your instance directory.

It features **Slim (Alex) vs Wide (Steve) auto-detection**, an interactive **3D player model preview GUI**, and a **built-in in-game Authlib Injector** that hooks directly into Mojang's session services—completely bypassing third-party skin API limitations, offline-mode restrictions, and server-side skin limits without requiring any external `-javaagent`!

---

## ✨ Features

- 📁 **Dedicated Skins Folder (`auth-libskins/`)**:
  - Automatically created in your Minecraft instance directory.
  - Simply drop any `.png` skin file into `auth-libskins/` to use it immediately.
- 👤 **Intelligent Model Auto-Detection**:
  - Automatically detects whether a skin uses **Slim (Alex / 3-pixel arms)** or **Wide (Steve / 4-pixel arms)** by inspecting unused arm texture coordinates.
  - Supports standard 64x64 textures, legacy 64x32 textures (automatically upgraded), and high-resolution (HD) square skins.
  - Override option (Auto / Slim / Wide) available in the GUI.
- 🔓 **Built-In In-Game Authlib Injector**:
  - Directly hooks `com.mojang.authlib` session verification and texture unpacking in-game.
  - Bypasses Mojang's strict `.minecraft.net` / `.mojang.com` domain check (`TextureUrlChecker`).
  - Seamlessly integrates with remote **Authlib-Injector API servers** (e.g., **LittleSkin**, **Ely.by**, **Blessing Skin**) with quick presets in the GUI.
  - No external JVM flags or `-javaagent` required!
- 🌐 **Complete Offline & Online Support**:
  - Fully supports offline (cracked) accounts and servers via vanilla deterministic UUID calculation (`UUID.nameUUIDFromBytes("OfflinePlayer:" + name)`).
  - Single-file auto-binding: If you drop a single `.png` skin in `auth-libskins/`, it automatically binds to your local player without requiring exact file renaming.
- 🔄 **Real-Time Hot-Reloading**:
  - Edit, save, or replace a PNG file on disk and see the changes in-game immediately without restarting your client!
- 🖥️ **Interactive 3D Preview Settings Screen**:
  - Rotatable 3D player model preview inside Minecraft.
  - One-click button to open the `auth-libskins` folder in your native OS file manager (Windows, macOS, Linux).
  - Model mode switcher (Auto-Detect / Slim / Wide) and Authlib Injector server configuration.
- 🎮 **Client & GUI Mod Compatibility**:
  - **Left Shift + K**: Hotkey to open the settings GUI anywhere (in-game or in menus; safely ignores textboxes).
  - **Chat Commands**: Type `/skin`, `/authlibskin`, or `/primeskin` to open the GUI; `/authlibskins reload` to force a reload.
  - **ModMenu Integration**: Built-in config screen provider in the ModMenu mods list.
  - **Adaptive Options Button**: Smart collision detection prevents overlapping with other GUI overhaul mods (Sodium, Bedrockify, FancyMenu).
  - **Skin Mod Friendly**: High-priority mixins (`1500`) ensure your skins load reliably alongside mods like 3D Skin Layers, Sodium, and Iris.

---

## 🎨 Skin File Naming Conventions

Place your `.png` skin files into the `auth-libskins/` directory in your `.minecraft` instance:

| File Name | Description |
| :--- | :--- |
| `skin.png` | **Default Local Player Skin**: Applies to your player everywhere (singleplayer, online servers, offline/cracked servers). |
| `<username>.png` | **Username Match**: Applies to a specific player name (e.g. `Crafter.png`, `Steve.png`). Case-insensitive. |
| `<uuid>.png` | **UUID Match**: Applies to a specific player UUID (with or without dashes; online or offline UUID). |
| `default.png` | **Fallback Skin**: Fallback skin for players who do not have an official or custom skin. |

> [!TIP]
> **Zero-Config for Offline Accounts**: If you drop only a single `.png` skin file into `auth-libskins/`, the mod automatically binds it to your local offline account without needing to rename it!

---

## ⌨️ Accessing the In-Game GUI

You can open the **"Auth-Lib Skin"** settings screen in any of the following ways:

1. **Hotkey**: Press **`Left Shift + K`** anywhere in-game or in menus. (Can also be customized in **Options -> Controls -> Key Binds -> PrimeAuthLib**).
2. **Chat Commands**: Run `/skin`, `/authlibskin`, or `/primeskin`.
3. **Options Menu**: Click **"Auth-Lib Skin..."** at the bottom of the vanilla **Options** screen.
4. **Skin Customization**: Click **"Auth-Lib Skin..."** inside **Options -> Skin Customization...**.
5. **ModMenu**: Click the config button next to **PrimeAuthLib** in the ModMenu list.

---

## 🌐 In-Game Authlib Injector Setup

If you use an Authlib-Injector authentication server (such as LittleSkin, Ely.by, or your own private Yggdrasil server):

1. Open the settings GUI (**Left Shift + K** or `/skin`).
2. Click **"Authlib Server..."**.
3. Select a preset (**Preset: LittleSkin** or **Preset: Ely.by**) or enter your custom API root URL (e.g., `https://example.com/api/yggdrasil`).
4. Click **Save**. Your configuration is saved in `auth-libskins/config.properties`.

*(Leave the URL blank if you only use local skins—local injection is active by default!)*

---

## 💻 Compatibility

| Mod / Client | Compatibility Status | Notes |
| :--- | :---: | :--- |
| **Sodium / Embeddium** | ✅ Compatible | Adaptive button positioning prevents GUI overlaps. |
| **Iris Shaders** | ✅ Compatible | Skin textures work seamlessly with shader pipelines. |
| **ModMenu** | ✅ Compatible | Built-in `ModMenuApi` config screen provider. |
| **3D Skin Layers** | ✅ Compatible | Voxel layer extrusion reads custom skin textures properly. |
| **FancyMenu / Bedrockify** | ✅ Compatible | Dedicated keybind (`Left Shift + K`) and chat commands bypass menu overrides. |
| **Custom Clients (Lunar, Badlion, Feather, Prism)** | ✅ Compatible | High-priority mixins ensure textures apply across all environments. |

---

## 🛠️ Building from Source

### Requirements
- **JDK 21** or newer
- Git

### Build Instructions
```bash
git clone https://github.com/craftervictus/primeauthlib.git
cd primeauthlib
./gradlew build
```

The compiled mod JAR will be located in `build/libs/primeauthlib-1.0.0.jar`.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
