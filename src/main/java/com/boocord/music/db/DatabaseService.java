package com.boocord.music.db;

import com.boocord.music.config.BotConfig;
import com.boocord.music.model.PlaybackSession;
import com.boocord.music.model.Song;
import com.boocord.music.model.UserLanguage;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DatabaseService implements AutoCloseable {
  private static final Logger LOGGER = Logger.getLogger(DatabaseService.class.getName());
  private static final int MAX_PERSISTED_QUEUE_SONGS = 100;
  private static final Type SONG_LIST_TYPE = new TypeToken<List<Song>>() {
  }.getType();

  private final HikariDataSource dataSource;
  private final Gson gson;

  public DatabaseService(BotConfig.DatabaseConfig config) {
    HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(
        "jdbc:mysql://" + config.host() + ":" + config.port() + "/" + config.database()
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
    );
    hikariConfig.setUsername(config.user());
    hikariConfig.setPassword(config.password());
    hikariConfig.setMaximumPoolSize(10);
    hikariConfig.setPoolName("boocord-music");
    this.dataSource = new HikariDataSource(hikariConfig);
    this.gson = new Gson();
  }

  public void initialize() throws SQLException {
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE TABLE IF NOT EXISTS playback_sessions (
            guild_id VARCHAR(64) PRIMARY KEY,
            voice_channel_id VARCHAR(64),
            text_channel_id VARCHAR(64),
            control_message_id VARCHAR(64),
            current_song LONGTEXT,
            queue LONGTEXT,
            shuffle_mode TINYINT(1) DEFAULT 0,
            playing TINYINT(1) DEFAULT 0,
            paused TINYINT(1) DEFAULT 0,
            volume INT DEFAULT 100,
            repeat_mode VARCHAR(16) DEFAULT 'off',
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
              ON UPDATE CURRENT_TIMESTAMP
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);

      statement.execute("""
          CREATE TABLE IF NOT EXISTS user_favorites (
            user_id VARCHAR(64) NOT NULL,
            song_id INT NOT NULL,
            PRIMARY KEY (user_id, song_id)
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);

      statement.execute("""
          CREATE TABLE IF NOT EXISTS user_stats (
            user_id VARCHAR(64) PRIMARY KEY,
            listening_time BIGINT DEFAULT 0
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);

      statement.execute("""
          CREATE TABLE IF NOT EXISTS user_settings (
            user_id VARCHAR(64) PRIMARY KEY,
            language VARCHAR(8) NOT NULL DEFAULT 'de'
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);
    }

    ensureColumn("playback_sessions", "paused", "TINYINT(1) DEFAULT 0");
    ensureColumn("playback_sessions", "volume", "INT DEFAULT 100");
    ensureColumn("playback_sessions", "repeat_mode", "VARCHAR(16) DEFAULT 'off'");
    ensureColumn("user_stats", "listening_time", "BIGINT DEFAULT 0");
    ensureLongTextColumn("playback_sessions", "current_song");
    ensureLongTextColumn("playback_sessions", "queue");
  }

  public void savePlaybackSession(PlaybackSession session) throws SQLException {
    String sql = """
        INSERT INTO playback_sessions (
          guild_id, voice_channel_id, text_channel_id, control_message_id,
          current_song, queue, shuffle_mode, playing, paused, volume, repeat_mode
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
          voice_channel_id = VALUES(voice_channel_id),
          text_channel_id = VALUES(text_channel_id),
          control_message_id = VALUES(control_message_id),
          current_song = VALUES(current_song),
          queue = VALUES(queue),
          shuffle_mode = VALUES(shuffle_mode),
          playing = VALUES(playing),
          paused = VALUES(paused),
          volume = VALUES(volume),
          repeat_mode = VALUES(repeat_mode)
        """;

    try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
      List<Song> persistedQueue = compactQueue(session.queue());
      statement.setString(1, session.guildId());
      statement.setString(2, session.voiceChannelId());
      statement.setString(3, session.textChannelId());
      statement.setString(4, session.controlMessageId());
      statement.setString(5, session.currentSong() == null ? null : gson.toJson(compactSong(session.currentSong())));
      statement.setString(6, gson.toJson(persistedQueue));
      statement.setBoolean(7, session.shuffleMode());
      statement.setBoolean(8, session.playing());
      statement.setBoolean(9, session.paused());
      statement.setInt(10, session.volume());
      statement.setString(11, session.repeatMode());
      statement.executeUpdate();
    }
  }

  public void deletePlaybackSession(String guildId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM playback_sessions WHERE guild_id = ?"
         )) {
      statement.setString(1, guildId);
      statement.executeUpdate();
    }
  }

  public List<PlaybackSession> getPlaybackSessions() throws SQLException {
    List<PlaybackSession> sessions = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement("SELECT * FROM playback_sessions");
         ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        sessions.add(new PlaybackSession(
            resultSet.getString("guild_id"),
            resultSet.getString("voice_channel_id"),
            resultSet.getString("text_channel_id"),
            resultSet.getString("control_message_id"),
            parseSong(resultSet.getString("current_song")),
            parseSongList(resultSet.getString("queue")),
            resultSet.getBoolean("shuffle_mode"),
            resultSet.getBoolean("playing"),
            resultSet.getBoolean("paused"),
            resultSet.getInt("volume"),
            resultSet.getString("repeat_mode")
        ));
      }
    }
    return sessions;
  }

  public void addFavorite(String userId, int songId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT IGNORE INTO user_favorites (user_id, song_id) VALUES (?, ?)"
         )) {
      statement.setString(1, userId);
      statement.setInt(2, songId);
      statement.executeUpdate();
    }
  }

  public void removeFavorite(String userId, int songId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM user_favorites WHERE user_id = ? AND song_id = ?"
         )) {
      statement.setString(1, userId);
      statement.setInt(2, songId);
      statement.executeUpdate();
    }
  }

  public List<Integer> getFavorites(String userId) throws SQLException {
    List<Integer> favorites = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT song_id FROM user_favorites WHERE user_id = ? ORDER BY song_id DESC"
         )) {
      statement.setString(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          favorites.add(resultSet.getInt("song_id"));
        }
      }
    }
    return favorites;
  }

  public void updateUserStats(String userId, long seconds) throws SQLException {
    long safeSeconds = Math.max(0, seconds);
    if (safeSeconds <= 0) {
      return;
    }

    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement("""
             INSERT INTO user_stats (user_id, listening_time)
             VALUES (?, ?)
             ON DUPLICATE KEY UPDATE listening_time = listening_time + ?
             """)) {
      statement.setString(1, userId);
      statement.setLong(2, safeSeconds);
      statement.setLong(3, safeSeconds);
      statement.executeUpdate();
    }
  }

  public long getUserStats(String userId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT listening_time FROM user_stats WHERE user_id = ?"
         )) {
      statement.setString(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          return resultSet.getLong("listening_time");
        }
      }
    }
    return 0L;
  }

  public UserLanguage getUserLanguage(String userId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT language FROM user_settings WHERE user_id = ?"
         )) {
      statement.setString(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          return UserLanguage.fromCode(resultSet.getString("language"));
        }
      }
    }
    return UserLanguage.GERMAN;
  }

  public void setUserLanguage(String userId, UserLanguage language) throws SQLException {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement("""
             INSERT INTO user_settings (user_id, language)
             VALUES (?, ?)
             ON DUPLICATE KEY UPDATE language = VALUES(language)
             """)) {
      statement.setString(1, userId);
      statement.setString(2, language.code());
      statement.executeUpdate();
    }
  }

  @Override
  public void close() {
    dataSource.close();
  }

  private void ensureColumn(String table, String column, String definition) throws SQLException {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement checkStatement = connection.prepareStatement(
             "SHOW COLUMNS FROM `" + table + "` LIKE ?"
         )) {
      checkStatement.setString(1, column);
      try (ResultSet resultSet = checkStatement.executeQuery()) {
        if (resultSet.next()) {
          return;
        }
      }
    }

    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
    }
  }

  private void ensureLongTextColumn(String table, String column) throws SQLException {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(
             "SHOW COLUMNS FROM `" + table + "` LIKE ?"
         )) {
      statement.setString(1, column);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return;
        }

        String type = resultSet.getString("Type");
        if (type != null && "longtext".equalsIgnoreCase(type)) {
          return;
        }
      }
    }

    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute(
          "ALTER TABLE `" + table + "` MODIFY COLUMN `" + column + "` LONGTEXT CHARACTER SET utf8mb4"
      );
    }
  }

  private List<Song> compactQueue(List<Song> queue) {
    if (queue == null || queue.isEmpty()) {
      return List.of();
    }

    return queue.stream()
        .limit(MAX_PERSISTED_QUEUE_SONGS)
        .map(this::compactSong)
        .toList();
  }

  private Song compactSong(Song song) {
    if (song == null) {
      return null;
    }

    return new Song(
        song.id(),
        song.name(),
        song.artistName(),
        song.nickname(),
        song.moodName(),
        song.albumName(),
        song.path(),
        song.streamUrl(),
        song.thumbnail(),
        song.thumbnailUrl(),
        null
    );
  }

  private Song parseSong(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return gson.fromJson(raw, Song.class);
    } catch (RuntimeException exception) {
      LOGGER.log(Level.WARNING, "Ungültige Songdaten in einer gespeicherten Playback-Session.", exception);
      return null;
    }
  }

  private List<Song> parseSongList(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    try {
      List<Song> songs = gson.fromJson(raw, SONG_LIST_TYPE);
      return songs == null ? List.of() : songs.stream().filter(Objects::nonNull).toList();
    } catch (RuntimeException exception) {
      LOGGER.log(Level.WARNING, "Ungültige Queue-Daten in einer gespeicherten Playback-Session.", exception);
      return List.of();
    }
  }
}
