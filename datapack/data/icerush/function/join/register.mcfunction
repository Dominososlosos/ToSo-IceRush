# ICE RUSH join register
# Called when a player joins the lobby.

scoreboard players add #players ir.players 1
execute if score #players ir.players matches 1 run function icerush:join/enable
function icerush:ui/lobby
execute if score #players ir.players matches 2..16 run function icerush:ready/check
