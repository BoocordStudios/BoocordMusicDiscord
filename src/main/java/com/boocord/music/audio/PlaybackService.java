package com.boocord.music.audio;

import com.boocord.music.api.BoocordApiClient;
import com.boocord.music.config.BotConfig;
import com.boocord.music.db.DatabaseService;
import com.boocord.music.discord.ComponentFactory;
import com.boocord.music.model.PlaybackSession;
import com.boocord.music.model.RepeatMode;
import com.boocord.music.model.Song;
import com.boocord.music.model.UserLanguage;
import com.boocord.music.util.ShuffleUtils;
import com.boocord.music.util.StringSanitizer;
import com.boocord.music.util.UserFacingException;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;

public final class PlaybackService {
  private static final Logger LOGGER = Logger.getLogger(PlaybackService.class.getName());

  private final JDA jda;
  private final BotConfig config;
  private final BoocordApiClient apiClient;
  private final DatabaseService databaseService;
  private final AudioPlayerManager playerManager;
  private final Map<String, GuildPlaybackState> states;
  private final Map<String, Long> activeListening;
  private final Map<String, ScheduledFuture<?>> persistTasks;
  private final ScheduledExecutorService scheduler;

  public PlaybackService(
      JDA jda,
      BotConfig config,
      BoocordApiClient apiClient,
      DatabaseService databaseService
  ) {
    this.jda = jda;
    this.config = config;
    this.apiClient = apiClient;
    this.databaseService = databaseService;
    this.playerManager = new DefaultAudioPlayerManager();
    AudioSourceManagers.registerRemoteSources(playerManager);
    this.states = new ConcurrentHashMap<>();
    this.activeListening = new ConcurrentHashMap<>();
    this.persistTasks = new ConcurrentHashMap<>();
    this.scheduler = Executors.newScheduledThreadPool(4);
  }

