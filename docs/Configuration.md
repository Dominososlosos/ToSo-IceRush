# ICE RUSH configuration

The Fabric mod creates `config/icerush.json` on the first server start. Edit the file while the server is stopped, then restart the server to apply changes.

| Setting | Default | Purpose |
|---|---:|---|
| `minPlayers` | 2 | Minimum players needed to start |
| `maxPlayers` | 16 | Maximum participants |
| `countdownSeconds` | 3 | Start countdown |
| `minRideSeconds` | 25 | Minimum ride phase |
| `maxRideSeconds` | 40 | Maximum ride phase |
| `parkingSeconds` | 20 | Parking phase duration |
| `finishHoldSeconds` | 8 | Winner display duration |
| `lobbyHeight` | 80 | Lobby height above the track |
| `trackInnerRadius` | 38.5 | Inner radius of the Blue Ice ring |
| `trackOuterRadius` | 45.0 | Outer radius of the Blue Ice ring |
| `turboZones` | 6 | Turbo zone count |
| `turboDurationSeconds` | 2 | Turbo boost duration |
| `turboBonusPoints` | 2 | Points per turbo activation |
| `eventChancePercent` | 5 | Chance of one random event per round |
| `crackChancePercent` | 10 | Chance per fixed crack position |
| `crackWarningSeconds` | 2 | Warning before a crack opens |
| `speedGraceSeconds` | 5 | Time before speed evaluation starts |
| `minimumSpeedPercent` | 60 | Configured lower speed bound |
| `speedReference` | 0.30 | Reference horizontal boat velocity |
| `parkingRadius` | 2.5 | Radius used to detect a parked boat |

Values are clamped to safe ranges when the config is loaded. Some settings are already used by the game loop; the minimum-speed percentage and speed reference are reserved for the remaining speed-penalty implementation and do not yet change boat physics.

Completed-game statistics are saved separately in `config/icerush-stats.json`. An administrator reset does not count as a completed game and does not save that round's results.
