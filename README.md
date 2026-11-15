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
- `/icerush defaultspots` — admin: restore the generated parking positions
- `/icerush setup` — admin: generate the 90-block circular Blue Ice track and elevated lobby platform
- `/icerush lobbysetup` — admin: create or repair the physical lobby controls
- `/icerush reset` — admin reset

## Current implementation milestone
The Fabric server mod includes the lobby queue, one-time READY votes, the 80% threshold, countdown cancellation when the roster changes, a generated circular arena and elevated lobby, clickable in-world lobby controls, a glass viewing floor and a live TOP 10/last-winner board, boat spawning and seat enforcement, a live sidebar scoreboard, per-player speed BossBars with a five-second grace period and physical 60–100% boat-speed scaling, six turbo zones by default (twelve during Turbo Madness), 5% random round events, eight crack positions with warnings, a 25–40 second ride phase, a 3–2–1 countdown before every round, dynamic parking count, animated temporary parking pads, 20-second parking, elimination effects, winner music and a timed particle celebration. Six achievements are tracked: Prvá výhra, Ice King, Turbo Master, Speed Demon, Survivor and Perfect Parking. Arena setup generates parking positions automatically; `/icerush addspot` can add manual positions. The arena location and dimensions are saved in `config/icerush.json` and restored automatically after restart. Gameplay settings are also stored in `config/icerush.json`; completed-game statistics are stored in `config/icerush-stats.json`, and the last winner is stored in `config/icerush-last-winner.txt`; both survive server restarts.

## Setup
1. Run `/icerush setup` while standing at the intended arena center. This builds the Blue Ice ring and a raised lobby platform.
2. The arena setup creates up to the configured maximum number of candidate parking positions automatically. If you want custom positions, clear them with `/icerush clearspots` and add your own using `/icerush addspot`. Custom parking positions are saved in the config and restored after restart; use `/icerush defaultspots` to restore the generated positions.
3. Players run `/icerush join`, then `/icerush ready`. The game starts after at least 80% have voted READY and enough parking positions exist.
4. Admins can inspect the state with `/icerush status` and stop/reset with `/icerush reset`.

The current feature set covers the main match loop and the major systems described in `ICE RUSH.docx`. The lobby controls are physical clickable interaction points with a chat-menu fallback; the live match scoreboard is shown in the sidebar and TOP 10 is opened from the lobby control. Achievement criteria chosen for this implementation are: Prvá výhra = 1 win; Ice King = 10 wins; Turbo Master = 25 lifetime turbo bonuses; Speed Demon = a speed record at least equal to the configured reference speed; Survivor = 10 lifetime survived rounds; Perfect Parking = a best parking time of 5 seconds or less.

The legacy datapack remains in `datapack/` as a reference and is not loaded by the Fabric mod.
