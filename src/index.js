import {
  Client,
  GatewayIntentBits,
  Partials,
  EmbedBuilder,
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  ActivityType
} from 'discord.js';
import {
  joinVoiceChannel,
  createAudioPlayer,
  createAudioResource,
  AudioPlayerStatus,
  NoSubscriberBehavior,
  StreamType,
  entersState,
  VoiceConnectionStatus,
  generateDependencyReport
} from '@discordjs/voice';
import fetch from 'node-fetch';
import { spawn } from 'child_process';
import ffmpegPath from 'ffmpeg-static';
import { PassThrough } from 'stream';
import mysql from 'mysql2/promise';
import dotenv from 'dotenv';

dotenv.config();

const REQUIRED_ENV = [
  'DISCORD_TOKEN',
  'DB_HOST',
  'DB_PORT',
  'DB_USER',
  'DB_PASSWORD',
  'DB_NAME'
];
const missing = REQUIRED_ENV.filter(
  k => !process.env[k] || String(process.env[k]).trim() === ''
);
if (missing.length) {
  console.error(`Fehlende Umgebungsvariablen: ${missing.join(', ')}`);
  process.exit(1);
}

process.on('unhandledRejection', e =>
  console.error('Ungefangene Promise-Rejection:', e)
);
process.on('uncaughtException', e =>
  console.error('Ungefangene Exception:', e)
);
process.on('SIGINT', () => gracefulShutdown('SIGINT'));
process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));

try {
  console.log(generateDependencyReport());
} catch (e) {
  console.error('Fehler beim Dependency Report:', e);
}

const client = new Client({
  intents: [
    GatewayIntentBits.Guilds,
    GatewayIntentBits.GuildVoiceStates,
    GatewayIntentBits.GuildMessages
  ],
  partials: [Partials.Channel]
});

const queues = new Map();
const activeListening = new Map();

const pool = mysql.createPool({
  host: process.env.DB_HOST,
  port: Number(process.env.DB_PORT),
  user: process.env.DB_USER,
  password: process.env.DB_PASSWORD,
  database: process.env.DB_NAME,
  waitForConnections: true,
  connectionLimit: 10,
  queueLimit: 0
});

async function initializeDatabase() {
  const createPlaybackTable = `
    CREATE TABLE IF NOT EXISTS playback_sessions (
      guild_id VARCHAR(64) PRIMARY KEY,
      voice_channel_id VARCHAR(64),
      text_channel_id VARCHAR(64),
      control_message_id VARCHAR(64),
      current_song TEXT,
      queue TEXT,
      shuffle_mode TINYINT(1),
      playing TINYINT(1),
      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
  `;
  const createFavoritesTable = `
    CREATE TABLE IF NOT EXISTS user_favorites (
      user_id VARCHAR(64) NOT NULL,
      song_id INT NOT NULL,
      PRIMARY KEY (user_id, song_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
  `;
  const createUserStatsTable = `
    CREATE TABLE IF NOT EXISTS user_stats (
      user_id VARCHAR(64) PRIMARY KEY,
      listening_time BIGINT DEFAULT 0
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
  `;
  try {
    await pool.query(createPlaybackTable);
    await pool.query(createFavoritesTable);
    await pool.query(createUserStatsTable);
    const [columns] = await pool.query(
      "SHOW COLUMNS FROM user_stats LIKE 'listening_time'"
    );
    if (!columns.length) {
      await pool.query(
        'ALTER TABLE user_stats ADD COLUMN listening_time BIGINT DEFAULT 0'
      );
    }
    console.log('Database initialized.');
  } catch (error) {
    console.error('Database initialization failed:', error);
    process.exit(1);
  }
}

async function addFavorite(userId, songId) {
  try {
    await pool.query(
      'INSERT IGNORE INTO user_favorites (user_id, song_id) VALUES (?, ?)',
      [userId, songId]
    );
  } catch (e) {
    console.error('Fehler beim Hinzufügen eines Favoriten:', e);
  }
}
async function removeFavorite(userId, songId) {
  try {
    await pool.query(
      'DELETE FROM user_favorites WHERE user_id = ? AND song_id = ?',
      [userId, songId]
    );
  } catch (e) {
    console.error('Fehler beim Entfernen eines Favoriten:', e);
  }
}
async function getFavorites(userId) {
  try {
    const [rows] = await pool.query(
      'SELECT song_id FROM user_favorites WHERE user_id = ?',
      [userId]
    );
    return rows.map(r => r.song_id);
  } catch (e) {
    console.error('Fehler beim Abruf der Favoriten:', e);
    return [];
  }
}

async function updateSession(guildId) {
  const queue = queues.get(guildId);
  if (!queue) {
    try {
      await pool.query('DELETE FROM playback_sessions WHERE guild_id = ?', [
        guildId
      ]);
    } catch (e) {
      console.error('Fehler beim Löschen der Session:', e);
    }
    return;
  }
  const currentSongStr = queue.currentSong
    ? JSON.stringify(queue.currentSong)
    : null;
  const queueStr = JSON.stringify(queue.songs);
  const shuffle_mode = queue.shuffleMode ? 1 : 0;
  const playing = queue.playing ? 1 : 0;
  const voiceChannelId = queue.connection?.joinConfig?.channelId || null;
  const textChannelId = queue.textChannel?.id || null;
  const controlMessageId = queue.controlMessage
    ? queue.controlMessage.id
    : null;

  const sql = `
    INSERT INTO playback_sessions (
      guild_id, voice_channel_id, text_channel_id, control_message_id,
      current_song, queue, shuffle_mode, playing
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    ON DUPLICATE KEY UPDATE
      voice_channel_id = VALUES(voice_channel_id),
      text_channel_id = VALUES(text_channel_id),
      control_message_id = VALUES(control_message_id),
      current_song = VALUES(current_song),
      queue = VALUES(queue),
      shuffle_mode = VALUES(shuffle_mode),
      playing = VALUES(playing)
  `;
  try {
    await pool.query(sql, [
      guildId,
      voiceChannelId,
      textChannelId,
      controlMessageId,
      currentSongStr,
      queueStr,
      shuffle_mode,
      playing
    ]);
  } catch (e) {
    console.error('Error updating session for guild', guildId, e);
  }
}

