package com.boocord.music.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {
  @Test
  void loadsRequiredValuesAndDefaults() {
    BotConfig config = new ConfigLoader(requiredValues()::get).load();

    assertEquals("token", config.discordToken());
    assertEquals(3306, config.database().port());
    assertEquals(15_000, config.api().timeoutMs());
    assertEquals(100, config.player().defaultVolumePercent());
  }

  @Test
  void rejectsMissingRequiredValues() {
    Map<String, String> values = requiredValues();
    values.remove("DISCORD_TOKEN");

    assertThrows(IllegalStateException.class, () -> new ConfigLoader(values::get).load());
  }

  @Test
  void rejectsNumericValuesOutsideSafeRanges() {
    Map<String, String> invalidPort = requiredValues();
    invalidPort.put("DB_PORT", "65536");
    assertThrows(IllegalStateException.class, () -> new ConfigLoader(invalidPort::get).load());

    Map<String, String> invalidVolume = requiredValues();
    invalidVolume.put("PLAYER_DEFAULT_VOLUME_PERCENT", "201");
    assertThrows(IllegalStateException.class, () -> new ConfigLoader(invalidVolume::get).load());
  }

  private Map<String, String> requiredValues() {
    Map<String, String> values = new HashMap<>();
    values.put("DISCORD_TOKEN", "token");
    values.put("DB_HOST", "127.0.0.1");
    values.put("DB_USER", "user");
    values.put("DB_PASSWORD", "password");
    values.put("DB_NAME", "database");
    return values;
  }
}
