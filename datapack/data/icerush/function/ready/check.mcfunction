# ICE RUSH ready check
# Verifies if enough players are ready to start the countdown.

execute if score #players ir.players matches 2.. run scoreboard players set #temp ir.temp 1
