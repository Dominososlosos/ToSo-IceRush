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
- `/icerush ready` — cast your one-time READY vote
- `/icerush leave` — leave the lobby
- `/icerush status` — show game status
- `/icerush stats` — show your persistent statistics
- `/icerush top` — show the TOP 10 leaderboard
- `/icerush achievements` — show unlocked achievements
- `/icerush lastwinner` — show the last completed game's winner
- `/icerush rules` — display the game rules
- `/icerush start` — admin start (requires 80% ready)
- `/icerush addspot` — admin: save a parking location at your current position
- `/icerush clearspots` — admin: remove configured parking locations
- `/icerush setup` — admin: generate the 90-block circular Blue Ice track and elevated lobby platform
- `/icerush reset` — admin reset

## Current implementation milestone
The Fabric server mod currently includes the lobby commands, one-time READY votes, the 80% threshold, countdown cancellation when the roster changes, a generated circular arena and elevated lobby, boat spawning and seat enforcement, per-player speed BossBars with a five-second grace period, six turbo zones by default (twelve during Turbo Madness), 5% random round events, eight crack positions with warnings, a 25–40 second ride phase, a 3–2–1 countdown before every round, dynamic parking count, temporary visible parking pads with snow particles, 20-second parking, elimination effects, winner celebration particles/sound, and survival rounds. Arena setup generates parking positions automatically; `/icerush addspot` can add manual positions. Gameplay settings are generated in `config/icerush.json`; completed-game statistics are stored in `config/icerush-stats.json`, and the last winner is stored in `config/icerush-last-winner.txt`; both survive server restarts.

## Setup
1. Run `/icerush setup` while standing at the intended arena center. This builds the Blue Ice ring and a raised lobby platform.
2. The arena setup creates up to the configured maximum number of candidate parking positions automatically. If you want custom positions, clear them with `/icerush clearspots` and add your own using `/icerush addspot`.
3. Players run `/icerush join`, then `/icerush ready`. The game starts after at least 80% have voted READY and enough parking positions exist.
4. Admins can inspect the state with `/icerush status` and stop/reset with `/icerush reset`.

This is an active development build, not yet feature-complete against `ICE RUSH.docx`. The full 100–60% speed-penalty algorithm, full parking animation, lobby buttons/rules, achievement criteria beyond First Win, and winner title/music/confetti effects are still pending. Persistent statistics, a command-based TOP 10, a First Win achievement, and editable gameplay configuration are implemented. The current speed BossBar shows red/yellow/green movement stages; the precise penalty thresholds have not been invented because the specification does not define them.

The legacy datapack remains in `datapack/` as a reference and is not loaded by the Fabric mod.
