# ICE RUSH lobby UI
# Displays lobby-related information and hooks for scoreboard/bossbar updates.

scoreboard players set #temp ir.temp 0
execute if score #players ir.players matches 0 run scoreboard players set #temp ir.temp 0
execute if score #players ir.players matches 1..16 run scoreboard players set #temp ir.temp 1
