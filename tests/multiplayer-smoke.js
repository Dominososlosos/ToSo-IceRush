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

    // Use the generated central pad; the test must not replace it with a ring position.
    admin.chat('/icerush join');

    guest = connect('IceRushGuest');
    await spawned(guest, 'Guest');
    guest.chat('/icerush join');
    await waitFor(admin, /IceRushGuest sa pripojil do ICE RUSH/);

    admin.chat('/icerush ready');
    guest.chat('/icerush ready');
    await waitFor(admin, /ICE RUSH sa začína!/);
    await waitFor(admin, /ŠTART! Kolo 1 — jazda!/);
    await new Promise(resolve => setTimeout(resolve, 600));
    if (!admin.vehicle || !guest.vehicle) {
      throw new Error('A competitor is not actually mounted in a boat after the race starts');
    }
    for (const [name, bot] of [['admin', admin], ['guest', guest]]) {
      const dx = bot.vehicle.position.x - center.x;
      const dz = bot.vehicle.position.z - center.z;
      const radius = Math.sqrt(dx * dx + dz * dz);
      if (radius < 36 || radius > 47) {
        throw new Error(name + ' boat did not start on the track ring; radial distance=' + radius.toFixed(2));
      }
    }
    // Simulate Shift/dismount: the server must safely remount the competitor.
    admin.dismount();
    await new Promise(resolve => setTimeout(resolve, 600));
    if (!admin.vehicle) throw new Error('The player remained dismounted after the server seat-repair tick');
    await waitFor(admin, /PARKOVANIE!/);

    // A two-player match must show exactly one solid 3x3 pad in the centre (9 blocks).
    let centralPadBlocks = 0;
    for (let x = center.x - 8; x <= center.x + 8; x++) {
      for (let z = center.z - 8; z <= center.z + 8; z++) {
        const block = admin.blockAt(new Vec3(x, center.y - 1, z));
        if (block && block.name === 'light_blue_concrete') centralPadBlocks++;
      }
    }
    if (centralPadBlocks !== 9) {
      throw new Error(`Expected exactly one central 3x3 parking pad (9 blocks), found ${centralPadBlocks}`);
    }

    // Move the admin's nearest boat onto the generated central pad; the other player misses it.
    admin.chat(`/tp @e[type=minecraft:boat,sort=nearest,limit=1] ${parking.x} ${parking.y} ${parking.z}`);
    await waitFor(admin, /IceRushGuest vypadáva z kola!/);
    await waitFor(admin, /IceRushAdmin VYHRÁVA ICE RUSH!/);

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
