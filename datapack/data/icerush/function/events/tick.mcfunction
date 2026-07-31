# ICE RUSH events tick
# Handles runtime event evaluation during the running phase.

scoreboard players set #temp ir.temp 0
execute if score #event ir.event matches 0 run function icerush:events/random
execute if score #event ir.event matches 1 run function icerush:events/snow
execute if score #event ir.event matches 2 run function icerush:events/wind
execute if score #event ir.event matches 3 run function icerush:events/fog
execute if score #event ir.event matches 4 run function icerush:events/slippery
execute if score #event ir.event matches 5 run function icerush:events/turbo_madness
