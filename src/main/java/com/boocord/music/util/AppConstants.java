package com.boocord.music.util;

public final class AppConstants {
  public static final int PRIMARY_COLOR = 0x9B59FF;
  public static final String API_BASE_URL = "https://music.boocord.com";
  public static final String API_REQUEST_BASE_URL = API_BASE_URL + "/api/request";
  public static final int MAX_API_PLAYLIST_LIMIT = 1_000;
  public static final int DEFAULT_VOLUME_PERCENT = 100;
  public static final int DEFAULT_IDLE_TIMEOUT_MS = 5 * 60 * 1_000;
  public static final long DEFAULT_PLAYLIST_CACHE_TTL_MS = 60_000L;
  public static final long DEFAULT_SONG_CACHE_TTL_MS = 600_000L;
  public static final int DEFAULT_API_TIMEOUT_MS = 15_000;
  public static final int DEFAULT_API_RETRY_COUNT = 2;
  public static final int MAX_LIST_ITEMS_PER_PAGE = 10;
  public static final int MAX_LYRICS_CHUNK_LENGTH = 1_800;
  public static final String PAGINATION_PREFIX = "page";

  private AppConstants() {
  }

  public static final class ButtonIds {
    public static final String PAUSE_RESUME = "pause_resume";
    public static final String SKIP = "skip_song";
    public static final String STOP = "stop_song";
    public static final String TOGGLE_FAVORITE = "toggle_favorite";
    public static final String LYRICS = "lyrics_button";
    public static final String TOGGLE_SHUFFLE = "toggle_shuffle";
    public static final String TOGGLE_LOOP_SONG = "toggle_loop_song";
    public static final String TOGGLE_REPEAT_QUEUE = "toggle_repeat_queue";

    private ButtonIds() {
    }
  }
}
