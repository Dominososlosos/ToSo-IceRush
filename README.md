# ICE RUSH

ICE RUSH is a server-only Fabric mod for Minecraft 1.20.1. Players join with a normal, unmodified Minecraft 1.20.1 client. The original datapack prototype is retained under `datapack/` as a reference and is not loaded by the mod.

## Target
- Minecraft Java Edition 1.20.1
- Fabric Loader 0.16.10
- Fabric API 0.92.7+1.20.1
- Java 17

## Build
Build with Gradle 8.12+ running on JDK 21. The generated mod targets Java 17, so the dedicated server itself can run on Java 17 or newer:

```bash
gradle build
```

The mod JAR is created in `build/libs/`.

## Installation

1. Use a Minecraft Java 1.20.1 dedicated server with Fabric Loader 0.16.10 and Java 17 or newer.
2. Install Fabric API 0.92.7+1.20.1 in the server `mods` folder. Players do not install ICE RUSH or Fabric API on their clients.
3. Copy the ICE RUSH JAR from `build/libs/` into the same `mods` folder.
4. Start the server and run `/icerush setup` as an administrator at the intended arena center.
5. If the lobby controls or results board are missing on an existing arena, run `/icerush lobbysetup`.

## CI verification

GitHub Actions builds the mod and runs a dedicated Fabric 1.20.1 server smoke test. The smoke test verifies that the mod loads, the `/icerush status` command is registered, and the server configuration is created. It does not replace a real multiplayer gameplay test.

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
