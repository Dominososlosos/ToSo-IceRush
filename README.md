# ICE RUSH

ICE RUSH is being migrated from the original datapack prototype to a Fabric server mod.

## Target
- Minecraft Java Edition 1.21.1
- Fabric Loader 0.19.1
- Fabric API 0.116.18+1.21.1
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
- `/icerush setup` — admin: generate the 90-block circular Blue Ice track and elevated lobby platform
- `/icerush reset` — admin reset

## Current implementation milestone
The Fabric server mod currently includes the lobby commands, one-time READY votes, the 80% threshold, countdown cancellation when the roster changes, a generated circular arena, basic boat spawning/seat enforcement, a 25–40 second ride phase, 20-second parking, elimination, and survival rounds. Parking positions still need to be configured by an administrator with `/icerush addspot`.

## Setup
1. Run `/icerush setup` while standing at the intended arena center. This builds the Blue Ice ring and a raised lobby platform.
2. Add parking positions by standing at each parking center and running `/icerush addspot`. Configure at least `players - 1` positions for the lobby roster.
3. Players run `/icerush join`, then `/icerush ready`. The game starts after at least 80% have voted READY and enough parking positions exist.
4. Admins can inspect the state with `/icerush status` and stop/reset with `/icerush reset`.

This is an active development build, not yet feature-complete against `ICE RUSH.docx`. Speed tiers/BossBar, turbo zones, random events, cracks, full lobby UI, persistent statistics/leaderboards, achievements, and the full parking visuals are still pending.

The legacy datapack remains in `datapack/` as a reference and is not loaded by the Fabric mod.