function addConnectionStateHandler(connection) {
  connection.on(VoiceConnectionStatus.Disconnected, async () => {
    try {
      await Promise.race([
        entersState(connection, VoiceConnectionStatus.Signalling, 5000),
        entersState(connection, VoiceConnectionStatus.Connecting, 5000)
      ]);
    } catch {
      connection.destroy();
    }
  });
}

async function restoreSessions() {
  try {
    const [rows] = await pool.query('SELECT * FROM playback_sessions');
    for (const row of rows) {
      const guild = client.guilds.cache.get(row.guild_id);
      if (!guild) continue;
      const voiceChannel = guild.channels.cache.get(row.voice_channel_id);
      const textChannel = guild.channels.cache.get(row.text_channel_id);
      if (!voiceChannel || !textChannel) continue;

      let connection;
      try {
        connection = joinVoiceChannel({
          channelId: voiceChannel.id,
          guildId: guild.id,
          adapterCreator: guild.voiceAdapterCreator
        });
        addConnectionStateHandler(connection);
        await entersState(connection, VoiceConnectionStatus.Ready, 30000);
      } catch (error) {
        console.error(
          `Error restoring connection for guild ${guild.id}`,
          error
        );
        try {
          connection?.destroy();
        } catch {}
        continue;
      }

      const player = createAudioPlayer({
        behaviors: { noSubscriber: NoSubscriberBehavior.Play }
      });
      player.on('error', err =>
        console.error('Audio-Player error (restored):', err)
      );

      let storedQueue = [];
      try {
        storedQueue = JSON.parse(row.queue || '[]');
      } catch (e) {
        console.error('Queue parse error', e);
      }
      let storedCurrentSong = null;
      try {
        storedCurrentSong = row.current_song
          ? JSON.parse(row.current_song)
          : null;
      } catch (e) {
        console.error('Song parse error', e);
      }
      if (storedCurrentSong) storedQueue.unshift(storedCurrentSong);

      const restoredQueue = {
        songs: storedQueue,
        currentSong: null,
        player,
        connection,
        playing: !!row.playing,
        textChannel,
        controlMessage: null,
        shuffleMode: !!row.shuffle_mode,
        volume: 1.0,
        loopCurrent: false,
        loopQueue: false,
        currentResource: null,
        currentFFmpegProcess: null,
        lastActivity: Date.now()
      };
      queues.set(guild.id, restoredQueue);
      console.log('Restored playback session for guild', guild.id);

      if (restoredQueue.playing && restoredQueue.songs.length > 0) {
        playSong(guild);
      }
    }
  } catch (error) {
    console.error('Error restoring sessions:', error);
  }
}

function createFFmpegStream(url) {
  const ffmpegExecutable = ffmpegPath || 'ffmpeg';
  const ffmpeg = spawn(ffmpegExecutable, [
    '-reconnect',
    '1',
    '-reconnect_streamed',
    '1',
    '-reconnect_delay_max',
    '5',
    '-i',
    url,
    '-vn',
    '-ac',
    '2',
    '-ar',
    '48000',
    '-f',
    's16le',
    'pipe:1'
  ]);
  ffmpeg.stderr.on('data', () => {});
  ffmpeg.on('error', () => {});

  const stream = new PassThrough({ highWaterMark: 256 * 1024 });
  ffmpeg.stdout.pipe(stream);

  stream.on('error', err => {
    if (err?.code !== 'ERR_STREAM_PREMATURE_CLOSE') {
      console.error('Stream error:', err);
    }
  });

  ffmpeg.on('close', () => {
    if (!stream.destroyed) stream.end();
  });

  return { process: ffmpeg, stream };
}

async function updateControlEmbed(guild, song) {
  const queue = queues.get(guild.id);
  if (!queue) return;

  const embed = new EmbedBuilder()
    .setTitle(`🎧 Jetzt läuft: ${song ? song.name : 'Unbekannt'} 💜`)
    .setDescription(
      `**🎤 Artist:** ${song?.artist_name ?? 'Unbekannt'}\n` +
        `**📝 Nickname:** ${song?.nickname ?? 'Unbekannt'}`
    )
    .setColor(0x8e44ad)
    .setTimestamp();

  if (song?.thumbnail) {
    embed.setImage(`https://music.boocord.com/${song.thumbnail}`);
  }

  const controls = new ActionRowBuilder().addComponents(
    new ButtonBuilder()
      .setCustomId('pause_resume')
      .setLabel('⏯️ Pause')
      .setStyle(ButtonStyle.Primary),
    new ButtonBuilder()
      .setCustomId('skip_song')
      .setLabel('⏭️ Skip')
      .setStyle(ButtonStyle.Primary),
    new ButtonBuilder()
      .setCustomId('stop_song')
      .setLabel('⏹️ Stop')
      .setStyle(ButtonStyle.Danger),
    new ButtonBuilder()
      .setCustomId('toggle_favorite')
      .setLabel('⭐️ Favorit')
      .setStyle(ButtonStyle.Success),
    new ButtonBuilder()
      .setCustomId('lyrics_button')
      .setLabel('📝 Lyrics')
      .setStyle(ButtonStyle.Secondary)
  );

  try {
    if (queue.controlMessage) {
      await queue.controlMessage.edit({
        embeds: [embed],
        components: [controls]
      });
    } else {
      const msg = await queue.textChannel.send({
        embeds: [embed],
        components: [controls]
      });
      queue.controlMessage = msg;
      await updateSession(guild.id);
    }
  } catch (e) {
    console.error('Fehler beim Aktualisieren des Control Embeds:', e);
    if (e?.code === 10008) {
      try {
        const msg = await queue.textChannel.send({
          embeds: [embed],
          components: [controls]
        });
        queue.controlMessage = msg;
        await updateSession(guild.id);
      } catch (sendError) {
        console.error(
          'Fehler beim Senden eines neuen Control Embeds:',
          sendError
        );
      }
    }
  }
}

