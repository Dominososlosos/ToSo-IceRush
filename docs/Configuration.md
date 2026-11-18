# ICE RUSH configuration

The Fabric mod creates `config/icerush.json` on the first server start. Edit the file while the server is stopped, then restart the server to apply changes.

| Setting | Default | Purpose |
|---|---:|---|
| `minPlayers` | 2 | Minimum players needed to start |
| `maxPlayers` | 16 | Maximum active participants |
| `countdownSeconds` | 3 | Start countdown |
| `minRideSeconds` | 25 | Minimum ride phase |
| `maxRideSeconds` | 40 | Maximum ride phase |
| `parkingSeconds` | 20 | Parking phase duration |
| `finishHoldSeconds` | 8 | Winner display duration |
| `lobbyHeight` | 80 | Lobby height above the track when first generated |
| `trackInnerRadius` | 38.0 | Inner radius of the Blue Ice ring |
| `trackOuterRadius` | 45.0 | Outer radius of the Blue Ice ring |
| `turboZones` | 6 | Turbo zone count |
| `turboDurationSeconds` | 2 | Turbo boost duration |
| `turboBonusPoints` | 2 | Points per turbo activation |
| `eventChancePercent` | 5 | Chance of exactly one random event per round |
| `crackChancePercent` | 10 | Chance per fixed crack position |
| `crackWarningSeconds` | 2 | Warning before a crack opens |
| `speedGraceSeconds` | 5 | Time before speed evaluation starts |
| `minimumSpeedPercent` | 60 | Lowest allowed speed tier (percent) |
| `speedReference` | 0.30 | Horizontal boat velocity mapped to the 100% tier |
| `parkingRadius` | 2.5 | Radius used to detect a parked boat |

Values are clamped to safe ranges when the config is loaded. Speed is evaluated in nine 5% steps from 60% to 100%; the selected tier is also applied to the boat's horizontal velocity. Turbo can activate only at 80% or above.

## Saved arena fields

After `/icerush setup`, the arena dimension, center, inner/outer radii and lobby height are saved separately. Editing `trackInnerRadius`, `trackOuterRadius` or `lobbyHeight` later does not resize an existing arena. The arena is intentionally generated only once. To rebuild it, stop the server, back up the world, and set `arenaConfigured` to `false` in the config before starting again.

Custom parking positions are stored in the same config file. Use `/icerush clearspots` and `/icerush addspot` to define them, or `/icerush defaultspots` to restore generated positions.

Completed-game statistics are saved separately in `config/icerush-stats.json`; the last winner is stored in `config/icerush-last-winner.txt`. An administrator reset does not count as a completed game and does not save that round's results.
