# ICE RUSH scoreboard update
# Central place for future scoreboard and HUD refresh logic.

scoreboard players set #temp ir.temp 0
scoreboard players operation #temp ir.temp = #round ir.round
scoreboard players operation #temp ir.temp += #players ir.players
