# PrimeAuthLib

A Fabric mod / library for **Minecraft 1.21.11**.

## Features & Tech Stack
- **Minecraft Version**: `1.21.11`
- **Fabric Loader**: `0.20.1`
- **Fabric Loom**: `1.19-SNAPSHOT`
- **Fabric API**: `0.141.6+1.21.11`
- **Java**: Java 21
- **Mappings**: Mojang Official Mappings (`loom.officialMojangMappings()`)
- **Split Source Sets**: Main and Client separated (`src/main` and `src/client`)

## Development Setup

### Requirements
- JDK 21 or newer

### Building
To build the mod jar:
```bash
./gradlew build
```
Output jar will be located in `build/libs/`.

### Running in Development
- Run Minecraft Client:
  ```bash
  ./gradlew runClient
  ```
- Run Minecraft Server:
  ```bash
  ./gradlew runServer
  ```

### IDE Setup
- **IntelliJ IDEA**: Open the project folder and let Gradle sync. Run `./gradlew genSources` if needed to decompile Minecraft sources.
- **VS Code**: Install the Extension Pack for Java.

