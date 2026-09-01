package com.boocord.music.model;

public enum RepeatMode {
  OFF,
  TRACK,
  QUEUE;

  public String toDatabaseValue() {
    return name().toLowerCase();
  }

  public static RepeatMode fromDatabaseValue(String value) {
    if (value == null || value.isBlank()) {
      return OFF;
    }

    return switch (value.trim().toLowerCase()) {
      case "track" -> TRACK;
      case "queue" -> QUEUE;
      default -> OFF;
    };
  }
}
