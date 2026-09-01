package com.boocord.music.model;

public record Song(
    Integer id,
    String name,
    String artistName,
    String nickname,
    String moodName,
    String albumName,
    String path,
    String streamUrl,
    String thumbnail,
    String thumbnailUrl,
    String lyrics
) {
}
