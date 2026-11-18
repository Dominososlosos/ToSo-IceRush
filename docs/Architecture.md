# ICE RUSH Architecture

The active implementation is a Fabric server mod for Minecraft Java 1.20.1. The original datapack prototype is retained under `datapack/` as a reference; it is not loaded by the mod.

## Runtime components

- `IceRushMod`: registers commands, server tick/lifecycle hooks, player join/disconnect hooks and lobby interaction handling.
- `IceRushGame`: owns the match state, participants, boats, BossBars, arena, parking, cracks, turbo zones, events, score and lobby controls.
- `IceRushConfig`: loads, validates and persists gameplay and arena configuration.
- `IceRushStatsStore`: persists completed-game player statistics and the last winner.

## Match states

- `WAITING`: queue, READY votes and lobby controls.
- `COUNTDOWN`: boats are spawned and locked before the simultaneous start.
- `RACING`: speed tiers, turbo, cracks and the selected round event are processed.
- `PARKING`: parking pads animate in, spots lock on occupation and unparked players are eliminated.
- `FINISHED`: winner presentation, statistics commit and return-to-lobby delay.

## Performance approach

- Match-state logic runs only while a match is active.
- Speed and HUD updates are scheduled at lower frequencies than the seat/parking checks that require immediate response.
- Crack timers are processed only when warning/open cracks exist.
- Arena construction happens once and its geometry is persisted.
- Stats are written only when a match completes normally; administrator reset discards the in-memory round results.
