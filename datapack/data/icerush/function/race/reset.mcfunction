# ICE RUSH race reset
# Resets the active race round back to lobby-ready defaults.

scoreboard players set #round ir.round 0
scoreboard players set #temp ir.temp 0
scoreboard players set #event ir.event 0
function icerush:ui/lobby
