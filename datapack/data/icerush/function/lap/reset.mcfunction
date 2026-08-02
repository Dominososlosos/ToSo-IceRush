# ICE RUSH lap reset
# Resets lap-related runtime state after a race segment.

scoreboard players set #temp ir.temp 0
scoreboard players set #lap ir.round 0
function icerush:checkpoint/tick
