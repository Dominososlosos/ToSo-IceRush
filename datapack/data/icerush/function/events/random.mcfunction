# ICE RUSH random event dispatcher
# Handles selection of a random event during the running phase.

scoreboard players set #temp ir.temp 0
scoreboard players set #event ir.event 0
execute if score #round ir.round matches 1.. run scoreboard players set #event ir.event 1
execute if score #round ir.round matches 2.. run scoreboard players set #event ir.event 2
execute if score #round ir.round matches 3.. run scoreboard players set #event ir.event 3
execute if score #round ir.round matches 4.. run scoreboard players set #event ir.event 4
execute if score #round ir.round matches 5.. run scoreboard players set #event ir.event 5
