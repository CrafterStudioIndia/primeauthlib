# Changelog

All notable changes to **PrimeAuthLib** will be documented in this file.

## [1.0.0] - 2026-10-25

### Initial Release for Minecraft 1.21.11 (Fabric)

#### 🌟 Features
- **Local Skin Folder**: Automatically creates and monitors `auth-libskins/` in the instance directory.
- **Slim (Alex) vs Wide (Steve) Auto-Detection**: Dynamically checks alpha transparency in arm coordinate regions (supports 64x64, HD square skins, and auto-converts legacy 64x32 skins).
- **In-Game Settings GUI**:
  - Interactive 3D rotatable player skin model preview widget.
  - Model type toggle (Auto-Detect / Slim / Wide).
  - One-click native OS folder opener for `auth-libskins/`.
  - Instant hot-reload button.
- **In-Game Authlib Injector**:
  - Direct bytecode hooks into `YggdrasilMinecraftSessionService` (`getPackedTextures`, `unpackTextures`, `fetchProfile`, `hasJoinedServer`).
  - Bypasses Mojang's `TextureUrlChecker` domain restrictions (`.minecraft.net` / `.mojang.com`).
  - Native disk loading in `SkinTextureDownloader` for `file://` URIs without HTTP connection overhead.
  - Built-in GUI configuration for remote Authlib-Injector API servers with one-click presets for **LittleSkin** and **Ely.by**.
- **Full Offline & Cracked Support**:
  - Deterministic offline UUID calculation (`nameUUIDFromBytes`).
  - Single-file auto-binding for offline accounts without requiring file renaming.
- **Client & GUI Mod Compatibility**:
  - Global hotkey: **Left Shift + K** (works in world and in menus; safely skips text boxes).
  - Client chat commands: `/skin`, `/authlibskin`, `/primeskin`, `/authlibskins reload`.
  - ModMenu integration via `PrimeAuthLibModMenu` (`ModMenuApi`).
  - Smart collision avoidance in `OptionsScreen` that detects occupied button coordinates and shifts automatically.
  - Fabric `ScreenEvents` hooks for `SkinCustomizationScreen`.
  - High-priority Mixins (`priority = 1500`) to cleanly override third-party skin mods when custom skins are loaded.
