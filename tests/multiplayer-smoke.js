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
  await Promise.race([
    new Promise(resolve => bot.once('spawn', resolve)),
    new Promise((_, reject) => setTimeout(() => reject(new Error(name + ' failed to spawn')), TIMEOUT))
  ]);
}

async function main() {
  let admin;
  let guest;
  try {
    admin = connect('IceRushAdmin');
    await spawned(admin, 'Admin');
    admin.chat('/icerush setup');
    await waitFor(admin, /ICE RUSH aréna vytvorená/);

    guest = connect('IceRushGuest');
    await spawned(guest, 'Guest');

    admin.chat('/icerush join');
    guest.chat('/icerush join');
    await waitFor(admin, /IceRushGuest sa pripojil do ICE RUSH/);
    admin.chat('/icerush ready');
    guest.chat('/icerush ready');

    await waitFor(admin, /ICE RUSH sa začína!/);
    await waitFor(admin, /ŠTART! Kolo 1 — jazda!/);
    await waitFor(admin, /PARKOVANIE!/);

    console.log('Parking phase reached with two connected clients; disconnecting guest to exercise elimination and winner flow.');
    guest.quit('Integration test: deliberate elimination during parking phase');
    await waitFor(admin, /IceRushGuest opustil zápas a vypadáva/);
    await waitFor(admin, /IceRushAdmin VYHRÁVA ICE RUSH!/);

    await new Promise(resolve => setTimeout(resolve, 1800));
    admin.chat('/icerush stats');
    await waitFor(admin, /Výhry: 1 \\| Hry: 1 \\| Prehry: 0/);
    console.log('PASS: two real protocol clients joined, readied, entered racing and parking, produced an elimination and winner, and persisted a 1-win/1-game record.');
  } finally {
    if (guest && guest.player) guest.quit();
    if (admin && admin.player) admin.quit();
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
