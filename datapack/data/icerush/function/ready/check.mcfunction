# ICE RUSH ready check
# Verifies if enough players are ready to start the countdown.

scoreboard players set #temp ir.temp 0
execute if score #players ir.players matches 2..16 run scoreboard players set #temp ir.temp 1
execute if score #temp ir.temp matches 1 run function icerush:ready/tick
