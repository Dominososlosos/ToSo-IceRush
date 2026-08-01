# ICE RUSH join tick
# Handles players waiting in the lobby and updating join-related UI.

execute if score #game ir.state matches 1 run function icerush:ui/lobby
execute if score #game ir.state matches 1 run function icerush:join/register
execute if score #game ir.state matches 1 run function icerush:ready/check
execute if score #game ir.state matches 0 run function icerush:join/disable
