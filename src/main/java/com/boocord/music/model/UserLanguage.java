package com.boocord.music.model;

import java.util.Locale;

public enum UserLanguage {
  GERMAN("de"),
  ENGLISH("en");

  private final String code;

  UserLanguage(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }

  public String text(String german, String english) {
    return this == ENGLISH ? english : german;
  }

  public static UserLanguage fromCode(String code) {
    if (code == null) {
      return GERMAN;
    }

    return switch (code.trim().toLowerCase(Locale.ROOT)) {
      case "en", "english" -> ENGLISH;
      default -> GERMAN;
    };
  }
}
