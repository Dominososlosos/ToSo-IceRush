# ICE RUSH race start
# Begins an active race round and initializes every player in the race.

scoreboard players add #round ir.round 1
scoreboard players set @a ir.cp 0
scoreboard players set @a ir.lap 1
scoreboard players set @a ir.finished 0
function icerush:speed/start
function icerush:checkpoint/start
function icerush:events/random
function icerush:race/vehicle_start
scoreboard players set #game ir.state 3

title @a title {"text":"ICE RUSH!"}
title @a subtitle {"text":"Štart!"}
