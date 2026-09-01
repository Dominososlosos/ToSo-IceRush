# ICE RUSH checkpoint tick
# Players must pass checkpoints in order. Checkpoint markers are placed with checkpoint/set1..set4.

execute as @a[scores={ir.finished=0}] at @s if score @s ir.cp matches 0 if entity @e[type=marker,tag=icerush.cp1,distance=..2,limit=1] run scoreboard players set @s ir.cp 1
execute as @a[scores={ir.finished=0}] at @s if score @s ir.cp matches 1 if entity @e[type=marker,tag=icerush.cp2,distance=..2,limit=1] run scoreboard players set @s ir.cp 2
execute as @a[scores={ir.finished=0}] at @s if score @s ir.cp matches 2 if entity @e[type=marker,tag=icerush.cp3,distance=..2,limit=1] run scoreboard players set @s ir.cp 3
execute as @a[scores={ir.finished=0}] at @s if score @s ir.cp matches 3 if entity @e[type=marker,tag=icerush.cp4,distance=..2,limit=1] run function icerush:checkpoint/complete
