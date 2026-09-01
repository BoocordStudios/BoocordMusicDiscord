package com.boocord.music.audio;

import com.boocord.music.model.RepeatMode;
import com.boocord.music.model.Song;
import com.boocord.music.model.UserLanguage;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledFuture;

public final class GuildPlaybackState {
  private final String guildId;
  private final Object lock;
  private final AudioPlayer audioPlayer;
  private final AudioPlayerSendHandler sendHandler;
  private final Deque<Song> queue;

  private volatile Song currentSong;
  private volatile String voiceChannelId;
  private volatile String textChannelId;
  private volatile String controlMessageId;
  private volatile boolean shuffleMode;
  private volatile RepeatMode repeatMode;
  private volatile UserLanguage language;
  private volatile long lastActivity;
  private final AtomicLong loadVersion;
  private volatile ScheduledFuture<?> idleDisconnectFuture;

  public GuildPlaybackState(String guildId, AudioPlayer audioPlayer) {
    this.guildId = guildId;
    this.lock = new Object();
    this.audioPlayer = audioPlayer;
    this.sendHandler = new AudioPlayerSendHandler(audioPlayer);
    this.queue = new ArrayDeque<>();
    this.loadVersion = new AtomicLong();
    this.repeatMode = RepeatMode.OFF;
    this.language = UserLanguage.GERMAN;
    this.lastActivity = System.currentTimeMillis();
  }

  public String guildId() {
    return guildId;
  }

  public Object lock() {
    return lock;
  }

  public AudioPlayer audioPlayer() {
    return audioPlayer;
  }

  public AudioPlayerSendHandler sendHandler() {
    return sendHandler;
  }

  public Deque<Song> queue() {
    return queue;
  }

  public Song currentSong() {
    return currentSong;
  }

  public void setCurrentSong(Song currentSong) {
    this.currentSong = currentSong;
  }

  public String voiceChannelId() {
    return voiceChannelId;
  }

  public void setVoiceChannelId(String voiceChannelId) {
    this.voiceChannelId = voiceChannelId;
  }

  public String textChannelId() {
    return textChannelId;
  }

  public void setTextChannelId(String textChannelId) {
    this.textChannelId = textChannelId;
  }

  public String controlMessageId() {
    return controlMessageId;
  }

  public void setControlMessageId(String controlMessageId) {
    this.controlMessageId = controlMessageId;
  }

  public boolean shuffleMode() {
    return shuffleMode;
  }

  public void setShuffleMode(boolean shuffleMode) {
    this.shuffleMode = shuffleMode;
  }

  public RepeatMode repeatMode() {
    return repeatMode;
  }

  public void setRepeatMode(RepeatMode repeatMode) {
    this.repeatMode = repeatMode;
  }

  public UserLanguage language() {
    return language;
  }

  public void setLanguage(UserLanguage language) {
    this.language = language == null ? UserLanguage.GERMAN : language;
  }

  public long lastActivity() {
    return lastActivity;
  }

  public void touch() {
    this.lastActivity = System.currentTimeMillis();
  }

  public long nextLoadVersion() {
    return loadVersion.incrementAndGet();
  }

  public long loadVersion() {
    return loadVersion.get();
  }

  public ScheduledFuture<?> idleDisconnectFuture() {
    return idleDisconnectFuture;
  }

  public void setIdleDisconnectFuture(ScheduledFuture<?> idleDisconnectFuture) {
    this.idleDisconnectFuture = idleDisconnectFuture;
  }
}