function updateGuildListeningStats(guildId) {
  for (const key of Array.from(activeListening.keys())) {
    if (key.startsWith(`${guildId}-`)) {
      const start = activeListening.get(key);
      const elapsed = Date.now() - start;
      const userId = key.split('-')[1];
      updateUserStats(userId, Math.floor(elapsed / 1000));
      activeListening.delete(key);
    }
  }
}
function initializeActiveListening(guild) {
  const queue = queues.get(guild.id);
  if (!queue) return;
  const voiceChannel = guild.channels.cache.get(
    queue.connection.joinConfig.channelId
  );
  if (!voiceChannel) return;
  voiceChannel.members.forEach(member => {
    if (!member.user.bot) {
      activeListening.set(`${guild.id}-${member.id}`, Date.now());
    }
  });
}
async function updateUserStats(userId, seconds) {
  try {
    await pool.query(
      `INSERT INTO user_stats (user_id, listening_time) VALUES (?, ?)
       ON DUPLICATE KEY UPDATE listening_time = listening_time + ?`,
      [userId, seconds, seconds]
    );
  } catch (e) {
    console.error('Error updating user stats:', e);
  }
}
async function getUserStats(userId) {
  try {
    const [rows] = await pool.query(
      'SELECT listening_time FROM user_stats WHERE user_id = ?',
      [userId]
    );
    return rows.length ? Number(rows[0].listening_time) : 0;
  } catch (e) {
    console.error('Error fetching user stats:', e);
    return 0;
  }
}

async function getOrCreateQueue(guild, member, textChannel, shuffleMode = false) {
  let queue = queues.get(guild.id);
  if (!queue) {
    let connection;
    try {
      if (!member?.voice?.channel?.id) return null;
      connection = joinVoiceChannel({
        channelId: member.voice.channel.id,
        guildId: guild.id,
        adapterCreator: guild.voiceAdapterCreator
      });
      addConnectionStateHandler(connection);
      await entersState(connection, VoiceConnectionStatus.Ready, 30000);
    } catch (e) {
      console.error('Verbindungsaufbau fehlgeschlagen:', e);
      try {
        connection?.destroy();
      } catch {}
      return null;
    }
    const player = createAudioPlayer({
      behaviors: { noSubscriber: NoSubscriberBehavior.Play }
    });
    player.on('error', err => {
      if (err?.code !== 'ERR_STREAM_PREMATURE_CLOSE') {
        console.error('Audio-Player-Fehler:', err);
      }
    });
    queue = {
      songs: [],
      currentSong: null,
      player,
      connection,
      playing: false,
      textChannel,
      controlMessage: null,
      shuffleMode,
      volume: 1.0,
      loopCurrent: false,
      loopQueue: false,
      currentResource: null,
      currentFFmpegProcess: null,
      lastActivity: Date.now()
    };
    queues.set(guild.id, queue);
    queue.connection.on('error', err =>
      console.error('Voice-Connection-Fehler:', err)
    );
  } else {
    queue.textChannel = textChannel;
    queue.lastActivity = Date.now();
  }
  return queue;
}
function destroyQueue(guildId) {
  const queue = queues.get(guildId);
  if (queue) {
    try {
      if (
        queue.connection &&
        queue.connection.state.status !== VoiceConnectionStatus.Destroyed
      ) {
        queue.connection.destroy();
      }
    } catch (e) {
      console.error('Fehler beim Zerstören der Voice-Connection:', e);
    }
    queues.delete(guildId);
    updateSession(guildId).catch(() => {});
    console.log(`Queue für Guild ${guildId} wurde zerstört.`);
  }
}
setInterval(() => {
  const now = Date.now();
  for (const [guildId, queue] of queues.entries()) {
    if (now - queue.lastActivity > 5 * 60 * 1000 && !queue.playing) {
      destroyQueue(guildId);
    }
  }
}, 60000);
setInterval(() => {
  const now = Date.now();
  for (const [key, start] of activeListening.entries()) {
    if (now - start > 60 * 60 * 1000) {
      activeListening.delete(key);
    }
  }
}, 60000);

async function playSong(guild) {
  const queue = queues.get(guild.id);
  if (!queue) return;
  queue.lastActivity = Date.now();

  if (
    !queue.connection ||
    queue.connection.state.status === VoiceConnectionStatus.Destroyed
  ) {
    console.log(
      `Voice Connection in Guild ${guild.id} wurde zerstört. Erstelle neue Verbindung...`
    );
    const channelId = queue.connection
      ? queue.connection.joinConfig.channelId
      : null;
    if (!channelId) {
      console.error('Keine Channel-ID zur Wiederherstellung der Voice Connection.');
      destroyQueue(guild.id);
      return;
    }
    try {
      const newConnection = joinVoiceChannel({
        channelId,
        guildId: guild.id,
        adapterCreator: guild.voiceAdapterCreator
      });
      addConnectionStateHandler(newConnection);
      await entersState(newConnection, VoiceConnectionStatus.Ready, 30000);
      queue.connection = newConnection;
    } catch (e) {
      console.error('Neuer Verbindungsaufbau fehlgeschlagen:', e);
      destroyQueue(guild.id);
      return;
    }
  }

  if (queue.songs.length === 0) {
    if (queue.shuffleMode) {
      try {
        const songs = await fetchPlaylist(9999);
        if (!songs.length) {
          queue.playing = false;
          destroyQueue(guild.id);
          return;
        }
        for (let i = songs.length - 1; i > 0; i--) {
          const j = Math.floor(Math.random() * (i + 1));
          [songs[i], songs[j]] = [songs[j], songs[i]];
        }
        queue.songs = songs;
        await updateSession(guild.id);
        playSong(guild);
      } catch (err) {
        console.error('Fehler beim erneuten Abruf der Playlist:', err);
        queue.playing = false;
        destroyQueue(guild.id);
      }
      return;
    }
    queue.playing = false;
    destroyQueue(guild.id);
    return;
  }

  const song = queue.songs.shift();
  queue.currentSong = song;
  await updateSession(guild.id);
  const songUrl = encodeURI(`https://music.boocord.com/${song.path}`);

  console.log(`${guild.name} (${guild.id}) – ${song.name}`);

  let ffmpegProcess;
  try {
    const ffmpegResult = createFFmpegStream(songUrl);
    ffmpegProcess = ffmpegResult.process;
    const resource = createAudioResource(ffmpegResult.stream, {
      inputType: StreamType.Raw,
      inlineVolume: true
    });
    resource.volume.setVolumeLogarithmic(queue.volume || 1.0);

    queue.currentResource = resource;
    queue.currentFFmpegProcess = ffmpegProcess;
    queue.retryCount = 0;

    queue.player.play(resource);
    queue.connection.subscribe(queue.player);
    updateControlEmbed(guild, song);
    await updateSession(guild.id);
    initializeActiveListening(guild);
  } catch (e) {
    console.error('Fehler beim Erstellen der AudioResource:', e);
    if (ffmpegProcess && !ffmpegProcess.killed) {
      try {
        ffmpegProcess.kill();
      } catch {}
    }
    queue.retryCount = (queue.retryCount || 0) + 1;
    const delay = Math.min(1000 * queue.retryCount, 5000);
    console.log(
      `Warte ${delay}ms vor erneutem Versuch, Song abzuspielen.`
    );
    setTimeout(() => playSong(guild), delay);
    return;
  }

  const onIdle = () => {
    updateGuildListeningStats(guild.id);
    if (
      queue.currentFFmpegProcess &&
      !queue.currentFFmpegProcess.killed
    ) {
      try {
        queue.currentFFmpegProcess.kill();
      } catch {}
    }
    queue.currentFFmpegProcess = null;
    queue.currentResource = null;

    if (queue.loopCurrent && queue.currentSong) {
      queue.songs.unshift(queue.currentSong);
    } else if (queue.loopQueue && queue.currentSong) {
      queue.songs.push(queue.currentSong);
    }
    playSong(guild);
  };

  queue.player.removeAllListeners(AudioPlayerStatus.Idle);
  queue.player.once(AudioPlayerStatus.Idle, onIdle);
}

