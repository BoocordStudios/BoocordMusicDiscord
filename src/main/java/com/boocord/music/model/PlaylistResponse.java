package com.boocord.music.model;

import java.util.List;

public record PlaylistResponse(
    boolean hasMore,
    String playlistName,
    String playlistDescription,
    List<Song> songs
) {
  public PlaylistResponse {
    songs = songs == null ? List.of() : List.copyOf(songs);
  }
}
