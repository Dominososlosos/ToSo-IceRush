# ICE RUSH countdown cancel
# Cancels the active countdown and returns to lobby.

scoreboard players set #game ir.state 1
scoreboard players set #timer ir.timer 0
function icerush:ui/lobby
