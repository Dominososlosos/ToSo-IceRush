# ICE RUSH State Machine

The Fabric implementation uses five authoritative states:

```text
WAITING
  -> COUNTDOWN
  -> RACING
  -> PARKING
  -> COUNTDOWN (next round)
  -> FINISHED
  -> WAITING
```

## State responsibilities

- **WAITING** — player queue, one-time READY votes, 80% threshold and lobby UI.
- **COUNTDOWN** — spawn/seat boats, lock movement, validate the lobby countdown if the roster changes, then start all boats together.
- **RACING** — random 25–40 second ride, speed tiers and physical speed scaling, turbo zones, one optional random event and crack warnings.
- **PARKING** — animate parking pads, count down, lock each occupied spot, eliminate players without a spot, and award parking/survival points.
- **FINISHED** — commit statistics once, play winner effects, then return players to the lobby.

Administrator reset is an immediate transition to WAITING. It does not commit statistics or update the last winner.

Late spectators are kept outside the active participant list until a round boundary. They are admitted only while the active-player and parking-capacity limits permit it.
