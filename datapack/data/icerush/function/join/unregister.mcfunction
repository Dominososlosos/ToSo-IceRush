# ICE RUSH join unregister
# Called when a player leaves the lobby.

scoreboard players remove #players ir.players 1
execute if score #players ir.players matches ..0 run scoreboard players set #players ir.players 0
function icerush:ui/lobby
execute if score #players ir.players matches 0 run function icerush:join/disable
