# ICE RUSH checkpoint completion
# Completing checkpoint 4 completes the current lap.

scoreboard players set @s ir.cp 0
scoreboard players add @s ir.lap 1

title @s actionbar {"text":"Kolo dokončené!"}

execute if score @s ir.lap matches 4 run scoreboard players set @s ir.finished 1
execute if score @s ir.lap matches 4 run scoreboard players add @s ir.wins 1
execute if score @s ir.lap matches 4 run function icerush:finish/start
