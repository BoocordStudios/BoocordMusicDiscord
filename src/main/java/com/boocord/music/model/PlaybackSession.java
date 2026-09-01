package com.boocord.music.model;

import java.util.List;

public record PlaybackSession(
    String guildId,
    String voiceChannelId,
    String textChannelId,
    String controlMessageId,
    Song currentSong,
    List<Song> queue,
    boolean shuffleMode,
    boolean playing,
    boolean paused,
    int volume,
    String repeatMode
) {
  public PlaybackSession {
    queue = queue == null ? List.of() : List.copyOf(queue);
  }
}
