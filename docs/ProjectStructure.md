# Project Structure

/datapack
  /data/icerush/function
    admin/
    arena/
    config/
    core/
    countdown/
    elimination/
    events/
    game/
    hud/
    join/
    parking/
    player/
    race/
    speed/
    statistics/
    turbo/
    winner/

Design principles:
- One responsibility per function.
- Shared utilities in core.
- No duplicated logic.
- Schedule over heavy tick loops where possible.
- Storage for persistent game data.
- Scoreboards only for values that need fast access.
