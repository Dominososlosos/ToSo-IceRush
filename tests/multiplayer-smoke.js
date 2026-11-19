const mineflayer = require('mineflayer');
const { Vec3 } = require('vec3');
const fs = require('fs');

const HOST = process.env.MC_HOST || '127.0.0.1';
const PORT = Number(process.env.MC_PORT || 25565);
const TIMEOUT = 30_000;

function connect(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: '1.20.1', auth: 'offline' });
  bot.messages = [];
  bot.on('messagestr', message => {
    bot.messages.push(message);
    console.log(`[${username}] ${message}`);
  });
  return bot;
}

function waitFor(bot, pattern, timeout = TIMEOUT) {
  const found = bot.messages.find(message => pattern.test(message));
  if (found) return Promise.resolve(found);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error(`Timed out waiting for ${pattern}. Recent messages: ${bot.messages.slice(-20).join(' | ')}`));
    }, timeout);
    const onMessage = message => {
      if (!pattern.test(message)) return;
      cleanup();
      resolve(message);
    };
    const cleanup = () => {
      clearTimeout(timer);
      bot.off('messagestr', onMessage);
    };
    bot.on('messagestr', onMessage);
  });
}

async function spawned(bot, name) {
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      bot.off('spawn', onSpawn);
      reject(new Error(name + ' failed to spawn'));
    }, TIMEOUT);
    const onSpawn = () => {
      clearTimeout(timer);
      resolve();
    };
    bot.once('spawn', onSpawn);
  });
}

