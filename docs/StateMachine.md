# ICE RUSH State Machine

States:

IDLE
 -> WAITING
 -> COUNTDOWN
 -> ROUND_START
 -> RACING
 -> PARKING
 -> ELIMINATION
 -> NEXT_ROUND
 -> GAME_END
 -> RESET

Rules:
- Only one active state.
- Every tick executes only the current state's logic.
- Modules communicate through scoreboard/storage values rather than duplicated checks.
- Expensive operations occur only on state transitions.

This minimizes per-tick cost and makes the datapack easier to debug and extend.
