# ICE RUSH ready disable
# Marks the lobby as not ready to start checking the countdown.

scoreboard players set #game ir.state 1
scoreboard players set #temp ir.temp 0
function icerush:ui/lobby
