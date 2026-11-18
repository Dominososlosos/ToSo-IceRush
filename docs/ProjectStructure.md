# Project Structure

The active Fabric mod is organized as follows:

```
src/main/java/sk/toso/icerush/
  IceRushMod.java
  IceRushGame.java
  IceRushConfig.java
  IceRushStatsStore.java

src/main/resources/
  fabric.mod.json

datapack/
  data/icerush/function/   # legacy prototype/reference, not loaded by the mod

docs/
  Architecture.md
  Configuration.md
  ProjectStructure.md
  StateMachine.md
  TestingChecklist.md
```

Design principles:
- Keep game state and participant state centralized.
- Persist arena configuration separately from per-match state.
- Do not save statistics for administrator-aborted matches.
- Use scheduled HUD/speed updates and avoid scanning crack positions when none are active.
- Keep admin setup and parking-position management separate from player-facing match controls.
