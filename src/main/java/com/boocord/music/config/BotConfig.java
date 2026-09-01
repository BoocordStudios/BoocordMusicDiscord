package com.boocord.music.config;

public record BotConfig(
    String discordToken,
    DatabaseConfig database,
    ApiConfig api,
    PlayerConfig player
) {
  public record DatabaseConfig(
      String host,
      int port,
      String user,
      String password,
      String database
  ) {
  }

  public record ApiConfig(
      int timeoutMs,
      int retryCount,
      long playlistCacheTtlMs,
      long songCacheTtlMs
  ) {
  }

  public record PlayerConfig(
      int idleTimeoutMs,
      int defaultVolumePercent
  ) {
  }
}
