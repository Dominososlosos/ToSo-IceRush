# ICE RUSH race reset
# Resets round-specific runtime state for a clean next round.

kill @e[type=boat,tag=icerush.vehicle]
scoreboard players set @a ir.cp 0
scoreboard players set @a ir.lap 0
scoreboard players set @a ir.finished 0
scoreboard players set #round ir.round 0
scoreboard players set #timer ir.timer 0
scoreboard players set #event ir.event 0
scoreboard players set #temp ir.temp 0
scoreboard players set #checkpoint ir.temp 0
scoreboard players set #speed ir.speed 0
scoreboard players set #maxspeed ir.maxspeed 0
