package com.boocord.music.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

public final class StringSanitizer {
  private static final Map<String, String> HTML_ENTITIES = Map.of(
      "&#039;", "'",
      "&amp;", "&",
      "&quot;", "\"",
      "&lt;", "<",
      "&gt;", ">"
  );

  private StringSanitizer() {
  }

  public static String decodeHtmlEntities(String value) {
    String output = value == null ? "" : value;
    for (Map.Entry<String, String> entry : HTML_ENTITIES.entrySet()) {
      output = output.replace(entry.getKey(), entry.getValue());
    }
    return output;
  }

  public static String collapseWhitespace(String value) {
    return (value == null ? "" : value)
        .replace("\r\n", "\n")
        .replaceAll("\\s+", " ")
        .trim();
  }

  public static String truncate(String value, int maxLength) {
    return truncate(value, maxLength, "...");
  }

  public static String truncate(String value, int maxLength, String suffix) {
    String normalized = value == null ? "" : value;
    int safeMax = Math.max(1, maxLength);
    if (normalized.length() <= safeMax) {
      return normalized;
    }

    String safeSuffix = suffix == null ? "" : suffix;
    if (safeSuffix.length() >= safeMax) {
      return safeSuffix.substring(0, safeMax);
    }

    int sliceLength = safeMax - safeSuffix.length();
    if (sliceLength > 0 && Character.isHighSurrogate(normalized.charAt(sliceLength - 1))) {
      sliceLength--;
    }
    return normalized.substring(0, sliceLength).trim() + safeSuffix;
  }

  public static String sanitizeLine(String value, String fallback, int maxLength) {
    String normalized = collapseWhitespace(decodeHtmlEntities(value));
    if (normalized.isBlank()) {
      return fallback;
    }
    return truncate(normalized, maxLength);
  }

  public static String sanitizeLine(String value) {
    return sanitizeLine(value, "Unbekannt", 200);
  }

  public static String normalizeLyrics(String value) {
    return decodeHtmlEntities(value == null ? "" : value)
        .replace("\r\n", "\n")
        .replace("\u0000", "")
        .trim();
  }

  public static String stripCodeBlocks(String value) {
    return (value == null ? "" : value).replace("```", "'''");
  }

  public static List<String> chunkLyrics(String lyrics) {
    String safeText = stripCodeBlocks(normalizeLyrics(lyrics));
    if (safeText.isBlank()) {
      return List.of();
    }

    List<String> chunks = new ArrayList<>();
    StringBuilder currentChunk = new StringBuilder();
    for (String line : safeText.split("\n")) {
      String candidate = currentChunk.isEmpty() ? line : currentChunk + "\n" + line;

      if (candidate.length() <= AppConstants.MAX_LYRICS_CHUNK_LENGTH) {
        currentChunk.setLength(0);
        currentChunk.append(candidate);
        continue;
      }

      if (!currentChunk.isEmpty()) {
        chunks.add(currentChunk.toString());
        currentChunk.setLength(0);
      }

      if (line.length() <= AppConstants.MAX_LYRICS_CHUNK_LENGTH) {
        currentChunk.append(line);
        continue;
      }

      for (int index = 0; index < line.length(); index += AppConstants.MAX_LYRICS_CHUNK_LENGTH) {
        int endIndex = Math.min(line.length(), index + AppConstants.MAX_LYRICS_CHUNK_LENGTH);
        chunks.add(line.substring(index, endIndex));
      }
    }

    if (!currentChunk.isEmpty()) {
      chunks.add(currentChunk.toString());
    }

    return chunks;
  }

  public static String toAbsoluteUrl(String pathOrUrl) {
    String normalized = pathOrUrl == null ? "" : pathOrUrl.trim();
    if (normalized.isBlank()) {
      return null;
    }

    String absoluteUrl;
    if (normalized.regionMatches(true, 0, "http://", 0, 7)
        || normalized.regionMatches(true, 0, "https://", 0, 8)) {
      absoluteUrl = normalized;
    } else {
      String trimmed = normalized.replaceFirst("^/+", "");
      absoluteUrl = AppConstants.API_BASE_URL + "/" + trimmed;
    }

    return encodeUrlPath(absoluteUrl);
  }

  public static String formatListeningTime(long totalSeconds) {
    long safeSeconds = Math.max(0, totalSeconds);
    long hours = safeSeconds / 3_600;
    long minutes = (safeSeconds % 3_600) / 60;
    long seconds = safeSeconds % 60;
    return hours + "h " + minutes + "m " + seconds + "s";
  }

  private static String encodeUrlPath(String value) {
    int schemeSeparator = value.indexOf("://");
    if (schemeSeparator < 0) {
      return encodePathComponent(value);
    }

    int queryIndex = value.indexOf('?');
    int fragmentIndex = value.indexOf('#');
    int pathEnd = value.length();
    if (queryIndex >= 0) {
      pathEnd = Math.min(pathEnd, queryIndex);
    }
    if (fragmentIndex >= 0) {
      pathEnd = Math.min(pathEnd, fragmentIndex);
    }

    String prefix = value.substring(0, schemeSeparator + 3);
    String remainder = value.substring(schemeSeparator + 3, pathEnd);
    String suffix = value.substring(pathEnd);

    int firstSlash = remainder.indexOf('/');
    if (firstSlash < 0) {
      return value;
    }

    String authority = remainder.substring(0, firstSlash);
    String path = remainder.substring(firstSlash);
    return prefix + authority + encodePathComponent(path) + suffix;
  }

  private static String encodePathComponent(String value) {
    StringBuilder encoded = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); ) {
      int codePoint = value.codePointAt(index);
      int charCount = Character.charCount(codePoint);

      if (codePoint == '/' || isAllowedPathCodePoint(codePoint) || isPercentEncoded(value, index)) {
        encoded.appendCodePoint(codePoint);
      } else {
        byte[] bytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
        for (byte current : bytes) {
          encoded.append('%');
          encoded.append(Character.toUpperCase(Character.forDigit((current >> 4) & 0xF, 16)));
          encoded.append(Character.toUpperCase(Character.forDigit(current & 0xF, 16)));
        }
      }

      index += charCount;
    }
    return encoded.toString();
  }

  private static boolean isAllowedPathCodePoint(int codePoint) {
    return (codePoint >= 'a' && codePoint <= 'z')
        || (codePoint >= 'A' && codePoint <= 'Z')
        || (codePoint >= '0' && codePoint <= '9')
        || "-._~!$&'()*+,;=:@".indexOf(codePoint) >= 0;
  }

  private static boolean isPercentEncoded(String value, int index) {
    return value.charAt(index) == '%'
        && index + 2 < value.length()
        && isHexDigit(value.charAt(index + 1))
        && isHexDigit(value.charAt(index + 2));
  }

  private static boolean isHexDigit(char value) {
    return (value >= '0' && value <= '9')
        || (value >= 'a' && value <= 'f')
        || (value >= 'A' && value <= 'F');
  }
}
