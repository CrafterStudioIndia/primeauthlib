# PrimeAuthLib

A client-side Fabric mod for **Minecraft 1.21.11** that loads custom player skins directly from an `auth-libskins` folder in the instance directory.

It automatically detects whether a skin is **Slim (Alex)** or **Wide/Normal (Steve)** and applies it in-game, completely bypassing third-party skin APIs, offline-mode limitations, or server-side skin restrictions.

---

## 🎨 How It Works

Upon client startup, the mod automatically creates an `auth-libskins` folder inside your Minecraft instance directory (e.g. `.minecraft/auth-libskins` or your launcher instance folder).

### Skin File Naming:
| File Name | Description |
| :--- | :--- |
| `skin.png` | Applies to **YOUR** local player everywhere (singleplayer & all multiplayer servers). |
| `<username>.png` | Applies to a specific player name (e.g., `Crafter.png`, `Steve.png`). Case-insensitive. |
| `<uuid>.png` | Applies to a specific player UUID (e.g., `069a79f4-44e9-4726-a5be-fca90e38aaf5.png`). |
| `default.png` | Fallback skin for any player who does not have an official or custom skin. |

### Key Features:
- **Automatic Slim / Wide Model Detection**: Inspects the 64x64 texture's unused arm coordinate regions (and handles HD square skins). If transparency is found in the 3-pixel arm gap, it sets the model to `SLIM` (Alex); otherwise `WIDE` (Steve).
- **Legacy 64x32 Support**: Automatically expands legacy 64x32 skins to 64x64 format with proper arm and leg copying.
- **Hot-Reloading**: Edit or replace a PNG file, and your skin updates in real time in-game without having to restart Minecraft!
- **Bypasses Server Limitations**: Mixins directly into `PlayerInfo` and `AbstractClientPlayer`, ensuring custom skins take priority over vanilla Yggdrasil, offline mode, and other skin mods.
- **Client Command**:
  - `/authlibskins`: Prints the active skins folder path in chat.
  - `/authlibskins reload`: Forces an immediate reload and cache clear of all skins.

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
