package com.boocord.music.api;

import com.boocord.music.config.BotConfig;
import com.boocord.music.model.PlaylistResponse;
import com.boocord.music.model.Song;
import com.boocord.music.util.AppConstants;
import com.boocord.music.util.StringSanitizer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class BoocordApiClient {
  private static final int MAX_CACHE_ENTRIES = 1_000;

  private final HttpClient httpClient;
  private final Gson gson;
  private final BotConfig.ApiConfig config;
  private final Map<String, CacheEntry> cache;

  public BoocordApiClient(BotConfig.ApiConfig config) {
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.timeoutMs()))
        .build();
    this.gson = new Gson();
    this.config = config;
    this.cache = new ConcurrentHashMap<>();
  }

  public List<Song> getAllSongs() {
    return fetchPlaylist(AppConstants.MAX_API_PLAYLIST_LIMIT, "", null, null, null, null, false)
        .songs();
  }

  public PlaylistResponse fetchPlaylist(
      int limit,
      String search,
      String mood,
      String artist,
      String album,
      String playlist,
      boolean forceRefresh
  ) {
    int safeLimit = Math.max(1, Math.min(limit, AppConstants.MAX_API_PLAYLIST_LIMIT));
    Map<String, String> query = new HashMap<>();
    query.put("limit", String.valueOf(safeLimit));
    putIfPresent(query, "search", search == null ? null : StringSanitizer.collapseWhitespace(search));
    putIfPresent(query, "mood", mood);
    putIfPresent(query, "artist", artist);
    putIfPresent(query, "album", album);
    putIfPresent(query, "playlist", playlist);

    JsonObject data = requestJson(
        "playlist_v2.php",
        query,
        cacheKey("playlist", query),
        config.playlistCacheTtlMs(),
        forceRefresh,
        false
    );

    List<Song> songs = new ArrayList<>();
    JsonArray array = data.has("songs") && data.get("songs").isJsonArray()
        ? data.getAsJsonArray("songs")
        : new JsonArray();
    for (JsonElement element : array) {
      if (!element.isJsonObject()) {
        continue;
      }
      Song song = normalizeSong(element.getAsJsonObject());
      if (song != null) {
        songs.add(song);
      }
    }

    return new PlaylistResponse(
        data.has("hasMore") && data.get("hasMore").getAsBoolean(),
        StringSanitizer.sanitizeLine(readString(data, "playlistName"), "Playlist", 120),
        StringSanitizer.sanitizeLine(readString(data, "playlistDesc"), "Keine Beschreibung verfügbar.", 300),
        songs
    );
  }

  public Song getSongById(int id) {
    if (id <= 0) {
      return null;
    }

    JsonObject data = requestJson(
        "song_v2.php",
        Map.of("id", String.valueOf(id)),
        cacheKey("song", Map.of("id", String.valueOf(id))),
        config.songCacheTtlMs(),
        false,
        true
    );

    return data == null ? null : normalizeSong(data);
  }

  public List<Song> getSongsByIds(Collection<Integer> ids) {
    List<Song> songs = new ArrayList<>();
    int processedIds = 0;
    for (Integer id : new LinkedHashSet<>(ids)) {
      if (processedIds++ >= AppConstants.MAX_API_PLAYLIST_LIMIT) {
        break;
      }
      if (id == null) {
        continue;
      }
      Song song = getSongById(id);
      if (song != null) {
        songs.add(song);
      }
    }
    return songs;
  }

  public Song resolvePlayQuery(String query) {
    List<Song> songs = getAllSongs();
    if (songs.isEmpty()) {
      return null;
    }

    String normalizedQuery = StringSanitizer.collapseWhitespace(query).toLowerCase(Locale.ROOT);
    if (normalizedQuery.isBlank()) {
      return null;
    }

    if (normalizedQuery.chars().allMatch(Character::isDigit)) {
      try {
        long index = Long.parseLong(normalizedQuery) - 1;
        if (index >= 0 && index < songs.size()) {
          return songs.get((int) index);
        }
      } catch (NumberFormatException ignored) {
        // An oversized numeric query cannot be a valid playlist position.
      }
    }

    return songs.stream()
        .map(song -> Map.entry(song, scoreSong(song, normalizedQuery)))
        .filter(entry -> entry.getValue() > 0)
        .max(Comparator.comparingInt(Map.Entry::getValue))
        .map(Map.Entry::getKey)
        .orElse(null);
  }

  public Song toSessionSong(Song song) {
    if (song == null) {
      return null;
    }

    return new Song(
        song.id(),
        StringSanitizer.sanitizeLine(song.name(), "Unbekannter Song", 120),
        StringSanitizer.sanitizeLine(song.artistName(), "Unbekannt", 80),
        StringSanitizer.sanitizeLine(song.nickname(), "Unbekannt", 80),
        StringSanitizer.sanitizeLine(song.moodName(), "Unbekannt", 80),
        StringSanitizer.sanitizeLine(song.albumName(), "", 80),
        song.path(),
        StringSanitizer.toAbsoluteUrl(song.streamUrl() != null ? song.streamUrl() : song.path()),
        song.thumbnail(),
        StringSanitizer.toAbsoluteUrl(song.thumbnailUrl() != null ? song.thumbnailUrl() : song.thumbnail()),
        song.lyrics() == null ? null : StringSanitizer.normalizeLyrics(song.lyrics())
    );
  }

  private Song normalizeSong(JsonObject song) {
    Integer id = readInt(song, "id");
    String streamUrl = StringSanitizer.toAbsoluteUrl(
        coalesce(readString(song, "path"), readString(song, "streamUrl"))
    );
    if (id == null || id <= 0 || streamUrl == null) {
      return null;
    }

    return new Song(
        id,
        StringSanitizer.sanitizeLine(readString(song, "name"), "Unbekannter Song", 120),
        StringSanitizer.sanitizeLine(coalesce(readString(song, "artist_name"), readString(song, "artistName")), "Unbekannt", 80),
        StringSanitizer.sanitizeLine(readString(song, "nickname"), "Unbekannt", 80),
        StringSanitizer.sanitizeLine(coalesce(readString(song, "mood_name"), readString(song, "moodName")), "Unbekannt", 80),
        StringSanitizer.sanitizeLine(coalesce(readString(song, "album_name"), readString(song, "albumName")), "", 80),
        readString(song, "path"),
        streamUrl,
        readString(song, "thumbnail"),
        StringSanitizer.toAbsoluteUrl(coalesce(readString(song, "thumbnail"), readString(song, "thumbnailUrl"))),
        song.has("lyrics") && !song.get("lyrics").isJsonNull()
            ? StringSanitizer.normalizeLyrics(song.get("lyrics").getAsString())
            : null
    );
  }

  private int scoreSong(Song song, String normalizedQuery) {
    List<String> haystacks = List.of(
        nullableLower(song.name()),
        nullableLower(song.artistName()),
        nullableLower(song.nickname()),
        nullableLower(song.artistName() + " " + song.name()),
        nullableLower(song.nickname() + " " + song.name())
    );

    int score = 0;
    for (String value : haystacks) {
      if (value == null || value.isBlank()) {
        continue;
      }

      if (value.equals(normalizedQuery)) {
        score = Math.max(score, 100);
      } else if (value.startsWith(normalizedQuery)) {
        score = Math.max(score, 80);
      } else if (value.contains(normalizedQuery)) {
        score = Math.max(score, 60);
      }
    }
    return score;
  }

  private JsonObject requestJson(
      String path,
      Map<String, String> params,
      String cacheKey,
      long ttlMs,
      boolean forceRefresh,
      boolean allow404
  ) {
    long now = System.currentTimeMillis();
    if (!forceRefresh && cacheKey != null) {
      CacheEntry cached = cache.get(cacheKey);
      if (cached != null && cached.expiresAt > now) {
        return cached.value;
      }
      if (cached != null) {
        cache.remove(cacheKey, cached);
      }
    }

    URI uri = URI.create(AppConstants.API_REQUEST_BASE_URL + "/" + path + buildQueryString(params));
    IOException lastIoException = null;
    InterruptedException lastInterrupt = null;
    RuntimeException lastRuntime = null;

    for (int attempt = 0; attempt <= config.retryCount(); attempt++) {
      try {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(config.timeoutMs()))
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (allow404 && response.statusCode() == 404) {
          return null;
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          throw new IllegalStateException("HTTP " + response.statusCode() + " für " + uri.getPath());
        }

        JsonObject json = gson.fromJson(response.body(), JsonObject.class);
        if (cacheKey != null && ttlMs > 0) {
          cacheResponse(cacheKey, json, ttlMs);
        }
        return json;
      } catch (IOException exception) {
        lastIoException = exception;
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        lastInterrupt = exception;
        break;
      } catch (RuntimeException exception) {
        lastRuntime = exception;
      }
    }

    if (lastInterrupt != null) {
      throw new IllegalStateException("API-Anfrage wurde unterbrochen.", lastInterrupt);
    }
    if (lastIoException != null) {
      throw new IllegalStateException("API-Anfrage fehlgeschlagen.", lastIoException);
    }
    throw Objects.requireNonNullElseGet(lastRuntime, () -> new IllegalStateException("API-Anfrage fehlgeschlagen."));
  }

  private String buildQueryString(Map<String, String> params) {
    if (params.isEmpty()) {
      return "";
    }

    StringBuilder builder = new StringBuilder("?");
    boolean first = true;
    for (Map.Entry<String, String> entry : params.entrySet()) {
      if (entry.getValue() == null || entry.getValue().isBlank()) {
        continue;
      }
      if (!first) {
        builder.append('&');
      }
      builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
      builder.append('=');
      builder.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
      first = false;
    }
    return first ? "" : builder.toString();
  }

  private String cacheKey(String prefix, Map<String, String> data) {
    return prefix + ":" + gson.toJson(data);
  }

  private void cacheResponse(String key, JsonObject value, long ttlMs) {
    long now = System.currentTimeMillis();
    if (cache.size() >= MAX_CACHE_ENTRIES) {
      cache.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
    }
    if (cache.size() >= MAX_CACHE_ENTRIES) {
      cache.entrySet().stream()
          .min(Comparator.comparingLong(entry -> entry.getValue().expiresAt))
          .ifPresent(entry -> cache.remove(entry.getKey(), entry.getValue()));
    }
    cache.put(key, new CacheEntry(value, now + ttlMs));
  }

  private void putIfPresent(Map<String, String> data, String key, String value) {
    if (value != null && !value.isBlank()) {
      data.put(key, value);
    }
  }

  private String readString(JsonObject object, String key) {
    return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
  }

  private Integer readInt(JsonObject object, String key) {
    if (!object.has(key) || object.get(key).isJsonNull()) {
      return null;
    }

    try {
      return object.get(key).getAsInt();
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private String coalesce(String first, String second) {
    return first != null && !first.isBlank() ? first : second;
  }

  private String nullableLower(String value) {
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }

  private record CacheEntry(JsonObject value, long expiresAt) {
  }
}
