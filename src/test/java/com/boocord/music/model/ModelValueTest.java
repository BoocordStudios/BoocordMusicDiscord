package com.boocord.music.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelValueTest {
  @Test
  void parsesRepeatModesAndLanguagesSafely() {
    assertEquals(RepeatMode.TRACK, RepeatMode.fromDatabaseValue(" TRACK "));
    assertEquals(RepeatMode.QUEUE, RepeatMode.fromDatabaseValue("queue"));
    assertEquals(RepeatMode.OFF, RepeatMode.fromDatabaseValue("unknown"));
    assertEquals(UserLanguage.ENGLISH, UserLanguage.fromCode("English"));
    assertEquals(UserLanguage.GERMAN, UserLanguage.fromCode(null));
  }

  @Test
  void copiesMutableListsAtRecordBoundaries() {
    List<Song> songs = new ArrayList<>();
    songs.add(song(1));

    PlaylistResponse playlist = new PlaylistResponse(false, "name", "description", songs);
    PlaybackSession session = new PlaybackSession(
        "guild", "voice", "text", null, null, songs, false, false, false, 100, "off"
    );
    songs.add(song(2));

    assertEquals(1, playlist.songs().size());
    assertEquals(1, session.queue().size());
    assertThrows(UnsupportedOperationException.class, () -> playlist.songs().add(song(3)));
    assertThrows(UnsupportedOperationException.class, () -> session.queue().add(song(3)));
  }

  private Song song(int id) {
    return new Song(id, "Song " + id, "Artist", "Nick", "Mood", "Album", null,
        "https://example.test/song.mp3", null, null, null);
  }
}
