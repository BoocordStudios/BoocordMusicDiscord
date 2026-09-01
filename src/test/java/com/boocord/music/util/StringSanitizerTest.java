package com.boocord.music.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class StringSanitizerTest {
  @Test
  void sanitizesAndTruncatesDisplayText() {
    assertEquals("Tom & Jerry", StringSanitizer.sanitizeLine("  Tom &amp;  Jerry\n", "fallback", 30));
    assertEquals("fallback", StringSanitizer.sanitizeLine(" \t ", "fallback", 30));
    assertEquals("ab...", StringSanitizer.truncate("abcdef", 5));
    assertEquals("..", StringSanitizer.truncate("abcdef", 2));
    assertEquals("😀...", StringSanitizer.truncate("😀abcdef", 5));
  }

  @Test
  void chunksLyricsWithoutBreakingDiscordCodeBlocks() {
    String oversizedLine = "a".repeat(AppConstants.MAX_LYRICS_CHUNK_LENGTH + 5);
    List<String> chunks = StringSanitizer.chunkLyrics("first```line\n" + oversizedLine);

    assertEquals(3, chunks.size());
    assertEquals("first'''line", chunks.get(0));
    assertEquals(AppConstants.MAX_LYRICS_CHUNK_LENGTH, chunks.get(1).length());
    assertEquals(5, chunks.get(2).length());
  }

  @Test
  void createsSafeAbsoluteUrls() {
    assertNull(StringSanitizer.toAbsoluteUrl("  "));
    assertEquals(
        "https://music.boocord.com/audio/My%20Song.mp3",
        StringSanitizer.toAbsoluteUrl("/audio/My Song.mp3")
    );
    assertEquals(
        "HTTPS://cdn.example.test/audio/My%20Song.mp3?token=abc",
        StringSanitizer.toAbsoluteUrl("HTTPS://cdn.example.test/audio/My Song.mp3?token=abc")
    );
  }

  @Test
  void formatsListeningTime() {
    assertEquals("0h 0m 0s", StringSanitizer.formatListeningTime(-10));
    assertEquals("1h 1m 1s", StringSanitizer.formatListeningTime(3_661));
  }
}