async function fetchJson(url, { timeoutMs = 15000 } = {}) {
  const controller = new AbortController();
  const t = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      headers: { Accept: 'application/json' },
      redirect: 'follow',
      signal: controller.signal
    });
    if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
    return await res.json();
  } finally {
    clearTimeout(t);
  }
}
async function fetchPlaylist(limit = 9999) {
  try {
    const safeLimit = Math.max(1, Math.min(Number(limit) || 1, 9999));
    const data = await fetchJson(
      `https://music.boocord.com/api/request/playlist.php?limit=${safeLimit}`
    );
    return Array.isArray(data?.songs) ? data.songs : [];
  } catch (e) {
    console.error('Fehler beim Abruf der Playlist:', e);
    return [];
  }
}
async function fetchSongById(id) {
  try {
    const safeId = Number(id);
    if (!Number.isInteger(safeId) || safeId < 0) return null;
    const song = await fetchJson(
      `https://music.boocord.com/api/request/song.php?id=${safeId}`
    );
    return song?.id ? song : null;
  } catch (e) {
    console.error('Fehler beim Abruf des Songs:', e);
    return null;
  }
}

client.on('voiceStateUpdate', (oldState, newState) => {
  if (newState.member?.user?.bot) return;
  const guildId = newState.guild.id;
  const userId = newState.id;
  const key = `${guildId}-${userId}`;

  const queue = queues.get(guildId);
  const isPlaying =
    queue &&
    queue.currentSong &&
    queue.player.state.status === AudioPlayerStatus.Playing;
  if (!isPlaying) {
    if (activeListening.has(key)) {
      const start = activeListening.get(key);
      updateUserStats(userId, Math.floor((Date.now() - start) / 1000));
      activeListening.delete(key);
    }
    return;
  }

  const botChannelId = queue.connection.joinConfig.channelId;
  if (
    newState.channelId === botChannelId &&
    oldState.channelId !== botChannelId
  ) {
    activeListening.set(key, Date.now());
  } else if (
    oldState.channelId === botChannelId &&
    newState.channelId !== botChannelId
  ) {
    if (activeListening.has(key)) {
      const start = activeListening.get(key);
      updateUserStats(userId, Math.floor((Date.now() - start) / 1000));
      activeListening.delete(key);
    }
  }
});

let bootstrapped = false;
async function onReady() {
  if (bootstrapped) return;
  bootstrapped = true;

  console.log(`Angemeldet als ${client.user.tag}`);
  updateBotStatus();
  setInterval(updateBotStatus, 300000);

  await initializeDatabase();
  await restoreSessions();

  try {
    await client.application?.commands.set([
      {
        name: 'play',
        description:
          'Fügt einen Song zur Warteschlange hinzu und startet die Wiedergabe 🎶',
        options: [
          {
            name: 'query',
            type: 3,
            description: 'Songname oder Nummer',
            required: true
          }
        ]
      },
      { name: 'playlist', description: 'Zeigt die komplette Playlist an 📜' },
      { name: 'queue', description: 'Zeigt die aktuelle Warteschlange an 📋' },
      { name: 'skip', description: 'Überspringt den aktuellen Song ⏭️' },
      { name: 'pause', description: 'Pausiert die Wiedergabe ⏸️' },
      { name: 'resume', description: 'Setzt die Wiedergabe fort ▶️' },
      {
        name: 'stop',
        description:
          'Stoppt die Wiedergabe und trennt die Verbindung ⏹️'
      },
      {
        name: 'shuffle',
        description:
          'Aktiviert den 24/7 Shuffle-Modus - alle Songs der Playlist werden random abgespielt 🔀'
      },
      {
        name: 'requestsong',
        description: 'Fügt einen Song per ID zur Warteschlange hinzu 📥',
        options: [
          {
            name: 'id',
            type: 4,
            description: 'Die ID des Songs',
            required: true
          }
        ]
      },
      {
        name: 'lyrics',
        description:
          'Zeigt die Songtexte des aktuell laufenden Songs an 📝'
      },
      { name: 'favorites', description: 'Zeigt deine Favoriten an.' },
      {
        name: 'favoplay',
        description:
          'Fügt alle deine Favoriten als Shuffle in die Warteschlange hinzu.'
      },
      {
        name: 'volume',
        description: 'Stelle die Lautstärke ein (0.0 bis 2.0)',
        options: [
          {
            name: 'value',
            type: 10,
            description: 'Lautstärke-Multiplikator (z. B. 1.0)',
            required: true
          }
        ]
      },
      { name: 'loop', description: 'Toggle Loop für den aktuellen Song' },
      { name: 'repeat', description: 'Toggle Repeat für die gesamte Queue' },
      {
        name: 'favotoggle',
        description:
          'Toggle den aktuellen Song als Favorit (hinzufügen/entfernen).'
      },
      {
        name: 'stats',
        description:
          'Zeigt an, wie lange du insgesamt Musik mit dem Bot gehört hast.'
      }
    ]);
  } catch (e) {
    console.error('Fehler beim Registrieren der Slash Commands:', e);
  }
}
client.once('ready', onReady);