  public void restoreSessions() {
    try {
      for (PlaybackSession session : databaseService.getPlaybackSessions()) {
        Guild guild = jda.getGuildById(session.guildId());
        if (guild == null) {
          databaseService.deletePlaybackSession(session.guildId());
          continue;
        }

        AudioChannel voiceChannel = guild.getVoiceChannelById(session.voiceChannelId());
        GuildMessageChannel textChannel = jda.getChannelById(
            GuildMessageChannel.class,
            session.textChannelId()
        );
        if (voiceChannel == null || textChannel == null) {
          databaseService.deletePlaybackSession(session.guildId());
          continue;
        }

        GuildPlaybackState state = getOrCreateState(guild);
        synchronized (state.lock()) {
          connect(guild, state, voiceChannel, textChannel);
          state.setShuffleMode(session.shuffleMode());
          state.setControlMessageId(session.controlMessageId());
          state.setRepeatMode(RepeatMode.fromDatabaseValue(session.repeatMode()));
          state.audioPlayer().setVolume(session.volume());
          state.queue().clear();
          if (session.queue() != null) {
            state.queue().addAll(session.queue());
          }
        }

        if (session.currentSong() != null) {
          startSong(guild, state, session.currentSong(), false);
        }

        if (session.paused()) {
          scheduler.schedule(() -> state.audioPlayer().setPaused(true), 2, TimeUnit.SECONDS);
        }

        schedulePersist(state.guildId());
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Sessions konnten nicht wiederhergestellt werden.", exception);
    }
  }

  public void shutdown() {
    Set<String> guildIds = Set.copyOf(states.keySet());
    for (String guildId : guildIds) {
      try {
        flushListeningStats(guildId);
        GuildPlaybackState state = states.get(guildId);
        if (state != null) {
          persistState(state);
          disconnect(state);
        }
      } catch (Exception exception) {
        LOGGER.log(Level.WARNING, "Fehler beim Shutdown für Guild " + guildId, exception);
      }
    }
    scheduler.shutdownNow();
    persistTasks.clear();
    playerManager.shutdown();
  }

  public GuildPlaybackState getState(String guildId) {
    return states.get(guildId);
  }

  public Song getCurrentSong(String guildId) {
    GuildPlaybackState state = states.get(guildId);
    return state == null ? null : state.currentSong();
  }

  public Song updateCurrentSong(String guildId, Song song) {
    GuildPlaybackState state = states.get(guildId);
    if (state == null || song == null) {
      return null;
    }

    Song sanitized = apiClient.toSessionSong(song);
    synchronized (state.lock()) {
      state.setCurrentSong(sanitized);
    }
    schedulePersist(guildId);
    return sanitized;
  }

  public QueueSnapshot getQueueSnapshot(String guildId) {
    GuildPlaybackState state = states.get(guildId);
    if (state == null) {
      return new QueueSnapshot(null, List.of());
    }

    synchronized (state.lock()) {
      return new QueueSnapshot(state.currentSong(), List.copyOf(state.queue()));
    }
  }

  public long getCurrentSessionSeconds(String guildId, String userId) {
    Long startedAt = activeListening.get(guildId + "-" + userId);
    if (startedAt == null) {
      return 0L;
    }
    return Math.max(0L, (System.currentTimeMillis() - startedAt) / 1_000L);
  }

  public String getVoiceAccessError(Member member, Guild guild, boolean requirePlayback) {
    return getVoiceAccessError(member, guild, requirePlayback, UserLanguage.GERMAN);
  }

  public String getVoiceAccessError(
      Member member,
      Guild guild,
      boolean requirePlayback,
      UserLanguage language
  ) {
    if (member == null || member.getVoiceState() == null || !member.getVoiceState().inAudioChannel()) {
      return language.text("Du musst in einem Voice-Channel sein.", "You need to be in a voice channel.");
    }

    GuildPlaybackState state = states.get(guild.getId());
    if (state == null || state.voiceChannelId() == null) {
      return requirePlayback
          ? language.text("Aktuell läuft keine Wiedergabe.", "Nothing is currently playing.")
          : null;
    }

    AudioChannel userChannel = member.getVoiceState().getChannel();
    if (userChannel == null || !state.voiceChannelId().equals(userChannel.getId())) {
      return language.text(
          "Du musst im gleichen Voice-Channel wie der Bot sein.",
          "You need to be in the same voice channel as the bot."
      );
    }

    return null;
  }

  public void enqueueSong(
      Guild guild,
      Member member,
      GuildMessageChannel channel,
      Song song,
      boolean playImmediately
  ) {
    enqueueSong(guild, member, channel, song, playImmediately, UserLanguage.GERMAN);
  }

  public void enqueueSong(
      Guild guild,
      Member member,
      GuildMessageChannel channel,
      Song song,
      boolean playImmediately,
      UserLanguage language
  ) {
    String accessError = getVoiceAccessError(member, guild, false, language);
    if (accessError != null) {
      throw new UserFacingException(accessError);
    }

    Song sanitized = apiClient.toSessionSong(song);
    GuildPlaybackState state = getOrCreateState(guild);
    synchronized (state.lock()) {
      state.setLanguage(language);
      state.setVoiceChannelId(Objects.requireNonNull(member.getVoiceState()).getChannel().getId());
      state.setTextChannelId(channel.getId());
      state.touch();
      if (playImmediately) {
        state.audioPlayer().stopTrack();
        startSong(guild, state, sanitized, false);
      } else if (state.currentSong() == null) {
        startSong(guild, state, sanitized, false);
      } else {
        state.queue().addLast(sanitized);
      }
    }
    syncControlMessage(guild.getId());
    schedulePersist(guild.getId());
  }

  public void replaceQueueWithSongs(
      Guild guild,
      Member member,
      GuildMessageChannel channel,
      List<Song> songs,
      boolean shuffle
  ) {
    replaceQueueWithSongs(guild, member, channel, songs, shuffle, UserLanguage.GERMAN);
  }

  public void replaceQueueWithSongs(
      Guild guild,
      Member member,
      GuildMessageChannel channel,
      List<Song> songs,
      boolean shuffle,
      UserLanguage language
  ) {
    String accessError = getVoiceAccessError(member, guild, false, language);
    if (accessError != null) {
      throw new UserFacingException(accessError);
    }

    List<Song> nextSongs = shuffle ? ShuffleUtils.shuffledCopy(songs) : new ArrayList<>(songs);
    GuildPlaybackState state = getOrCreateState(guild);
    synchronized (state.lock()) {
      state.setLanguage(language);
      state.setVoiceChannelId(Objects.requireNonNull(member.getVoiceState()).getChannel().getId());
      state.setTextChannelId(channel.getId());
      state.setShuffleMode(shuffle);
      state.touch();
      state.queue().clear();
      nextSongs.stream().map(apiClient::toSessionSong).filter(Objects::nonNull).forEach(state.queue()::addLast);
      if (state.currentSong() == null) {
        Song next = state.queue().pollFirst();
        if (next != null) {
          startSong(guild, state, next, false);
        }
      }
    }
    syncControlMessage(guild.getId());
    schedulePersist(guild.getId());
  }

  public String togglePause(String guildId) {
    GuildPlaybackState state = requireStateWithPlayback(guildId);
    if (state.audioPlayer().isPaused()) {
      state.audioPlayer().setPaused(false);
      initializeActiveListeners(guildId);
    } else {
      flushListeningStats(guildId);
      state.audioPlayer().setPaused(true);
    }

    syncControlMessage(guildId);
    schedulePersist(guildId);
    return state.audioPlayer().isPaused() ? "paused" : "playing";
  }

  public void skip(String guildId) {
    GuildPlaybackState state = requireStateWithPlayback(guildId);
    flushListeningStats(guildId);

    Song next;
    synchronized (state.lock()) {
      next = state.queue().pollFirst();
      state.setCurrentSong(null);
      state.audioPlayer().stopTrack();
    }

    Guild guild = jda.getGuildById(guildId);
    if (guild != null && next != null) {
      startSong(guild, state, next, false);
    } else {
      disableControlMessage(guildId);
      scheduleIdleDisconnect(state);
    }

    syncControlMessage(guildId);
    schedulePersist(guildId);
  }

  public void stop(String guildId) {
    GuildPlaybackState state = requireStateWithPlayback(guildId);
    flushListeningStats(guildId);
    synchronized (state.lock()) {
      state.queue().clear();
      state.setCurrentSong(null);
      state.audioPlayer().stopTrack();
    }
    cancelPersist(guildId);
    disconnect(state);
    try {
      databaseService.deletePlaybackSession(guildId);
    } catch (SQLException exception) {
      throw new IllegalStateException("Playback-Session konnte nicht gelöscht werden.", exception);
    }
    states.remove(guildId);
  }

  public int setVolume(String guildId, double multiplier) {
    GuildPlaybackState state = requireStateWithPlayback(guildId);
    int percent = Math.max(0, Math.min((int) Math.round(multiplier * 100), 200));
    state.audioPlayer().setVolume(percent);
    syncControlMessage(guildId);
    schedulePersist(guildId);
    return percent;
  }

  public RepeatMode toggleTrackLoop(String guildId) {
    GuildPlaybackState state = requireStateWithPlayback(guildId);
    RepeatMode nextMode = state.repeatMode() == RepeatMode.TRACK ? RepeatMode.OFF : RepeatMode.TRACK;
    state.setRepeatMode(nextMode);
    syncControlMessage(guildId);
    schedulePersist(guildId);
    return nextMode;
  }

  public RepeatMode toggleQueueRepeat(String guildId) {
    GuildPlaybackState state = requireStateWithPlayback(guildId);
    RepeatMode nextMode = state.repeatMode() == RepeatMode.QUEUE ? RepeatMode.OFF : RepeatMode.QUEUE;
    state.setRepeatMode(nextMode);
    syncControlMessage(guildId);
    schedulePersist(guildId);
    return nextMode;
  }

  public boolean toggleShuffle(String guildId) {
    GuildPlaybackState state = requireStateWithPlayback(guildId);
    state.setShuffleMode(!state.shuffleMode());
    syncControlMessage(guildId);
    schedulePersist(guildId);
    return state.shuffleMode();
  }

  public void handleVoiceStateUpdate(GuildVoiceUpdateEvent event) {
    String guildId = event.getGuild().getId();
    String userId = event.getEntity().getId();
    String key = guildId + "-" + userId;
    GuildPlaybackState state = states.get(guildId);

    if (state == null || state.voiceChannelId() == null) {
      Long startedAt = activeListening.remove(key);
      if (startedAt != null) {
        persistListeningSeconds(userId, startedAt);
      }
      return;
    }

    if (event.getMember().getUser().isBot()) {
      return;
    }

    boolean joinedBotChannel = event.getChannelJoined() != null
        && state.voiceChannelId().equals(event.getChannelJoined().getId());
    boolean leftBotChannel = event.getChannelLeft() != null
        && state.voiceChannelId().equals(event.getChannelLeft().getId());

    if (joinedBotChannel && state.currentSong() != null && !state.audioPlayer().isPaused()) {
      activeListening.put(key, System.currentTimeMillis());
    }

    if (leftBotChannel) {
      Long startedAt = activeListening.remove(key);
      if (startedAt != null) {
        persistListeningSeconds(userId, startedAt);
      }
    }
  }

  public void syncControlMessage(String guildId) {
    GuildPlaybackState state = states.get(guildId);
    if (state == null || state.currentSong() == null || state.textChannelId() == null) {
      return;
    }

    GuildMessageChannel channel = jda.getChannelById(
        GuildMessageChannel.class,
        state.textChannelId()
    );
    if (channel == null || !channel.getGuild().getSelfMember().hasPermission(channel, Permission.MESSAGE_SEND)) {
      return;
    }

    try {
      if (state.controlMessageId() != null) {
        channel.retrieveMessageById(state.controlMessageId())
            .flatMap(message -> message.editMessageComponents(
                ComponentFactory.buildNowPlaying(
                    state.currentSong(),
                    state.queue().size(),
                    state.audioPlayer().getVolume(),
                     state.audioPlayer().isPaused(),
                     state.shuffleMode(),
                     state.repeatMode(),
                     state.language()
                )
            ).useComponentsV2())
            .complete();
        return;
      }
    } catch (Exception exception) {
      LOGGER.log(Level.FINE, "Control-Message konnte nicht aktualisiert werden.", exception);
    }

    var message = channel.sendMessageComponents(
            ComponentFactory.buildNowPlaying(
                state.currentSong(),
                state.queue().size(),
                state.audioPlayer().getVolume(),
                 state.audioPlayer().isPaused(),
                 state.shuffleMode(),
                 state.repeatMode(),
                 state.language()
            )
        )
        .useComponentsV2()
        .complete();
    state.setControlMessageId(message.getId());
    schedulePersist(guildId);
  }

  public void disableControlMessage(String guildId) {
    GuildPlaybackState state = states.get(guildId);
    if (state == null || state.controlMessageId() == null || state.textChannelId() == null) {
      return;
    }

    GuildMessageChannel channel = jda.getChannelById(
        GuildMessageChannel.class,
        state.textChannelId()
    );
    if (channel == null) {
      return;
    }

    try {
      channel.retrieveMessageById(state.controlMessageId())
          .flatMap(message -> message.editMessageComponents(ComponentFactory.buildInactivePlayer(state.language()))
              .useComponentsV2())
          .complete();
    } catch (Exception ignored) {
      // cleanup errors are non-fatal
    }
  }

  public void sendPlaybackNotice(String guildId, String title, String description) {
    GuildPlaybackState state = states.get(guildId);
    if (state == null || state.textChannelId() == null) {
      return;
    }

    GuildMessageChannel channel = jda.getChannelById(
        GuildMessageChannel.class,
        state.textChannelId()
    );
    if (channel == null) {
      return;
    }

    channel.sendMessageComponents(ComponentFactory.createInfo(title, description))
        .useComponentsV2()
        .queue();
  }

  public int activePlaybackCount() {
    return (int) states.values().stream().filter(state -> state.currentSong() != null).count();
  }

  private GuildPlaybackState getOrCreateState(Guild guild) {
    return states.computeIfAbsent(guild.getId(), ignored -> {
      var audioPlayer = playerManager.createPlayer();
      audioPlayer.setVolume(config.player().defaultVolumePercent());
      GuildPlaybackState state = new GuildPlaybackState(guild.getId(), audioPlayer);
      audioPlayer.addListener(new AudioEventAdapter() {
        @Override
        public void onTrackStart(com.sedmelluq.discord.lavaplayer.player.AudioPlayer player, AudioTrack track) {
          state.touch();
          initializeActiveListeners(guild.getId());
          syncControlMessage(guild.getId());
          schedulePersist(guild.getId());
        }

        @Override
        public void onTrackEnd(
            com.sedmelluq.discord.lavaplayer.player.AudioPlayer player,
            AudioTrack track,
            AudioTrackEndReason endReason
        ) {
          if (endReason.mayStartNext) {
            handleTrackCompletion(guild, state, null);
          }
        }

        @Override
        public void onTrackException(
            com.sedmelluq.discord.lavaplayer.player.AudioPlayer player,
            AudioTrack track,
            FriendlyException exception
        ) {
          sendPlaybackNotice(
              guild.getId(),
              state.language().text("Wiedergabeproblem", "Playback problem"),
              state.language().text("Der Song \"", "The song \"") + StringSanitizer.sanitizeLine(
                  state.currentSong() == null ? null : state.currentSong().name(),
                  state.language().text("Unbekannter Song", "Unknown song"),
                  80
              ) + state.language().text(
                  "\" wurde wegen eines Fehlers übersprungen.",
                  "\" was skipped because of an error."
              )
          );
        }

        @Override
        public void onTrackStuck(
            com.sedmelluq.discord.lavaplayer.player.AudioPlayer player,
            AudioTrack track,
            long thresholdMs
        ) {
          handleTrackCompletion(
              guild,
              state,
              state.language().text("Der Song \"", "The song \"") + StringSanitizer.sanitizeLine(
                  state.currentSong() == null ? null : state.currentSong().name(),
                  state.language().text("Unbekannter Song", "Unknown song"),
                  80
              ) + state.language().text(
                  "\" hing fest und wurde übersprungen.",
                  "\" became stuck and was skipped."
              )
          );
        }
      });
      return state;
    });
  }

  private void handleTrackCompletion(Guild guild, GuildPlaybackState state, String errorMessage) {
    flushListeningStats(guild.getId());

    Song nextSong = null;
    boolean refillShuffle = false;
    synchronized (state.lock()) {
      Song endedSong = state.currentSong();
      if (endedSong != null && state.repeatMode() == RepeatMode.QUEUE) {
        state.queue().addLast(endedSong);
      }

      if (endedSong != null && state.repeatMode() == RepeatMode.TRACK) {
        nextSong = endedSong;
      } else {
        state.setCurrentSong(null);
        nextSong = state.queue().pollFirst();
      }

      if (nextSong == null && state.shuffleMode()) {
        refillShuffle = true;
      }
    }

    if (errorMessage != null) {
      sendPlaybackNotice(
          guild.getId(),
          state.language().text("Wiedergabeproblem", "Playback problem"),
          errorMessage
      );
    }

    if (nextSong != null) {
      startSong(guild, state, nextSong, false);
      return;
    }

    if (refillShuffle) {
      try {
        List<Song> songs = ShuffleUtils.shuffledCopy(apiClient.getAllSongs());
        synchronized (state.lock()) {
          state.queue().clear();
          songs.stream().map(apiClient::toSessionSong).filter(Objects::nonNull).forEach(state.queue()::addLast);
          Song refillNext = state.queue().pollFirst();
          if (refillNext != null) {
            startSong(guild, state, refillNext, false);
            return;
          }
        }
      } catch (RuntimeException exception) {
        LOGGER.log(Level.WARNING, "Shuffle-Refill fehlgeschlagen.", exception);
      }
    }

    disableControlMessage(guild.getId());
    scheduleIdleDisconnect(state);
    schedulePersist(guild.getId());
  }

  private void startSong(Guild guild, GuildPlaybackState state, Song song, boolean replacingCurrent) {
    Song sanitized = apiClient.toSessionSong(song);
    if (sanitized == null || sanitized.streamUrl() == null) {
      throw new IllegalStateException("Der Song enthält keine gültige Audio-URL.");
    }

    LOGGER.info(() -> "Lade Song für Guild " + guild.getId() + ": " + sanitized.name()
        + " (ID " + sanitized.id() + ")");

    long loadVersion;
    synchronized (state.lock()) {
      cancelIdleDisconnect(state);
      if (replacingCurrent) {
        state.audioPlayer().stopTrack();
      }
      loadVersion = state.nextLoadVersion();
    }

    loadTrack(sanitized, state).whenComplete((track, throwable) -> {
      synchronized (state.lock()) {
        if (state.loadVersion() != loadVersion) {
          return;
        }
      }

      if (throwable != null) {
        LOGGER.log(Level.WARNING, "Track konnte nicht geladen werden.", throwable);
        sendPlaybackNotice(
            guild.getId(),
            state.language().text("Wiedergabeproblem", "Playback problem"),
            state.language().text("Der Song \"", "The song \"")
                + StringSanitizer.sanitizeLine(
                    sanitized.name(), state.language().text("Unbekannter Song", "Unknown song"), 80
                )
                + state.language().text("\" konnte nicht geladen werden.", "\" could not be loaded.")
        );
        handleTrackCompletion(guild, state, null);
        return;
      }

      synchronized (state.lock()) {
        if (state.loadVersion() != loadVersion) {
          return;
        }
        AudioChannel voiceChannel = guild.getVoiceChannelById(state.voiceChannelId());
        GuildMessageChannel textChannel = jda.getChannelById(
            GuildMessageChannel.class,
            state.textChannelId()
        );
        if (voiceChannel == null || textChannel == null) {
          throw new IllegalStateException("Voice- oder Text-Channel für die Wiedergabe ist nicht mehr verfügbar.");
        }
        connect(guild, state, voiceChannel, textChannel);
        state.setCurrentSong(sanitized);
        state.audioPlayer().startTrack(track, false);
        state.touch();
      }

      LOGGER.info(() -> "Starte Song in Guild " + guild.getId() + ": " + sanitized.name());
      syncControlMessage(guild.getId());
      schedulePersist(guild.getId());
    });
  }

  private CompletableFuture<AudioTrack> loadTrack(Song song, GuildPlaybackState state) {
    CompletableFuture<AudioTrack> future = new CompletableFuture<>();
    playerManager.loadItemOrdered(state, song.streamUrl(), new AudioLoadResultHandler() {
      @Override
      public void trackLoaded(AudioTrack track) {
        track.setUserData(song);
        future.complete(track);
      }

      @Override
      public void playlistLoaded(AudioPlaylist playlist) {
        AudioTrack selectedTrack = playlist.getSelectedTrack();
        if (selectedTrack == null && !playlist.getTracks().isEmpty()) {
          selectedTrack = playlist.getTracks().get(0);
        }

        if (selectedTrack == null) {
          future.completeExceptionally(new IllegalStateException("Keine Audioquelle gefunden."));
          return;
        }

        selectedTrack.setUserData(song);
        future.complete(selectedTrack);
      }

      @Override
      public void noMatches() {
        future.completeExceptionally(new IllegalStateException("Keine Audioquelle gefunden."));
      }

      @Override
      public void loadFailed(FriendlyException exception) {
        future.completeExceptionally(exception);
      }
    });
    return future;
  }

  private GuildPlaybackState requireStateWithPlayback(String guildId) {
    GuildPlaybackState state = states.get(guildId);
    if (state == null || state.currentSong() == null) {
      throw new UserFacingException(
          "Aktuell wird keine Musik abgespielt.",
          "No music is currently playing."
      );
    }
    return state;
  }

  private void connect(
      Guild guild,
      GuildPlaybackState state,
      AudioChannel voiceChannel,
      GuildMessageChannel textChannel
  ) {
    var audioManager = guild.getAudioManager();
    audioManager.setSendingHandler(state.sendHandler());
    audioManager.setSelfDeafened(true);

    if (!audioManager.isConnected() || !voiceChannel.getId().equals(state.voiceChannelId())) {
      audioManager.openAudioConnection(voiceChannel);
    }

    state.setVoiceChannelId(voiceChannel.getId());
    state.setTextChannelId(textChannel.getId());
  }

  private void disconnect(GuildPlaybackState state) {
    Guild guild = jda.getGuildById(state.guildId());
    if (guild != null) {
      guild.getAudioManager().closeAudioConnection();
      guild.getAudioManager().setSendingHandler(null);
    }
    cancelIdleDisconnect(state);
    disableControlMessage(state.guildId());
  }

  private void schedulePersist(String guildId) {
    ScheduledFuture<?> existing = persistTasks.get(guildId);
    if (existing != null) {
      existing.cancel(false);
    }

    AtomicReference<ScheduledFuture<?>> taskReference = new AtomicReference<>();
    ScheduledFuture<?> future = scheduler.schedule(() -> {
      try {
        GuildPlaybackState state = states.get(guildId);
        if (state != null) {
          persistState(state);
        }
      } finally {
        persistTasks.remove(guildId, taskReference.get());
      }
    }, 250, TimeUnit.MILLISECONDS);
    taskReference.set(future);
    persistTasks.put(guildId, future);
  }

  private void persistState(GuildPlaybackState state) {
    PlaybackSession snapshot;
    boolean deleteSession;
    synchronized (state.lock()) {
      deleteSession = state.currentSong() == null && state.queue().isEmpty();
      snapshot = deleteSession ? null : new PlaybackSession(
          state.guildId(),
          state.voiceChannelId(),
          state.textChannelId(),
          state.controlMessageId(),
          state.currentSong(),
          List.copyOf(state.queue()),
          state.shuffleMode(),
          state.currentSong() != null,
          state.audioPlayer().isPaused(),
          state.audioPlayer().getVolume(),
          state.repeatMode().toDatabaseValue()
      );
    }

    try {
      if (deleteSession) {
        databaseService.deletePlaybackSession(state.guildId());
      } else {
        databaseService.savePlaybackSession(snapshot);
      }
    } catch (SQLException exception) {
      LOGGER.log(Level.WARNING, "Session-Persistenz fehlgeschlagen für Guild " + state.guildId(), exception);
    }
  }

  private void scheduleIdleDisconnect(GuildPlaybackState state) {
    cancelIdleDisconnect(state);
    ScheduledFuture<?> future = scheduler.schedule(() -> {
      synchronized (state.lock()) {
        if (state.currentSong() != null || !state.queue().isEmpty()) {
          return;
        }
      }

      try {
        cancelPersist(state.guildId());
        disconnect(state);
        databaseService.deletePlaybackSession(state.guildId());
        states.remove(state.guildId());
      } catch (SQLException exception) {
        LOGGER.log(Level.WARNING, "Idle-Disconnect Cleanup fehlgeschlagen.", exception);
      }
    }, config.player().idleTimeoutMs(), TimeUnit.MILLISECONDS);
    state.setIdleDisconnectFuture(future);
  }

  private void cancelIdleDisconnect(GuildPlaybackState state) {
    ScheduledFuture<?> future = state.idleDisconnectFuture();
    if (future != null) {
      future.cancel(false);
      state.setIdleDisconnectFuture(null);
    }
  }

  private void cancelPersist(String guildId) {
    ScheduledFuture<?> future = persistTasks.remove(guildId);
    if (future != null) {
      future.cancel(false);
    }
  }

  private void initializeActiveListeners(String guildId) {
    GuildPlaybackState state = states.get(guildId);
    if (state == null || state.voiceChannelId() == null) {
      return;
    }

    Guild guild = jda.getGuildById(guildId);
    if (guild == null) {
      return;
    }

    AudioChannel channel = guild.getVoiceChannelById(state.voiceChannelId());
    if (channel == null) {
      return;
    }

    long now = System.currentTimeMillis();
    for (Member member : channel.getMembers()) {
      if (!member.getUser().isBot()) {
        activeListening.put(guildId + "-" + member.getId(), now);
      }
    }
  }

  private void flushListeningStats(String guildId) {
    List<String> keys = activeListening.keySet().stream()
        .filter(key -> key.startsWith(guildId + "-"))
        .toList();
    for (String key : keys) {
      Long startedAt = activeListening.remove(key);
      if (startedAt == null) {
        continue;
      }
      persistListeningSeconds(key.substring(guildId.length() + 1), startedAt);
    }
  }

  private void persistListeningSeconds(String userId, long startedAt) {
    long seconds = Math.max(0L, (System.currentTimeMillis() - startedAt) / 1_000L);
    if (seconds <= 0) {
      return;
    }

    try {
      databaseService.updateUserStats(userId, seconds);
    } catch (SQLException exception) {
      LOGGER.log(Level.WARNING, "Hörzeit konnte nicht gespeichert werden.", exception);
    }
  }

  public record QueueSnapshot(Song currentSong, List<Song> upcomingSongs) {
    public QueueSnapshot {
      upcomingSongs = upcomingSongs == null ? List.of() : List.copyOf(upcomingSongs);
    }
  }
}
