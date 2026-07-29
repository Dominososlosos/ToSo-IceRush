# ICE RUSH load
# Initializes scoreboards, storage, and persistent state.

data modify storage icerush:config max_players set value 16
data modify storage icerush:config ready_percent set value 80
data modify storage icerush:config parking_min_ticks set value 500
data modify storage icerush:config parking_max_ticks set value 800
data modify storage icerush:config parking_duration_ticks set value 400
data modify storage icerush:config grace_ticks set value 100
data modify storage icerush:config speed_min_percent set value 60
data modify storage icerush:config speed_step_percent set value 5
data modify storage icerush:config turbo_bonus_percent set value 5
data modify storage icerush:config turbo_duration_ticks set value 40
data modify storage icerush:config event_chance_percent set value 5
data modify storage icerush:config crack_chance_percent set value 10

scoreboard objectives add ir.state dummy
scoreboard objectives add ir.timer dummy
scoreboard objectives add ir.round dummy
scoreboard objectives add ir.players dummy
scoreboard objectives add ir.ready dummy
scoreboard objectives add ir.speed dummy
scoreboard objectives add ir.maxspeed dummy
scoreboard objectives add ir.points dummy
scoreboard objectives add ir.wins dummy
scoreboard objectives add ir.losses dummy
scoreboard objectives add ir.games dummy
scoreboard objectives add ir.parked dummy
scoreboard objectives add ir.event dummy
scoreboard objectives add ir.crack dummy
scoreboard objectives add ir.vote dummy
scoreboard objectives add ir.temp dummy

scoreboard players set #game ir.state 0
scoreboard players set #round ir.round 0
scoreboard players set #players ir.players 0
scoreboard players set #ready ir.ready 0
scoreboard players set #timer ir.timer 0
scoreboard players set #temp ir.temp 0

bossbar add icerush:lobby "ICE RUSH"
bossbar add icerush:speed "Rychlost"
bossbar add icerush:parking "Parkovanie"
