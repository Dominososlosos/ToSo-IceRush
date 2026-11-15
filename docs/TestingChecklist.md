# ICE RUSH — server verification checklist

This checklist is for the first real Minecraft server test. It is a test plan, not a claim that these scenarios have already been executed.

## Test environment

- [ ] Minecraft Java 1.21.1
- [ ] Fabric Loader 0.19.1
- [ ] Fabric API 0.116.18+1.21.1
- [ ] Java 21
- [ ] Dedicated test world and a backup before arena generation
- [ ] At least 2 accounts for the first match; up to 16 for load testing

Install the built ICE RUSH JAR in the server `mods` folder and start the server. Confirm that the log contains the ICE RUSH initialization message.

## Arena and lobby

- [ ] Run `/icerush setup` as an administrator at the intended arena center.
- [ ] Verify the circular Blue Ice ring: 90-block outside diameter, 7-block width, empty middle.
- [ ] Verify the lobby is 80 blocks above the track and has a glass viewing section.
- [ ] Verify the six physical lobby controls: JOIN, READY, PRAVIDLÁ, TOP 10, ŠTATISTIKY, POSLEDNÝ VÍŤAZ.
- [ ] Right-click each control and verify that it invokes the matching action.
- [ ] Verify the sidebar displays the waiting-room player count and READY count.
- [ ] Restart the server and verify the saved arena is restored without rebuilding or duplicating lobby controls.
- [ ] Verify `/icerush lobbysetup` repairs missing lobby controls on an existing arena.

## Queue, READY and countdown

- [ ] Verify a player can join the queue only once.
- [ ] Verify the queue refuses players beyond the configured maximum.
- [ ] Verify the minimum player count is enforced.
- [ ] Verify each player can cast READY only once.
- [ ] Verify the countdown starts at the 80% threshold (rounded up).
- [ ] During a lobby countdown, disconnect or remove enough READY players to break the threshold; verify the countdown cancels and boats are cleaned up.
- [ ] Verify boats stay locked during 3–2–1 and unlock together at ŠTART.

## Driving, speed and turbo

- [ ] Verify players cannot remain dismounted or switch to another boat during a round.
- [ ] Verify speed evaluation is inactive for the configured first 5 seconds.
- [ ] Verify the BossBar uses red/yellow/green tiers and displays 100%, 95%, 90%, 85%, 80%, 75%, 70%, 65% or 60%.
- [ ] Verify the physical velocity multiplier follows the displayed tier and never falls below 60%.
- [ ] Verify turbo activates only while the player is in the green tier (80% or more).
- [ ] Verify turbo adds the configured bonus, lasts the configured duration, does not stack, and a repeat trigger refreshes its duration.
- [ ] Verify six default turbo zones and the doubled count during Turbo Madness.
- [ ] Verify turbo sound, particles and actionbar feedback.

## Events and cracks

- [ ] With the default configuration, run enough rounds to sample the 5% event chance.
- [ ] Temporarily set event chance to 100% and verify exactly one event is selected per round.
- [ ] Verify snow, wind, super ice, Turbo Madness and fog each have a visible/gameplay effect.
- [ ] Verify eight fixed crack positions are used.
- [ ] Verify each position uses the configured 10% per-round chance.
- [ ] Verify a crack warns for 2 seconds with blinking ice, sound and particles before opening.
- [ ] Verify adjacent cracks are never open together.
- [ ] Verify falling through a crack eliminates the player and broadcasts the player's name.

## Parking and round transitions

- [ ] Verify the ride phase lasts a random 25–40 seconds by default and does not expose its timer.
- [ ] Verify the parking phase appears with an animated pad reveal and a 20-second countdown.
- [ ] Verify the number of active parking spots equals active players minus one.
- [ ] Verify each parking spot accepts only one boat and locks after occupation.
- [ ] Verify parking ends early when every spot is occupied, or ends when the timer expires.
- [ ] Verify unparked players are eliminated with a public name, title, sound and particles.
- [ ] Verify temporary parking blocks are restored after the phase.
- [ ] Verify surviving players remain in their boats, move to randomized start positions, and are locked during the next countdown.

## Spectators, results and reset

- [ ] Join during an active round; verify the player is sent to the lobby as a spectator.
- [ ] Verify a late spectator is admitted only at the next round boundary.
- [ ] Verify an eliminated player cannot re-enter the current match.
- [ ] Verify the last survivor receives the winner title, music, fireworks and timed celebration particles.
- [ ] Verify scoring: win +100, second +60, third +40, parking +10, turbo +2 (configurable), survived round +5.
- [ ] Verify TOP 10 is sorted by lifetime points.
- [ ] Verify personal wins, games, losses, points, turbo bonuses, speed record and parking record persist after restart.
- [ ] Verify achievement unlocks: Prvá výhra (1 win), Ice King (10 wins), Turbo Master (25 turbo bonuses), Speed Demon (reference speed reached), Survivor (10 survived rounds), Perfect Parking (best parking at or below 5 seconds).
- [ ] Run `/icerush reset` as an administrator during a match; verify everyone returns to the lobby and the aborted match does not change saved statistics or the last winner.

## Load and regression checks

- [ ] Repeat a full match with 2 players.
- [ ] Repeat with 3 players to validate 80% rounding and third-place scoring.
- [ ] Repeat with 8 players.
- [ ] Run a 16-player match and monitor server tick time/TPS.
- [ ] Test player disconnects during racing and parking.
- [ ] Test a server restart while waiting and after arena generation.
- [ ] Inspect the server log for exceptions, missing entities, failed block restoration or repeated entity creation.

## Configuration

Edit `config/icerush.json` while the server is stopped, then restart. Verify min/max players, countdown, ride duration, parking duration, turbo zones, event chance, crack chance, turbo duration/bonus, speed grace period, minimum speed and parking radius. Keep a backup of the configuration and world before changing arena geometry.
