package com.boocord.music.util;

import com.boocord.music.model.UserLanguage;

/** An expected interaction rejection which is safe to show to the user. */
public final class UserFacingException extends RuntimeException {
  private final String englishMessage;

  public UserFacingException(String message) {
    this(message, message);
  }

  public UserFacingException(String germanMessage, String englishMessage) {
    super(germanMessage);
    this.englishMessage = englishMessage;
  }

  public String getMessage(UserLanguage language) {
    return language == UserLanguage.ENGLISH ? englishMessage : getMessage();
  }
}