async function main() {
  let admin;
  let guest;
  try {
    admin = connect('IceRushAdmin');
    await spawned(admin, 'Admin');
    const center = {
      x: Math.floor(admin.entity.position.x),
      y: Math.floor(admin.entity.position.y),
      z: Math.floor(admin.entity.position.z)
    };
    // The first central 3x3 pad is centred two blocks diagonally from the arena centre.
    const parking = { x: center.x - 1.5, y: center.y, z: center.z - 1.5 };

    admin.chat('/icerush setup');
    await waitFor(admin, /ICE RUSH aréna vytvorená/);
    // The arena center is the admin's original block position; wait for the lobby teleport.
    for (let attempt = 0; attempt < 50; attempt++) {
      if (Math.abs(admin.entity.position.y - (center.y + 16)) < 2) break;
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    if (Math.abs(admin.entity.position.y - (center.y + 16)) >= 2) {
      throw new Error('Admin was not teleported to the 16-block lobby; y=' + admin.entity.position.y);
    }
    const lobbyFloor = admin.blockAt(new Vec3(center.x, center.y + 15, center.z));
    if (!lobbyFloor || lobbyFloor.name !== 'light_blue_stained_glass') {
      throw new Error('Lobby floor was not generated at the expected height; found ' + (lobbyFloor && lobbyFloor.name));
    }
    const trackFloor = admin.blockAt(new Vec3(center.x + 41, center.y - 1, center.z));
    if (!trackFloor || trackFloor.name !== 'blue_ice') {
      throw new Error('Flat Blue Ice track was not generated; found ' + (trackFloor && trackFloor.name));
    }
    const centerFloor = admin.blockAt(new Vec3(center.x, center.y - 1, center.z));
    if (!centerFloor || centerFloor.name !== 'air') {
      throw new Error('Arena center should be open while waiting; found ' + (centerFloor && centerFloor.name));
    }
    parking.x = center.x - 1.5;
    parking.y = center.y;
    parking.z = center.z - 1.5;

    // Join by clicking the first player-shaped lobby character, not by command.
    await new Promise(resolve => setTimeout(resolve, 500));
    const npcXOffsets = [-6.5, -3.7, -0.9, 1.9, 4.7, 7.5];
    const lobbyCharacters = Object.values(admin.entities).filter(entity =>
      entity.name === 'armor_stand'
      && Math.abs(entity.position.z - (center.z + 6.9)) < 0.75
      && npcXOffsets.some(offset => Math.abs(entity.position.x - (center.x + offset)) < 0.8));
    if (lobbyCharacters.length !== 6) throw new Error('Expected six lobby characters, found ' + lobbyCharacters.length);
    const joinStand = lobbyCharacters.sort((a, b) => a.position.x - b.position.x)[0];
    // The NPCs are deliberately spread along the lobby edge; walk within interaction reach.
    admin.chat(`/tp @s ${joinStand.position.x + 1.5} ${center.y + 16} ${joinStand.position.z - 1.5}`);
    await new Promise(resolve => setTimeout(resolve, 350));
    admin.activateEntity(joinStand);
    await waitFor(admin, /IceRushAdmin sa pripojil do ICE RUSH/);
    // Use the generated central pad; the test must not replace it with a ring position.

    guest = connect('IceRushGuest');
    await spawned(guest, 'Guest');
    guest.chat('/icerush join');
    await waitFor(admin, /IceRushGuest sa pripojil do ICE RUSH/);

    admin.chat('/icerush ready');
    guest.chat('/icerush ready');
    await waitFor(admin, /ICE RUSH sa začína!/);
    await waitFor(admin, /ŠTART!/);
    await new Promise(resolve => setTimeout(resolve, 600));
    if (!admin.vehicle || !guest.vehicle) {
      throw new Error('A competitor is not actually mounted in a boat after the race starts');
    }

    // Mineflayer is a protocol bot and does not run the vanilla client's
    // local BoatEntity steering physics. Exercise both real 1.20.1 packets:
    // paddle state, followed by the vehicle position update a vanilla client
    // sends after its local physics tick.
    const controlledBoat = admin.vehicle;
    const startBoatPosition = controlledBoat.position.clone();
    admin._client.write('steer_boat', { leftPaddle: true, rightPaddle: true });
    await new Promise(resolve => setTimeout(resolve, 150));
    admin._client.write('vehicle_move', {
      x: startBoatPosition.x + 0.75,
      y: startBoatPosition.y,
      z: startBoatPosition.z,
      yaw: controlledBoat.yaw,
      pitch: controlledBoat.pitch
    });
    await new Promise(resolve => setTimeout(resolve, 350));
    admin._client.write('steer_boat', { leftPaddle: false, rightPaddle: false });
    await new Promise(resolve => setTimeout(resolve, 250));
    if (!admin.vehicle || admin.vehicle.id !== controlledBoat.id) {
      throw new Error('Competitor lost their assigned boat after vanilla paddle and vehicle movement packets');
    }
    const boatTravel = admin.vehicle.position.distanceTo(startBoatPosition);
    if (boatTravel < 0.35) {
      throw new Error('Server did not preserve vanilla client boat movement; distance=' + boatTravel.toFixed(3));
    }

    for (const [name, bot] of [['admin', admin], ['guest', guest]]) {
      const dx = bot.vehicle.position.x - center.x;
      const dz = bot.vehicle.position.z - center.z;
      const radius = Math.sqrt(dx * dx + dz * dz);
      if (radius < 36 || radius > 47) {
        throw new Error(name + ' boat did not start on the track ring; radial distance=' + radius.toFixed(2));
      }
    }
    // Simulate Shift/dismount: only this manual request is blocked during the race.
    admin.dismount();
    await new Promise(resolve => setTimeout(resolve, 600));
    if (!admin.vehicle || admin.vehicle.id !== controlledBoat.id) {
      throw new Error('Sneak dismount was not blocked while the competitor was racing');
    }
    await waitFor(admin, /PARKOVANIE!/);

    // Ask the server itself to count the real 3x3 concrete blocks.
    admin.chat('/icerush status');
    await waitFor(admin, /Aktívne parkoviská: 1.*Bloky: 9\/9/);

    // Move the admin's nearest boat onto the generated central pad; the other player misses it.
    admin.chat(`/tp @e[type=minecraft:boat,sort=nearest,limit=1] ${parking.x} ${parking.y} ${parking.z}`);
    await waitFor(admin, /IceRushGuest vypadáva z kola!/);
    await waitFor(admin, /IceRushAdmin VYHRÁVA ICE RUSH!/);
    admin.chat('/icerush status');
    await waitFor(admin, /Aktívne parkoviská: 0.*Bloky: 0\/0/);

    await new Promise(resolve => setTimeout(resolve, 1800));
    admin.chat('/icerush stats');
    await waitFor(admin, /Výhry: 1.*Hry: 1/);
    await waitFor(admin, /Úspešnosť: 100\.0%.*Prehry: 0/);
    await waitFor(admin, /Celkové body: 115/);
    guest.chat('/icerush stats');
    await waitFor(guest, /Výhry: 0.*Hry: 1/);
    await waitFor(guest, /Úspešnosť: 0\.0%.*Prehry: 1/);
    await waitFor(guest, /Celkové body: 60/);
    fs.writeFileSync('server/config/multiplayer-test-result.txt', 'PASS: two clients joined, readied, raced, parked/eliminated, winner declared; admin 1W/115 points, guest 1L/60 points.\\n');
    console.log('PASS: two real protocol clients joined, readied, raced, parked/eliminated, declared a winner, and persisted both player records.');
  } finally {
    for (const bot of [guest, admin]) {
      if (!bot) continue;
      try { bot.quit(); } catch (_) {}
      try { if (bot._client) bot._client.end(); } catch (_) {}
    }
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
