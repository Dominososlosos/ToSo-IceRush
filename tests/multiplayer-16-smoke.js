const mineflayer = require('mineflayer');
const { Vec3 } = require('vec3');
const fs = require('fs');
const crypto = require('crypto');

const HOST = process.env.MC_HOST || '127.0.0.1';
const PORT = Number(process.env.MC_PORT || 25565);
const TIMEOUT = 45_000;
const PLAYER_COUNT = 16;
const bots = new Map();
let spectator;
let arenaCenter;

function connect(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: '1.20.1', auth: 'offline' });
  bot.messages = [];
  bot.on('messagestr', message => {
    bot.messages.push(message);
    console.log('[' + username + '] ' + message);
  });
  return bot;
}

function waitForNewMessage(bot, pattern, timeout = TIMEOUT) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error('Timed out waiting for a new message matching ' + pattern + '. Recent messages: ' + bot.messages.slice(-25).join(' | ')));
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

function waitFor(bot, pattern, timeout = TIMEOUT) {
  const found = bot.messages.find(message => pattern.test(message));
  if (found) return Promise.resolve(found);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error('Timed out waiting for ' + pattern + '. Recent messages: ' + bot.messages.slice(-25).join(' | ')));
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

function offlineUuid(name) {
  const bytes = crypto.createHash('md5').update('OfflinePlayer:' + name).digest();
  bytes[6] = (bytes[6] & 0x0f) | 0x30;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString('hex');
  return hex.slice(0,8) + '-' + hex.slice(8,12) + '-' + hex.slice(12,16) + '-' + hex.slice(16,20) + '-' + hex.slice(20);
}

