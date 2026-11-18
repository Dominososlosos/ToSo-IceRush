# ICE RUSH core tick
# Dispatches the current game state.

execute if score #game ir.state matches 0 run function icerush:state/idle
execute if score #game ir.state matches 1 run function icerush:state/lobby
execute if score #game ir.state matches 2 run function icerush:state/countdown
execute if score #game ir.state matches 3 run function icerush:state/running
execute if score #game ir.state matches 4 run function icerush:state/parking
execute if score #game ir.state matches 5 run function icerush:state/elimination
execute if score #game ir.state matches 6 run function icerush:state/winner
