# ICE RUSH

ICE RUSH is being migrated from the original datapack prototype to a Fabric server mod.

## Target
- Minecraft Java Edition 1.21.1
- Fabric Loader 0.19.1
- Fabric API 0.116.12+1.21.1
- Java 21

## Build
Use Gradle 8.12+ with Java 21:

```bash
gradle build
```

The mod JAR is created in `build/libs/`.

## Commands
- `/icerush join` — join the lobby
- `/icerush ready` — toggle ready status
- `/icerush leave` — leave the lobby
- `/icerush status` — show game status
- `/icerush start` — admin start (requires 80% ready)
- `/icerush addspot` — admin: save a parking location at your current position
- `/icerush clearspots` — admin: remove configured parking locations
- `/icerush reset` — admin reset

## Current implementation milestone
The Fabric server mod now has a working lobby, 80% ready threshold, 3-second countdown, random 25–40 second ride phase, 20-second boat parking phase, elimination, endless survival rounds, and last-player-standing winner state.

The legacy datapack remains in `datapack/` as a reference and is not loaded by the Fabric mod.