async function main() {
  try {
    for (let i = 1; i <= PLAYER_COUNT; i++) {
      const name = 'IceRushP' + String(i).padStart(2, '0');
      const bot = connect(name);
      bots.set(name, bot);
      await spawned(bot, name);
      if (i === 1) {
        arenaCenter = {
          x: Math.floor(bot.entity.position.x),
          y: Math.floor(bot.entity.position.y),
          z: Math.floor(bot.entity.position.z)
        };
        bot.chat('/icerush setup');
        await waitFor(bot, /ICE RUSH aréna vytvorená/);
        await new Promise(resolve => setTimeout(resolve, 500));
        // Setup teleports the admin to the lobby directly above the exact arena centre.
        arenaCenter.x = Math.floor(bot.entity.position.x);
        arenaCenter.z = Math.floor(bot.entity.position.z);
        arenaCenter.y = Math.floor(bot.entity.position.y) - 80;
        const npcXOffsets = [-6.5, -3.7, -0.9, 1.9, 4.7, 7.5];
        const characters = Object.values(bot.entities).filter(entity =>
          entity.name === 'armor_stand'
          && Math.abs(entity.position.y - bot.entity.position.y) < 10
          && Math.abs(entity.position.z - (arenaCenter.z + 6.9)) < 0.75
          && npcXOffsets.some(offset => Math.abs(entity.position.x - (arenaCenter.x + offset)) < 0.8));
        if (characters.length !== 6) throw new Error('Expected six non-villager lobby characters, found ' + characters.length);
        const villagers = Object.values(bot.entities).filter(entity =>
          entity.name === 'villager' && Math.abs(entity.position.y - bot.entity.position.y) < 10);
        if (villagers.length !== 0) throw new Error('Villager NPCs are still present in the lobby');
        bot.chat('/icerush join');
        await waitFor(bot, /sa pripojil do ICE RUSH/);
      } else {
        bot.chat('/icerush join');
        await waitFor(bots.get('IceRushP01'), new RegExp(name + ' sa pripojil do ICE RUSH'));
      }
    }

    for (let i = 1; i <= 13; i++) {
      bots.get('IceRushP' + String(i).padStart(2, '0')).chat('/icerush ready');
      await new Promise(resolve => setTimeout(resolve, 120));
    }
    await waitFor(bots.get('IceRushP01'), /ICE RUSH sa začína!/);
    await waitFor(bots.get('IceRushP01'), /ŠTART! Kolo 1 — jazda!/);
    await new Promise(resolve => setTimeout(resolve, 700));
    for (let i = 1; i <= PLAYER_COUNT; i++) {
      const name = 'IceRushP' + String(i).padStart(2, '0');
      const bot = bots.get(name);
      if (!bot.vehicle) throw new Error(name + ' was not actually mounted in a boat at race start');
      const dx = bot.vehicle.position.x - arenaCenter.x;
      const dz = bot.vehicle.position.z - arenaCenter.z;
      const radius = Math.sqrt(dx * dx + dz * dz);
      if (radius < 36 || radius > 47) throw new Error(name + ' boat was not on the track ring; radial distance=' + radius.toFixed(2));
    }

    spectator = connect('IceRushLate');
    await spawned(spectator, 'Late spectator');
    spectator.chat('/icerush join');
    await waitFor(spectator, /ICE RUSH už prebieha\. Sleduješ zápas ako divák až do jeho skončenia\./);
    await waitFor(spectator, /Tvoj režim: DIVÁK/);
    const lateJoinRejected = waitForNewMessage(spectator, /Hra už prebieha\. Do konca zápasu zostávaš divákom\./);
    const lateJoinMode = waitForNewMessage(spectator, /Tvoj režim: DIVÁK/);
    spectator.chat('/icerush join');
    await lateJoinRejected;
    await lateJoinMode;
    const readyRejected = waitForNewMessage(spectator, /Najprv sa prihlás do lobby/);
    const spectatorStillConfirmed = waitForNewMessage(spectator, /Tvoj režim: DIVÁK/);
    spectator.chat('/icerush ready');
    await readyRejected;
    await spectatorStillConfirmed;

    await waitFor(bots.get('IceRushP01'), /PARKOVANIE!/, 120_000);
    bots.get('IceRushP16').chat('/icerush status');
    await waitFor(bots.get('IceRushP16'), /Aktívne parkoviská: 15.*Bloky: 135\/135/);

    // Disconnecting one competitor during parking must immediately remove exactly one pad.
    const firstName = 'IceRushP01';
    bots.get(firstName).quit();
    await waitFor(spectator, new RegExp(firstName + ' opustil zápas a vypadáva\\.'));
    await new Promise(resolve => setTimeout(resolve, 350));
    spectator.chat('/icerush status');
    await waitFor(spectator, /Aktívne parkoviská: 14.*Bloky: 126\/126/);

    for (let i = 2; i < PLAYER_COUNT; i++) {
      const name = 'IceRushP' + String(i).padStart(2, '0');
      bots.get(name).quit();
      await waitFor(spectator, new RegExp(name + ' opustil zápas a vypadáva\\.'));
    }
    await waitFor(spectator, /IceRushP16 VYHRÁVA ICE RUSH!/);

    const survivor = bots.get('IceRushP16');
    await new Promise(resolve => setTimeout(resolve, 1200));
    if (survivor.vehicle) {
      throw new Error('Winner remained mounted in a boat after the match finished');
    }
    survivor.chat('/icerush stats');
    await waitFor(survivor, /Výhry: 1.*Hry: 1/);
    await waitFor(survivor, /Úspešnosť: 100\.0%.*Prehry: 0/);

    const stats = JSON.parse(fs.readFileSync('server/config/icerush-stats.json', 'utf8'));
    for (let i = 1; i <= PLAYER_COUNT; i++) {
      const name = 'IceRushP' + String(i).padStart(2, '0');
      const row = stats[offlineUuid(name)];
      if (!row || row.gamesPlayed !== 1) throw new Error(name + ' missing exactly one completed game in persisted stats');
      if (i === PLAYER_COUNT && (row.wins !== 1 || row.losses !== 0)) throw new Error('Last connected competitor did not receive the win');
      if (i !== PLAYER_COUNT && (row.wins !== 0 || row.losses !== 1)) throw new Error(name + ' disconnect was not recorded as a loss');
    }
    if (stats[offlineUuid('IceRushLate')]) throw new Error('Late spectator was incorrectly recorded as a competitor');
    

    fs.writeFileSync('server/config/multiplayer-16-test-result.txt',
      'PASS: 16 competitors joined; 13/16 READY started the match; 17th late joiner stayed spectator and could not READY; 15 competitors disconnected; remaining player won; all 16 competitor stats persisted; spectator had no competitor stats.\n');
    console.log('PASS: 16-player capacity, late spectator isolation, multiple disconnects, winner resolution, and persisted statistics.');
  } finally {
    for (const bot of [...bots.values(), spectator]) {
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
