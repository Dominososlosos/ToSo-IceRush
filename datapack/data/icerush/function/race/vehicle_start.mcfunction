# ICE RUSH vehicle start
# Gives each active player a boat vehicle at their current start position.

execute as @a[scores={ir.finished=0}] at @s run summon boat ~ ~ ~ {Tags:["icerush.vehicle"]}
execute as @a[scores={ir.finished=0}] at @s run ride @s mount @e[type=boat,tag=icerush.vehicle,distance=..2,limit=1,sort=nearest]
