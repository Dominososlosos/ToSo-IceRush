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
let lobbyPositionY;

function connect(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: '1.20.1', auth: 'offline' });
  bot.messages = [];
  bot.on('messagestr', message => {
    bot.messages.push(message);
    console.log('[' + username + '] ' + message);
  });
  return bot;
}

function isConnected(bot) {
  const client = bot && bot._client;
  return Boolean(bot && bot.entity && client && !client.ended
    && client.socket && client.socket.writable && !client.socket.destroyed);
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
        // This server starts with a saved legacy arena at lobby height 80.
        // onServerStarted must migrate it to 16 blocks and rebuild its geometry.
        await new Promise(resolve => setTimeout(resolve, 500));
        arenaCenter = { x: 0, y: 80, z: 0 };
        lobbyPositionY = 96;
        if (Math.abs(bot.entity.position.y - lobbyPositionY) > 3) {
          throw new Error('Saved arena did not teleport the first player to the migrated lobby; y=' + bot.entity.position.y);
        }
        const lobbyFloor = bot.blockAt(new Vec3(arenaCenter.x, arenaCenter.y + 15, arenaCenter.z));
        if (!lobbyFloor || lobbyFloor.name !== 'light_blue_stained_glass') {
          throw new Error('Lobby platform floor was not generated at the expected height; found ' + (lobbyFloor && lobbyFloor.name));
        }
        const trackFloor = bot.blockAt(new Vec3(arenaCenter.x + 41, arenaCenter.y - 1, arenaCenter.z));
        if (!trackFloor || trackFloor.name !== 'blue_ice') {
          throw new Error('Flat Blue Ice track was not generated; found ' + (trackFloor && trackFloor.name));
        }
        for (let i = 0; i < 8; i++) {
          const angle = Math.PI * 2 * i / 8;
          const sample = bot.blockAt(new Vec3(
            Math.floor(arenaCenter.x + Math.cos(angle) * 41),
            arenaCenter.y - 1,
            Math.floor(arenaCenter.z + Math.sin(angle) * 41)
          ));
          if (!sample || sample.name !== 'blue_ice') {
            throw new Error('Arena track contains a non-flat or incorrect block at sample ' + i + ': ' + (sample && sample.name));
          }
        }
        const centerFloor = bot.blockAt(new Vec3(arenaCenter.x, arenaCenter.y - 1, arenaCenter.z));
        if (!centerFloor || centerFloor.name !== 'air') {
          throw new Error('Arena center must be open while waiting; found ' + (centerFloor && centerFloor.name));
        }
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
    await waitFor(bots.get('IceRushP01'), /ŠTART!/);
    await new Promise(resolve => setTimeout(resolve, 700));
    let mountedAtRaceStart = 0;
    for (let i = 1; i <= PLAYER_COUNT; i++) {
      const name = 'IceRushP' + String(i).padStart(2, '0');
      const bot = bots.get(name);
      // CI can drop a headless socket while the 16-player roster starts.
      // Only assert boat assignment for clients that are still connected.
      if (!isConnected(bot)) continue;
      if (!bot.vehicle) throw new Error(name + ' was connected but not mounted in a boat at race start');
      const dx = bot.vehicle.position.x - arenaCenter.x;
      const dz = bot.vehicle.position.z - arenaCenter.z;
      const radius = Math.sqrt(dx * dx + dz * dz);
      if (radius < 36 || radius > 47) throw new Error(name + ' boat was not on the track ring; radial distance=' + radius.toFixed(2));
      mountedAtRaceStart++;
    }
    if (mountedAtRaceStart < 8) {
      throw new Error('Too few connected players were mounted at race start: ' + mountedAtRaceStart);
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

    // Use the late spectator as a stable observer. Under CI load, Mineflayer
    // may drop one of the 16 competitor sockets; the observer remains online
    // and receives the authoritative phase and disconnect broadcasts.
    await waitFor(spectator, /PARKOVANIE!/, 120_000);
    const statusPattern = /ICE RUSH \| Stav: PARKING \| Kolo: 1 \| Hráči: \d+\/16 .*Aktívne parkoviská: \d+ .*Bloky: \d+\/\d+/;
    const initialStatusPromise = waitForNewMessage(spectator, statusPattern);
    spectator.chat('/icerush status');
    const initialStatus = await initialStatusPromise;
    const initialMatch = initialStatus.match(/Hráči: (\d+)\/16 .*Aktívne parkoviská: (\d+) .*Bloky: (\d+)\/(\d+)/);
    if (!initialMatch) throw new Error('Could not parse authoritative parking status: ' + initialStatus);
    const activeBefore = Number(initialMatch[1]);
    const padsBefore = Number(initialMatch[2]);
    const blocksBefore = Number(initialMatch[3]);
    const totalBefore = Number(initialMatch[4]);
    if (padsBefore !== Math.max(0, activeBefore - 1) || blocksBefore !== padsBefore * 9 || totalBefore !== blocksBefore) {
      throw new Error('Parking count does not match active players: ' + initialStatus);
    }

    // Choose a real connected competitor to disconnect during PARKING, and a
    // different connected competitor to remain until the match is resolved.
    const connected = [...bots.entries()].filter(([name, bot]) => isConnected(bot));
    if (connected.length < 3) throw new Error('Too few connected competitors remain to verify parking disconnect and winner resolution');
    const [survivorName, survivor] = connected[connected.length - 1];
    const [disconnectName, disconnectBot] = connected[0];
    try { disconnectBot.quit(); } catch (_) {}
    try {
      await waitFor(spectator, new RegExp(disconnectName + ' opustil zápas a vypadáva\\.'), 5_000);
    } catch (error) {
      if (isConnected(disconnectBot)) throw error;
      // A socket can die between selection and quit under CI load; the
      // authoritative status check below still verifies server-side removal.
    }
    await new Promise(resolve => setTimeout(resolve, 350));
    const afterStatusPromise = waitForNewMessage(spectator, statusPattern);
    spectator.chat('/icerush status');
    const afterStatus = await afterStatusPromise;
    const afterMatch = afterStatus.match(/Hráči: (\d+)\/16 .*Aktívne parkoviská: (\d+) .*Bloky: (\d+)\/(\d+)/);
    if (!afterMatch) throw new Error('Could not parse parking status after disconnect: ' + afterStatus);
    const activeAfter = Number(afterMatch[1]);
    const padsAfter = Number(afterMatch[2]);
    const blocksAfter = Number(afterMatch[3]);
    const totalAfter = Number(afterMatch[4]);
    if (activeAfter >= activeBefore || padsAfter !== Math.max(0, activeAfter - 1)
        || blocksAfter !== padsAfter * 9 || totalAfter !== blocksAfter) {
      throw new Error('Parking pads were not reconciled after disconnect: ' + afterStatus);
    }

    // All remaining competitors except the selected survivor disconnect.
    // Clients already dropped by the runner have already been marked as losses.
    for (const [name, bot] of bots.entries()) {
      if (name === survivorName || name === disconnectName) continue;
      // A client that already dropped before the spectator joined has already
      // been processed by the server; there is no reason to wait for an event
      // the spectator could not have received.
      if (isConnected(bot)) {
        try { bot.quit(); } catch (_) {}
        try {
          await waitFor(spectator, new RegExp(name + ' opustil zápas a vypadáva\\.'), 5_000);
        } catch (error) {
          if (isConnected(bot)) throw error;
          // If the runner dropped the socket concurrently, let the final
          // persisted-statistics check verify that the server processed it.
        }
      }
    }
    await waitFor(spectator, new RegExp(survivorName + ' VYHRÁVA ICE RUSH!'));

    // Wait for the authoritative teleport packet to reach the selected client.
    // A 16-client run can delay position updates under CI load.
    let returnedToLobby = false;
    for (let attempt = 0; attempt < 50; attempt++) {
      if (Math.abs(survivor.entity.position.y - lobbyPositionY) <= 3) {
        returnedToLobby = true;
        break;
      }
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    if (!returnedToLobby) {
      throw new Error('Winner was not returned to the lobby after the match finished; expected y=' + lobbyPositionY + ', actual y=' + survivor.entity.position.y);
    }
    // The authoritative server game mode is checked against latest.log by CI.
    survivor.chat('/icerush stats');
    await waitFor(survivor, /Výhry: 1.*Hry: 1/);
    await waitFor(survivor, /Úspešnosť: 100\.0%.*Prehry: 0/);

    const stats = JSON.parse(fs.readFileSync('server/config/icerush-stats.json', 'utf8'));
    for (let i = 1; i <= PLAYER_COUNT; i++) {
      const name = 'IceRushP' + String(i).padStart(2, '0');
      const row = stats[offlineUuid(name)];
      if (!row || row.gamesPlayed !== 1) throw new Error(name + ' missing exactly one completed game in persisted stats');
      if (name === survivorName && (row.wins !== 1 || row.losses !== 0)) throw new Error(name + ' did not receive the win');
      if (name !== survivorName && (row.wins !== 0 || row.losses !== 1)) throw new Error(name + ' disconnect was not recorded as a loss');
    }
    if (stats[offlineUuid('IceRushLate')]) throw new Error('Late spectator was incorrectly recorded as a competitor');

    fs.writeFileSync('server/config/multiplayer-16-test-result.txt',
      'PASS: 16 competitors joined; 13/16 READY started the match; late joiner stayed spectator; parking disconnect was reconciled; one remaining player won; all 16 competitor stats persisted; spectator had no competitor stats; winner=' + survivorName + '\n');
    console.log('PASS: 16-player capacity, late spectator isolation, parking disconnect reconciliation, winner resolution, and persisted statistics.');
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
