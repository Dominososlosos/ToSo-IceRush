const mineflayer = require('mineflayer');

const HOST = process.env.MC_HOST || '127.0.0.1';
const PORT = Number(process.env.MC_PORT || 25565);
const TIMEOUT = 90_000;

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
    const parking = { x: center.x + 42.0, y: center.y, z: center.z + 0.5 };

    admin.chat('/icerush setup');
    await waitFor(admin, /ICE RUSH aréna vytvorená/);

    // Configure one deterministic pad at a real generated start-ring coordinate.
    admin.chat(`/tp @s ${parking.x} ${parking.y} ${parking.z}`);
    admin.chat('/icerush clearspots');
    await waitFor(admin, /Všetky parkovacie miesta boli odstránené/);
    admin.chat('/icerush addspot');
    await waitFor(admin, /Parkovacie miesto #1 uložené/);
    admin.chat('/icerush join');

    guest = connect('IceRushGuest');
    await spawned(guest, 'Guest');
    guest.chat('/icerush join');
    await waitFor(admin, /IceRushGuest sa pripojil do ICE RUSH/);

    admin.chat('/icerush ready');
    guest.chat('/icerush ready');
    await waitFor(admin, /ICE RUSH sa začína!/);
    await waitFor(admin, /ŠTART! Kolo 1 — jazda!/);
    await waitFor(admin, /PARKOVANIE!/);

    // Move the admin's nearest boat onto the one configured pad; the other player misses it.
    admin.chat(`/tp @e[type=minecraft:boat,sort=nearest,limit=1] ${parking.x} ${parking.y} ${parking.z}`);
    await waitFor(admin, /IceRushGuest vypadáva z kola!/);
    await waitFor(admin, /IceRushAdmin VYHRÁVA ICE RUSH!/);

    await new Promise(resolve => setTimeout(resolve, 1800));
    admin.chat('/icerush stats');
    await waitFor(admin, /Výhry: 1 \\| Hry: 1 \\| Prehry: 0/);
    await waitFor(admin, /Body: 115 \\| Turbo bonusy:/);
    guest.chat('/icerush stats');
    await waitFor(guest, /Výhry: 0 \\| Hry: 1 \\| Prehry: 1/);
    await waitFor(guest, /Body: 60 \\| Turbo bonusy:/);
    console.log('PASS: two real protocol clients joined, readied, raced, parked/eliminated, declared a winner, and persisted both player records.');
  } finally {
    if (guest && guest.player) guest.quit();
    if (admin && admin.player) admin.quit();
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
