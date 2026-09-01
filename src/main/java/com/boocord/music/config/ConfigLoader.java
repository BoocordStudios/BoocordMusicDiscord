package com.boocord.music.config;

import com.boocord.music.util.AppConstants;
import io.github.cdimascio.dotenv.Dotenv;
import java.util.Objects;
import java.util.function.Function;

public final class ConfigLoader {
  private final Function<String, String> valueLookup;

  public ConfigLoader() {
    Dotenv dotenv = Dotenv.configure()
        .ignoreIfMalformed()
        .ignoreIfMissing()
        .load();
    this.valueLookup = name -> {
      String systemValue = System.getenv(name);
      return systemValue != null && !systemValue.isBlank() ? systemValue : dotenv.get(name);
    };
  }

  ConfigLoader(Function<String, String> valueLookup) {
    this.valueLookup = Objects.requireNonNull(valueLookup);
  }

  public BotConfig load() {
    return new BotConfig(
        getRequired("DISCORD_TOKEN"),
        new BotConfig.DatabaseConfig(
            getRequired("DB_HOST"),
            getInt("DB_PORT", 3306, 1, 65_535),
            getRequired("DB_USER"),
            getRequired("DB_PASSWORD"),
            getRequired("DB_NAME")
        ),
        new BotConfig.ApiConfig(
            getInt("API_TIMEOUT_MS", AppConstants.DEFAULT_API_TIMEOUT_MS, 1_000, 120_000),
            getInt("API_RETRY_COUNT", AppConstants.DEFAULT_API_RETRY_COUNT, 0, 10),
            getLong("PLAYLIST_CACHE_TTL_MS", AppConstants.DEFAULT_PLAYLIST_CACHE_TTL_MS, 1_000),
            getLong("SONG_CACHE_TTL_MS", AppConstants.DEFAULT_SONG_CACHE_TTL_MS, 1_000)
        ),
        new BotConfig.PlayerConfig(
            getInt("PLAYER_IDLE_TIMEOUT_MS", AppConstants.DEFAULT_IDLE_TIMEOUT_MS, 30_000, Integer.MAX_VALUE),
            getInt("PLAYER_DEFAULT_VOLUME_PERCENT", AppConstants.DEFAULT_VOLUME_PERCENT, 0, 200)
        )
    );
  }

  private String getRequired(String name) {
    String value = getOptional(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Fehlende Umgebungsvariable: " + name);
    }
    return value.trim();
  }

  private int getInt(String name, int fallback, int min, int max) {
    String value = getOptional(name);
    if (value == null || value.isBlank()) {
      return fallback;
    }

    try {
      int parsed = Integer.parseInt(value.trim());
      if (parsed < min || parsed > max) {
        throw new IllegalArgumentException();
      }
      return parsed;
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Ungültige Zahl in " + name + ": " + value);
    }
  }

  private long getLong(String name, long fallback, long min) {
    String value = getOptional(name);
    if (value == null || value.isBlank()) {
      return fallback;
    }

    try {
      long parsed = Long.parseLong(value.trim());
      if (parsed < min) {
        throw new IllegalArgumentException();
      }
      return parsed;
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Ungültige Zahl in " + name + ": " + value);
    }
  }

  private String getOptional(String name) {
    return valueLookup.apply(name);
  }
}
