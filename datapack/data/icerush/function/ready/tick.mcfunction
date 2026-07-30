# ICE RUSH ready tick
# Handles ready-state evaluation and transitions.

function icerush:ready/check
execute if score #temp ir.temp matches 1 run function icerush:countdown/start
