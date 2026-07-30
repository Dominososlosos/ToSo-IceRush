# ICE RUSH countdown tick
# Handles the active countdown timer.

scoreboard players remove #timer ir.timer 1
execute if score #timer ir.timer matches 15 run scoreboard players set #temp ir.temp 15
execute if score #timer ir.timer matches 10 run scoreboard players set #temp ir.temp 10
execute if score #timer ir.timer matches 5 run scoreboard players set #temp ir.temp 5
execute if score #timer ir.timer matches 4 run scoreboard players set #temp ir.temp 4
execute if score #timer ir.timer matches 3 run scoreboard players set #temp ir.temp 3
execute if score #timer ir.timer matches 2 run scoreboard players set #temp ir.temp 2
execute if score #timer ir.timer matches 1 run scoreboard players set #temp ir.temp 1
execute if score #timer ir.timer matches ..0 run function icerush:countdown/finish