client.on('interactionCreate', async interaction => {
  try {
    if (interaction.isChatInputCommand()) {
      await interaction.deferReply();
      const guild = interaction.guild;
      if (!guild) {
        await interaction.editReply({
          embeds: [
            new EmbedBuilder()
              .setTitle('Fehler 💜')
              .setDescription(
                'Dieser Befehl kann nur auf einem Server verwendet werden.'
              )
              .setColor(0x8e44ad)
          ]
        });
        return;
      }

      if (interaction.commandName === 'play') {
        const query = interaction.options.getString('query');
        const member = interaction.member;
        if (!member || !('voice' in member) || !member.voice.channel) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Fehler 💜')
                .setDescription('Du musst in einem Voice-Channel sein!')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        const queue = await getOrCreateQueue(
          guild,
          member,
          interaction.channel,
          false
        );
        if (!queue) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Fehler 💜')
                .setDescription('Verbindungsaufbau fehlgeschlagen!')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        const songs = await fetchPlaylist(9999);
        let song;
        if (!isNaN(Number(query))) {
          const index = parseInt(query, 10) - 1;
          song = songs[index];
        } else {
          const q = query.toLowerCase();
          song = songs.find(s => s.name?.toLowerCase().includes(q));
        }
        if (!song) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Nicht gefunden 💜')
                .setDescription('Song nicht gefunden!')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        if (queue.shuffleMode) {
          queue.songs.unshift(song);
          if (queue.playing) {
            queue.player.stop();
          } else {
            queue.playing = true;
            playSong(guild);
          }
          await updateSession(guild.id);
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Song hinzugefügt 💜')
                .setDescription(
                  `Song **${song.name}** wurde hinzugefügt und wird jetzt abgespielt!`
                )
                .setColor(0x8e44ad)
            ]
          });
        } else {
          queue.songs.push(song);
          if (!queue.playing) {
            queue.playing = true;
            playSong(guild);
          }
          await updateSession(guild.id);
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Song hinzugefügt 💜')
                .setDescription(
                  `Song **${song.name}** wurde zur Warteschlange hinzugefügt!`
                )
                .setColor(0x8e44ad)
            ]
          });
        }
        return;
      }

      if (interaction.commandName === 'playlist') {
        const songs = await fetchPlaylist(9999);
        if (!songs.length) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Playlist leer 💜')
                .setDescription('Keine Songs in der Playlist gefunden.')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        let currentPage = 0;
        const itemsPerPage = 10;
        const totalPages = Math.ceil(songs.length / itemsPerPage);

        const generateEmbed = page => {
          const start = page * itemsPerPage;
          const currentSongs = songs.slice(start, start + itemsPerPage);
          const embed = new EmbedBuilder()
            .setTitle('🎶 Playlist Übersicht')
            .setColor(0x8e44ad)
            .setFooter({ text: `Seite ${page + 1} von ${totalPages}` });
          currentSongs.forEach((song, i) => {
            embed.addFields({
              name: `**${start + i + 1}. ${song.name}**`,
              value: song.thumbnail
                ? `[Cover anzeigen](https://music.boocord.com/${song.thumbnail})`
                : 'Kein Cover vorhanden',
              inline: false
            });
          });
          return embed;
        };

        const row = new ActionRowBuilder().addComponents(
          new ButtonBuilder()
            .setCustomId('playlist_prev')
            .setLabel('⏮️ Zurück')
            .setStyle(ButtonStyle.Primary)
            .setDisabled(true),
          new ButtonBuilder()
            .setCustomId('playlist_next')
            .setLabel('Weiter ⏭️')
            .setStyle(ButtonStyle.Primary)
            .setDisabled(totalPages <= 1)
        );

        await interaction.editReply({
          embeds: [generateEmbed(currentPage)],
          components: [row]
        });
        const playlistMessage = await interaction.fetchReply();
        const collector = playlistMessage.createMessageComponentCollector({
          time: 600000
        });

        collector.on('collect', async i => {
          if (i.customId === 'playlist_prev') currentPage -= 1;
          else if (i.customId === 'playlist_next') currentPage += 1;

          if (currentPage < 0) currentPage = 0;
          if (currentPage >= totalPages) currentPage = totalPages - 1;

          row.components[0].setDisabled(currentPage === 0);
          row.components[1].setDisabled(currentPage === totalPages - 1);

          try {
            await i.update({
              embeds: [generateEmbed(currentPage)],
              components: [row]
            });
          } catch (err) {
            if (err?.code !== 10062) console.error(err);
          }
        });

        collector.on('end', () => {
          playlistMessage.edit({ components: [] }).catch(() => {});
        });
        return;
      }

      if (interaction.commandName === 'queue') {
        const queue = queues.get(guild.id);
        if (!queue || (!queue.songs.length && !queue.currentSong)) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Warteschlange leer 💜')
                .setDescription('Die Warteschlange ist leer.')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        const embed = new EmbedBuilder()
          .setTitle('📋 Aktuelle Warteschlange')
          .setColor(0x8e44ad)
          .setDescription(
            (queue.currentSong
              ? `▶️ **Aktuell:** ${queue.currentSong.name}\n`
              : '') +
              queue.songs
                .map((s, i) => `🔹 ${i + 1}. ${s.name}`)
                .join('\n')
          );
        await interaction.editReply({ embeds: [embed] });
        return;
      }

      if (interaction.commandName === 'skip') {
        const queue = queues.get(guild.id);
        if (!queue) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Keine Wiedergabe 💜')
                .setDescription('Keine Musik wird gespielt.')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        queue.player.stop();
        await updateSession(guild.id);
        await interaction.editReply({
          embeds: [
            new EmbedBuilder()
              .setTitle('Song übersprungen 💜')
              .setDescription('⏭️ Song wird übersprungen...')
              .setColor(0x8e44ad)
          ]
        });
        return;
      }

      if (interaction.commandName === 'pause') {
        const queue = queues.get(guild.id);
        if (!queue) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Keine Wiedergabe 💜')
                .setDescription('Aktuell wird keine Musik abgespielt.')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        if (queue.player.state.status === AudioPlayerStatus.Playing) {
          updateGuildListeningStats(guild.id);
        }
        queue.player.pause();
        await updateSession(guild.id);
        await interaction.editReply({
          embeds: [
            new EmbedBuilder()
              .setTitle('Musik pausiert 💜')
              .setDescription('Die Wiedergabe wurde pausiert.')
              .setColor(0x8e44ad)
          ]
        });
        return;
      }

      if (interaction.commandName === 'resume') {
        const queue = queues.get(guild.id);
        if (!queue) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Keine Wiedergabe 💜')
                .setDescription('Aktuell wird keine Musik abgespielt.')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        queue.player.unpause();
        initializeActiveListening(guild);
        await updateSession(guild.id);
        await interaction.editReply({
          embeds: [
            new EmbedBuilder()
              .setTitle('Musik fortgesetzt 💜')
              .setDescription('▶️ Musik wird fortgesetzt!')
              .setColor(0x8e44ad)
          ]
        });
        return;
      }

      if (interaction.commandName === 'stop') {
        const queue = queues.get(guild.id);
        if (!queue) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Keine Wiedergabe 💜')
                .setDescription('Aktuell wird keine Musik abgespielt.')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        queue.songs = [];
        queue.player.stop();
        if (
          queue.connection &&
          queue.connection.state.status !== VoiceConnectionStatus.Destroyed
        ) {
          try {
            queue.connection.destroy();
          } catch {}
        }
        destroyQueue(guild.id);
        await interaction.editReply({
          embeds: [
            new EmbedBuilder()
              .setTitle('Musik gestoppt 💜')
              .setDescription('Die Wiedergabe wurde gestoppt.')
              .setColor(0x8e44ad)
          ]
        });
        return;
      }

      if (interaction.commandName === 'shuffle') {
        const member = interaction.member;
        if (!member || !('voice' in member) || !member.voice.channel) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Fehler 💜')
                .setDescription('Du musst in einem Voice-Channel sein.')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        const queue = await getOrCreateQueue(
          guild,
          member,
          interaction.channel,
          true
        );
        if (!queue) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Fehler 💜')
                .setDescription('Verbindungsaufbau fehlgeschlagen!')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        const songs = await fetchPlaylist(9999);
        if (!songs.length) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Playlist leer 💜')
                .setDescription('Keine Songs in der Playlist gefunden.')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        for (let i = songs.length - 1; i > 0; i--) {
          const j = Math.floor(Math.random() * (i + 1));
          [songs[i], songs[j]] = [songs[j], songs[i]];
        }
        queue.songs = songs;
        if (!queue.playing) {
          queue.playing = true;
          playSong(guild);
        }
        await updateSession(guild.id);
        await interaction.editReply({
          embeds: [
            new EmbedBuilder()
              .setTitle('Shuffle-Modus 💜')
              .setDescription(
                'Shuffle-Modus aktiviert! Alle Songs werden zufällig abgespielt.'
              )
              .setColor(0x8e44ad)
          ]
        });
        return;
      }

      if (interaction.commandName === 'requestsong') {
        const id = interaction.options.getInteger('id');
        const member = interaction.member;
        if (!member || !('voice' in member) || !member.voice.channel) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Fehler 💜')
                .setDescription('Du musst in einem Voice-Channel sein!')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        const song = await fetchSongById(id);
        if (!song || !song.name) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Nicht gefunden 💜')
                .setDescription(
                  'Dieser Song ist auf Boocord Music nicht vorhanden.'
                )
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        const queue = await getOrCreateQueue(
          guild,
          member,
          interaction.channel,
          false
        );
        if (!queue) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Fehler 💜')
                .setDescription('Verbindungsaufbau fehlgeschlagen!')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        queue.songs.push(song);
        if (!queue.playing) {
          queue.playing = true;
          playSong(guild);
        }
        await updateSession(guild.id);
        await interaction.editReply({
          embeds: [
            new EmbedBuilder()
              .setTitle('Song hinzugefügt 💜')
              .setDescription(
                `Song **${song.name}** (ID: ${song.id}) wurde der Warteschlange hinzugefügt!`
              )
              .setColor(0x8e44ad)
          ]
        });
        return;
      }

      if (interaction.commandName === 'lyrics') {
        const queue = queues.get(guild.id);
        if (!queue || !queue.currentSong) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Keine Wiedergabe 💜')
                .setDescription('Es wird momentan kein Song abgespielt.')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        if (!queue.currentSong.lyrics) {
          const detailedSong = await fetchSongById(queue.currentSong.id);
          if (detailedSong?.lyrics) {
            queue.currentSong.lyrics = detailedSong.lyrics;
          }
        }
        if (!queue.currentSong.lyrics) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Lyrics nicht gefunden 💜')
                .setDescription(
                  'Für diesen Song sind leider keine Songtexte verfügbar.'
                )
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        const decoded = String(queue.currentSong.lyrics).replace(
          /&#039;/g,
          "'"
        );
        await interaction.editReply({
          embeds: [
            new EmbedBuilder()
              .setTitle(`📝 Songtexte für: ${queue.currentSong.name} 💜`)
              .setDescription('```' + decoded.substring(0, 2040) + '```')
              .setColor(0x8e44ad)
              .setTimestamp()
          ]
        });
        return;
      }

      if (interaction.commandName === 'favorites') {
        const userId = interaction.user.id;
        const favIds = await getFavorites(userId);
        if (!favIds.length) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Keine Favoriten')
                .setDescription('Du hast noch keine Favoriten gespeichert.')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        const songs = (
          await Promise.all(favIds.map(id => fetchSongById(id)))
        ).filter(Boolean);
        if (!songs.length) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Keine Favoriten')
                .setDescription('Keine gültigen Favoriten gefunden.')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }

        let currentPage = 0;
        const itemsPerPage = 10;
        const totalPages = Math.ceil(songs.length / itemsPerPage);

        const generateEmbed = page => {
          const start = page * itemsPerPage;
          const currentSongs = songs.slice(start, start + itemsPerPage);
          const embed = new EmbedBuilder()
            .setTitle('⭐ Deine Favoriten')
            .setColor(0x8e44ad)
            .setFooter({ text: `Seite ${page + 1} von ${totalPages}` });
          currentSongs.forEach((song, i) => {
            embed.addFields({
              name: `**${start + i + 1}. ${song.name}**`,
              value: song.thumbnail
                ? `[Cover anzeigen](https://music.boocord.com/${song.thumbnail})`
                : 'Kein Cover vorhanden',
              inline: false
            });
          });
          return embed;
        };

        const row = new ActionRowBuilder().addComponents(
          new ButtonBuilder()
            .setCustomId('fav_prev')
            .setLabel('⏮️ Zurück')
            .setStyle(ButtonStyle.Primary)
            .setDisabled(true),
          new ButtonBuilder()
            .setCustomId('fav_next')
            .setLabel('Weiter ⏭️')
            .setStyle(ButtonStyle.Primary)
            .setDisabled(totalPages <= 1)
        );

        await interaction.editReply({
          embeds: [generateEmbed(currentPage)],
          components: [row]
        });

        const favMessage = await interaction.fetchReply();
        const collector = favMessage.createMessageComponentCollector({
          time: 600000
        });

        collector.on('collect', async i => {
          if (i.customId === 'fav_prev') currentPage -= 1;
          else if (i.customId === 'fav_next') currentPage += 1;

          if (currentPage < 0) currentPage = 0;
          if (currentPage >= totalPages) currentPage = totalPages - 1;

          row.components[0].setDisabled(currentPage === 0);
          row.components[1].setDisabled(currentPage === totalPages - 1);

          try {
            await i.update({
              embeds: [generateEmbed(currentPage)],
              components: [row]
            });
          } catch (err) {
            if (err?.code !== 10062) console.error(err);
          }
        });

        collector.on('end', () => {
          favMessage.edit({ components: [] }).catch(() => {});
        });
        return;
      }

      if (interaction.commandName === 'favoplay') {
        const userId = interaction.user.id;
        const favIds = await getFavorites(userId);
        if (!favIds.length) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Keine Favoriten')
                .setDescription('Du hast noch keine Favoriten gespeichert.')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        const songs = (
          await Promise.all(favIds.map(id => fetchSongById(id)))
        ).filter(Boolean);
        if (!songs.length) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Keine Favoriten')
                .setDescription('Keine gültigen Favoriten gefunden.')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        const member = interaction.member;
        if (!member || !('voice' in member) || !member.voice.channel) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Fehler')
                .setDescription('Du musst in einem Voice-Channel sein.')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        const queue = await getOrCreateQueue(
          guild,
          member,
          interaction.channel,
          true
        );
        if (!queue) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Fehler')
                .setDescription('Verbindungsaufbau fehlgeschlagen!')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        for (let i = songs.length - 1; i > 0; i--) {
          const j = Math.floor(Math.random() * (i + 1));
          [songs[i], songs[j]] = [songs[j], songs[i]];
        }
        queue.songs = songs;
        if (!queue.playing) {
          queue.playing = true;
          playSong(guild);
        }
        await updateSession(guild.id);
        await interaction.editReply({
          embeds: [
            new EmbedBuilder()
              .setTitle('Favoriten abspielen')
              .setDescription(
                'Alle deine Favoriten werden jetzt als Shuffle abgespielt.'
              )
              .setColor(0x8e44ad)
          ]
        });
        return;
      }

      if (interaction.commandName === 'volume') {
        const value = interaction.options.getNumber('value');
        if (typeof value !== 'number' || value < 0 || value > 2.0) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Ungültiger Wert')
                .setDescription('Bitte gib einen Wert zwischen 0.0 und 2.0 ein.')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        const queue = queues.get(guild.id);
        if (!queue) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Keine Wiedergabe 💜')
                .setDescription('Aktuell wird keine Musik abgespielt.')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        queue.volume = value;
        if (queue.currentResource?.volume) {
          queue.currentResource.volume.setVolumeLogarithmic(value);
        }
        await updateSession(guild.id);
        await interaction.editReply({
          embeds: [
            new EmbedBuilder()
              .setTitle('Lautstärke geändert 💜')
              .setDescription(`Die Lautstärke wurde auf ${value} gesetzt.`)
              .setColor(0x8e44ad)
          ]
        });
        return;
      }

      if (interaction.commandName === 'favotoggle') {
        const userId = interaction.user.id;
        const queue = queues.get(guild.id);
        if (!queue || !queue.currentSong) {
          await interaction.editReply({
            embeds: [
              new EmbedBuilder()
                .setTitle('Keine Wiedergabe 💜')
                .setDescription('Kein Song läuft momentan.')
                .setColor(0x8e44ad)
            ]
          });
          return;
        }
        const songId = queue.currentSong.id;
        const favorites = await getFavorites(userId);
        let action;
        if (favorites.includes(songId)) {
          await removeFavorite(userId, songId);
          action = 'entfernt';
        } else {
          await addFavorite(userId, songId);
          action = 'hinzugefügt';
        }
        await interaction.editReply({
          embeds: [
            new EmbedBuilder()
              .setTitle('Favorit aktualisiert 💜')
              .setDescription(
                `Song wurde in deiner Favoriten-Liste ${action}.`
              )
              .setColor(0x8e44ad)
          ]
        });
        return;
      }

      if (interaction.commandName === 'stats') {
        const userId = interaction.user.id;
        const totalTime = await getUserStats(userId);
        const key = `${guild.id}-${userId}`;
        const currentSession = activeListening.has(key)
          ? Math.floor((Date.now() - activeListening.get(key)) / 1000)
          : 0;
        const totalListening = totalTime + currentSession;
        const hours = Math.floor(totalListening / 3600);
        const minutes = Math.floor((totalListening % 3600) / 60);
        const seconds = totalListening % 60;
        await interaction.editReply({
          embeds: [
            new EmbedBuilder()
              .setTitle('Deine Hörstatistik 💜')
              .setDescription(
                `Du hast insgesamt **${hours}h ${minutes}m ${seconds}s** Musik gehört.`
              )
              .setColor(0x8e44ad)
          ]
        });
        return;
      }
    }

    if (interaction.isButton()) {
      await interaction.deferUpdate();
      if (interaction.customId.startsWith('playlist_')) return;

      const customId = interaction.customId;
      const guild = interaction.guild;
      if (!guild) return;
      const queue = queues.get(guild.id);
      if (!queue) {
        await interaction.followUp({
          embeds: [
            new EmbedBuilder()
              .setTitle('Keine Wiedergabe 💜')
              .setDescription('Es wird aktuell kein Song abgespielt.')
              .setColor(0x8e44ad)
          ],
          ephemeral: true
        });
        return;
      }

      if (customId === 'toggle_favorite') {
        const userId = interaction.user.id;
        if (!queue.currentSong) {
          await interaction.followUp({
            content: 'Kein Song läuft momentan.',
            ephemeral: true
          });
          return;
        }
        const songId = queue.currentSong.id;
        const favorites = await getFavorites(userId);
        let action;
        if (favorites.includes(songId)) {
          await removeFavorite(userId, songId);
          action = 'entfernt';
        } else {
          await addFavorite(userId, songId);
          action = 'hinzugefügt';
        }
        await interaction.followUp({
          content: `Song wurde in deiner Favoriten-Liste ${action}.`,
          ephemeral: true
        });
        return;
      }

      if (customId === 'pause_resume') {
        if (queue.player.state.status === AudioPlayerStatus.Playing) {
          updateGuildListeningStats(guild.id);
          queue.player.pause();
          await interaction.followUp({
            embeds: [
              new EmbedBuilder()
                .setTitle('Musik pausiert 💜')
                .setDescription('Die Wiedergabe wurde pausiert.')
                .setColor(0x8e44ad)
            ],
            ephemeral: true
          });
        } else {
          queue.player.unpause();
          initializeActiveListening(guild);
          await interaction.followUp({
            embeds: [
              new EmbedBuilder()
                .setTitle('Musik fortgesetzt 💜')
                .setDescription('Die Wiedergabe wurde fortgesetzt.')
                .setColor(0x8e44ad)
            ],
            ephemeral: true
          });
        }
        await updateSession(guild.id);
        return;
      }

      if (customId === 'skip_song') {
        await interaction.followUp({
          embeds: [
            new EmbedBuilder()
              .setTitle('Song übersprungen 💜')
              .setDescription('Der nächste Song wird gestartet...')
              .setColor(0x8e44ad)
          ],
          ephemeral: true
        });
        queue.player.stop();
        await updateSession(guild.id);
        return;
      }

      if (customId === 'stop_song') {
        await interaction.followUp({
          embeds: [
            new EmbedBuilder()
              .setTitle('Musik gestoppt 💜')
              .setDescription('Die Wiedergabe wurde gestoppt.')
              .setColor(0x8e44ad)
          ],
          ephemeral: true
        });
        queue.songs = [];
        queue.player.stop();
        if (
          queue.connection &&
          queue.connection.state.status !== VoiceConnectionStatus.Destroyed
        ) {
          try {
            queue.connection.destroy();
          } catch {}
        }
        destroyQueue(guild.id);
        await updateSession(guild.id);
        return;
      }

      if (customId === 'toggle_shuffle') {
        queue.shuffleMode = !queue.shuffleMode;
        await interaction.followUp({
          embeds: [
            new EmbedBuilder()
              .setTitle('Shuffle-Modus geändert 💜')
              .setDescription(
                `Shuffle-Modus ist jetzt ${
                  queue.shuffleMode ? 'aktiviert' : 'deaktiviert'
                }.`
              )
              .setColor(0x8e44ad)
          ],
          ephemeral: true
        });
        updateControlEmbed(guild, queue.currentSong);
        await updateSession(guild.id);
        return;
      }

      if (customId === 'lyrics_button') {
        if (!queue.currentSong) {
          await interaction.followUp({
            embeds: [
              new EmbedBuilder()
                .setTitle('Keine Wiedergabe 💜')
                .setDescription('Momentan wird kein Song abgespielt.')
                .setColor(0x8e44ad)
            ],
            ephemeral: true
          });
          return;
        }
        if (!queue.currentSong.lyrics) {
          const detailedSong = await fetchSongById(queue.currentSong.id);
          if (detailedSong?.lyrics) {
            queue.currentSong.lyrics = detailedSong.lyrics;
          }
        }
        if (!queue.currentSong.lyrics) {
          await interaction.followUp({
            embeds: [
              new EmbedBuilder()
                .setTitle('Lyrics nicht gefunden 💜')
                .setDescription(
                  'Für diesen Song sind leider keine Songtexte verfügbar.'
                )
                .setColor(0x8e44ad)
            ],
            ephemeral: true
          });
          return;
        }
        const decoded = String(queue.currentSong.lyrics).replace(
          /&#039;/g,
          "'"
        );
        await interaction.followUp({
          embeds: [
            new EmbedBuilder()
              .setTitle(`Songtexte: ${queue.currentSong.name} 💜`)
              .setDescription('```' + decoded.substring(0, 2040) + '```')
              .setColor(0x8e44ad)
              .setTimestamp()
          ],
          ephemeral: true
        });
      }
    }
  } catch (err) {
    console.error('Fehler bei der Interaction:', err);
    try {
      if (interaction.isChatInputCommand()) {
        if (interaction.deferred || interaction.replied) {
          await interaction.editReply({ content: 'Ein Fehler ist aufgetreten.' });
        } else {
          await interaction.deferReply();
          await interaction.editReply({ content: 'Ein Fehler ist aufgetreten.' });
        }
      } else if (interaction.isButton()) {
        await interaction.followUp({
          content: 'Ein Fehler ist aufgetreten.',
          ephemeral: true
        });
      }
    } catch (e) {
      console.error('Fehler beim Senden der Fehlermeldung:', e);
    }
  }
});

function updateBotStatus() {
  try {
    const serverCount = client.guilds.cache.size;
    client.user.setPresence({
      activities: [
        { name: `${serverCount} Servers`, type: ActivityType.Playing }
      ],
      status: 'online'
    });
  } catch (e) {
    console.error('Fehler beim Setzen der Presence:', e);
  }
}

client
  .login(process.env.DISCORD_TOKEN)
  .catch(err => {
    console.error('Login fehlgeschlagen:', err);
    process.exit(1);
  });

async function gracefulShutdown(signal) {
  console.log(`Beende Prozess wegen ${signal}...`);
  try {
    for (const [guildId] of queues.entries()) {
      destroyQueue(guildId);
    }
    await pool.end();
  } catch (e) {
    console.error('Fehler beim Shutdown:', e);
  } finally {
    process.exit(0);
  }
}