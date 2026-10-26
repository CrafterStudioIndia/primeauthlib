# Changelog (PrimeAuthLib - Minecraft 26.3)

All notable changes to **PrimeAuthLib** for Minecraft 26.3 will be documented in this file.

## [1.0.0+26.3] - 2026-10-25

### Port to Minecraft 26.3 (Wilderness Bound)

#### 🌟 Features
- **Minecraft 26.3 Target**: Compatible with Fabric Loader >=0.19.5, Fabric API 0.165.0+26.3, and Java 21+.
- **Dedicated `auth-libskins/` Directory**: Instance-level zero-config skin loading.
- **Slim vs Wide Auto-Detection**: Supports standard 64x64, 64x32 conversion, and HD square skins.
- **Interactive 3D Skin Preview Screen**: Rotatable player model widget and folder opener.
- **In-Game Authlib Injector**:
  - Direct session service hooks into `YggdrasilMinecraftSessionService`.
  - Removes Mojang `TextureUrlChecker` domain restrictions.
  - Native disk loading for local `file://` skin URIs.
  - Built-in remote server configuration with LittleSkin and Ely.by presets.
- **Offline & Cracked Support**: Automatic offline UUID calculation and single-file auto-binding.
- **Mod & Client Compatibility**:
  - Global hotkey: **Left Shift + K**.
  - Client commands: `/skin`, `/authlibskin`, `/primeskin`, `/authlibskins reload`.
  - ModMenu config provider (`ModMenuApi`).
  - Adaptive options button with overlap avoidance.
  - High-priority Mixins (`priority = 1500`).

