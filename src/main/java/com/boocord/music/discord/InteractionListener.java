package com.boocord.music.discord;

import com.boocord.music.api.BoocordApiClient;
import com.boocord.music.audio.PlaybackService;
import com.boocord.music.db.DatabaseService;
import com.boocord.music.model.RepeatMode;
import com.boocord.music.model.Song;
import com.boocord.music.model.UserLanguage;
import com.boocord.music.util.AppConstants;
import com.boocord.music.util.UserFacingException;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public final class InteractionListener extends ListenerAdapter {
  private static final Logger LOGGER = Logger.getLogger(InteractionListener.class.getName());

  private final BoocordApiClient apiClient;
  private final DatabaseService databaseService;
  private final PlaybackService playbackService;
  private final PaginationService paginationService;
  private final ExecutorService executorService;
  private final ThreadLocal<UserLanguage> interactionLanguage;

  public InteractionListener(
      BoocordApiClient apiClient,
      DatabaseService databaseService,
      PlaybackService playbackService,
      PaginationService paginationService
  ) {
    this.apiClient = apiClient;
    this.databaseService = databaseService;
    this.playbackService = playbackService;
    this.paginationService = paginationService;
    int workerCount = Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors() * 2));
    this.executorService = Executors.newFixedThreadPool(workerCount);
    this.interactionLanguage = ThreadLocal.withInitial(() -> UserLanguage.GERMAN);
  }

  public void shutdown() {
    executorService.shutdownNow();
  }

  @Override
  public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
    event.deferReply().queue(
        ignored -> executorService.execute(() -> processSlashCommand(event)),
        failure -> LOGGER.log(Level.WARNING, "Slash-Command konnte nicht bestätigt werden.", failure)
    );
  }

  @Override
  public void onButtonInteraction(ButtonInteractionEvent event) {
    if (paginationService.handle(event)) {
      return;
    }

    event.deferReply(true).queue(
        ignored -> executorService.execute(() -> processButton(event)),
        failure -> LOGGER.log(Level.WARNING, "Button-Interaktion konnte nicht bestätigt werden.", failure)
    );
  }

  private void processSlashCommand(SlashCommandInteractionEvent event) {
    interactionLanguage.set(loadLanguage(event.getUser().getId()));
    try {
      handleSlashCommand(event);
    } catch (Exception exception) {
      logInteractionFailure("Slash-Command /" + event.getName(), exception);
      String message = exception instanceof UserFacingException userFacingException
          ? userFacingException.getMessage(language())
          : language().text(
              "Ein unerwarteter Fehler ist aufgetreten.",
              "An unexpected error occurred."
          );
      event.getHook()
          .editOriginalComponents(ComponentFactory.createInfo(
              language().text("Fehler", "Error"), message, language()
          ))
          .useComponentsV2()
          .queue();
    } finally {
      interactionLanguage.remove();
    }
  }

  private void processButton(ButtonInteractionEvent event) {
    interactionLanguage.set(loadLanguage(event.getUser().getId()));
    try {
      handleButton(event);
    } catch (Exception exception) {
      logInteractionFailure("Button " + event.getComponentId(), exception);
      String message = exception instanceof UserFacingException userFacingException
          ? userFacingException.getMessage(language())
          : language().text(
              "Ein unerwarteter Fehler ist aufgetreten.",
              "An unexpected error occurred."
          );
      event.getHook()
          .editOriginalComponents(ComponentFactory.createInfo(
              language().text("Fehler", "Error"), message, language()
          ))
          .useComponentsV2()
          .queue();
    } finally {
      interactionLanguage.remove();
    }
  }

  @Override
  public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
    try {
      playbackService.handleVoiceStateUpdate(event);
    } catch (Exception exception) {
      LOGGER.log(Level.WARNING, "Fehler bei voiceStateUpdate.", exception);
    }
  }

  private void handleSlashCommand(SlashCommandInteractionEvent event) throws SQLException {
    if ("language".equals(event.getName())) {
      handleLanguage(event);
      return;
    }

    Guild guild = event.getGuild();
    Member member = event.getMember();
    GuildMessageChannel channel = event.getGuildChannel();
    if (guild == null || member == null || channel == null) {
      event.getHook().editOriginalComponents(
          info(
              "Fehler", "Error",
              "Dieser Befehl kann nur auf einem Server verwendet werden.",
              "This command can only be used on a server."
          )
      ).useComponentsV2().queue();
      return;
    }

    switch (event.getName()) {
      case "play" -> handlePlay(event, guild, member, channel);
      case "playlist" -> handlePlaylist(event);
      case "search" -> handleSearch(event);
      case "queue" -> handleQueue(event);
      case "skip" -> handleSkip(event, guild, member);
      case "pause" -> handlePause(event, guild, member);
      case "resume" -> handleResume(event, guild, member);
      case "stop" -> handleStop(event, guild, member);
      case "shuffle" -> handleShuffle(event, guild, member, channel);
      case "requestsong" -> handleRequestSong(event, guild, member, channel);
      case "lyrics" -> handleLyrics(event);
      case "favorites" -> handleFavorites(event);
      case "favoplay" -> handleFavoritesPlay(event, guild, member, channel);
      case "volume" -> handleVolume(event, guild, member);
      case "loop" -> handleLoop(event, guild, member);
      case "repeat" -> handleRepeat(event, guild, member);
      case "favotoggle" -> handleFavoriteToggle(event);
      case "stats" -> handleStats(event);
      default -> event.getHook().editOriginalComponents(
          info(
              "Unbekannter Befehl", "Unknown command",
              "Dieser Befehl wird nicht unterstützt.", "This command is not supported."
          )
      ).useComponentsV2().queue();
    }
  }

  private void handleButton(ButtonInteractionEvent event) throws SQLException {
    Guild guild = event.getGuild();
    Member member = event.getMember();
    if (guild == null || member == null) {
      event.getHook().editOriginalComponents(
          info(
              "Fehler", "Error",
              "Diese Aktion kann nur auf einem Server verwendet werden.",
              "This action can only be used on a server."
          )
      ).useComponentsV2().queue();
      return;
    }

    switch (event.getComponentId()) {
      case AppConstants.ButtonIds.PAUSE_RESUME -> handlePauseResumeButton(event, guild, member);
      case AppConstants.ButtonIds.SKIP -> handleSkipButton(event, guild, member);
      case AppConstants.ButtonIds.STOP -> handleStopButton(event, guild, member);
      case AppConstants.ButtonIds.TOGGLE_FAVORITE -> handleFavoriteButton(event, guild);
      case AppConstants.ButtonIds.LYRICS -> handleLyricsButton(event, guild);
      case AppConstants.ButtonIds.TOGGLE_SHUFFLE -> handleShuffleButton(event, guild, member);
      case AppConstants.ButtonIds.TOGGLE_LOOP_SONG -> handleLoopButton(event, guild, member);
      case AppConstants.ButtonIds.TOGGLE_REPEAT_QUEUE -> handleRepeatButton(event, guild, member);
      default -> event.getHook().editOriginalComponents(
          info(
              "Unbekannter Button", "Unknown button",
              "Diese Aktion wird nicht unterstützt.", "This action is not supported."
          )
      ).useComponentsV2().queue();
    }
  }

  private void handlePlay(SlashCommandInteractionEvent event, Guild guild, Member member, GuildMessageChannel channel) {
    String query = event.getOption("query").getAsString();
    Song song = apiClient.resolvePlayQuery(query);
    if (song == null) {
      event.getHook().editOriginalComponents(
          info(
              "Nicht gefunden", "Not found",
              "Es wurde kein passender Song gefunden.", "No matching song was found."
          )
      ).useComponentsV2().queue();
      return;
    }

    boolean playImmediately = playbackService.getState(guild.getId()) != null
        && playbackService.getState(guild.getId()).shuffleMode();
    playbackService.enqueueSong(guild, member, channel, song, playImmediately, language());

    event.getHook().editOriginalComponents(
        ComponentFactory.createInfo(
            language().text("Song hinzugefügt", "Song added"),
            playImmediately
                ? language().text(
                    "\"" + song.name() + "\" wird sofort abgespielt.",
                    "\"" + song.name() + "\" will play immediately."
                )
                : language().text(
                    "\"" + song.name() + "\" wurde der Warteschlange hinzugefügt.",
                    "\"" + song.name() + "\" was added to the queue."
                ),
            language()
        )
    ).useComponentsV2().queue();
  }

  private void handlePlaylist(SlashCommandInteractionEvent event) {
    var playlist = apiClient.fetchPlaylist(1_000, "", null, null, null, null, false);
    paginationService.sendPaginatedReply(
        event.getHook(),
        event.getUser().getId(),
        ComponentFactory.buildPlaylistPages(
            "Boocord Playlist",
            playlist.songs(),
            playlist.playlistName() + "\n" + playlist.playlistDescription() + "\n\n"
                + language().text("Gesamt: ", "Total: ") + playlist.songs().size() + " Songs",
            language()
        ),
        language()
    );
  }

  private void handleSearch(SlashCommandInteractionEvent event) {
    String search = event.getOption("search").getAsString();
    var playlist = apiClient.fetchPlaylist(1_000, search, null, null, null, null, false);
    paginationService.sendPaginatedReply(
        event.getHook(),
        event.getUser().getId(),
        ComponentFactory.buildPlaylistPages(
            language().text("Suchergebnisse", "Search results"),
            playlist.songs(),
            language().text("Suche: ", "Search: ") + search + "\n\n"
                + language().text("Gesamt: ", "Total: ") + playlist.songs().size() + " Songs",
            language()
        ),
        language()
    );
  }

  private void handleQueue(SlashCommandInteractionEvent event) {
    var snapshot = playbackService.getQueueSnapshot(event.getGuild().getId());
    paginationService.sendPaginatedReply(
        event.getHook(),
        event.getUser().getId(),
        ComponentFactory.buildQueuePages(snapshot.currentSong(), snapshot.upcomingSongs(), language()),
        language()
    );
  }

  private void handleSkip(SlashCommandInteractionEvent event, Guild guild, Member member) {
    String error = voiceAccessError(member, guild, true);
    if (error != null) {
      event.getHook().editOriginalComponents(errorInfo(error)).useComponentsV2().queue();
      return;
    }
    playbackService.skip(guild.getId());
    event.getHook().editOriginalComponents(
        info("Song übersprungen", "Song skipped", "Der nächste Song wird gestartet.", "The next song is starting.")
    ).useComponentsV2().queue();
  }

  private void handlePause(SlashCommandInteractionEvent event, Guild guild, Member member) {
    String error = voiceAccessError(member, guild, true);
    if (error != null) {
      event.getHook().editOriginalComponents(errorInfo(error)).useComponentsV2().queue();
      return;
    }

    String result = playbackService.togglePause(guild.getId());
    event.getHook().editOriginalComponents(
        info(
            "paused".equals(result) ? "Wiedergabe pausiert" : "Wiedergabe fortgesetzt",
            "paused".equals(result) ? "Playback paused" : "Playback resumed",
            "paused".equals(result) ? "Die Wiedergabe wurde pausiert." : "Die Wiedergabe läuft wieder.",
            "paused".equals(result) ? "Playback has been paused." : "Playback is running again."
        )
    ).useComponentsV2().queue();
  }

  private void handleResume(SlashCommandInteractionEvent event, Guild guild, Member member) {
    String error = voiceAccessError(member, guild, true);
    if (error != null) {
      event.getHook().editOriginalComponents(errorInfo(error)).useComponentsV2().queue();
      return;
    }

    var state = playbackService.getState(guild.getId());
    if (state == null || !state.audioPlayer().isPaused()) {
      event.getHook().editOriginalComponents(
          info("Keine Pause aktiv", "Not paused", "Die Wiedergabe ist bereits aktiv.", "Playback is already active.")
      ).useComponentsV2().queue();
      return;
    }

    playbackService.togglePause(guild.getId());
    event.getHook().editOriginalComponents(
        info("Wiedergabe fortgesetzt", "Playback resumed", "Die Wiedergabe läuft wieder.", "Playback is running again.")
    ).useComponentsV2().queue();
  }

  private void handleStop(SlashCommandInteractionEvent event, Guild guild, Member member) {
    String error = voiceAccessError(member, guild, true);
    if (error != null) {
      event.getHook().editOriginalComponents(errorInfo(error)).useComponentsV2().queue();
      return;
    }
    playbackService.stop(guild.getId());
    event.getHook().editOriginalComponents(
        info("Wiedergabe gestoppt", "Playback stopped", "Die Wiedergabe wurde beendet.", "Playback has ended.")
    ).useComponentsV2().queue();
  }

  private void handleShuffle(SlashCommandInteractionEvent event, Guild guild, Member member, GuildMessageChannel channel) {
    List<Song> songs = apiClient.getAllSongs();
    if (songs.isEmpty()) {
      event.getHook().editOriginalComponents(
          info("Playlist leer", "Empty playlist", "Es konnten keine Songs geladen werden.", "No songs could be loaded.")
      ).useComponentsV2().queue();
      return;
    }

    playbackService.replaceQueueWithSongs(guild, member, channel, songs, true, language());
    event.getHook().editOriginalComponents(
        info(
            "Shuffle aktiviert", "Shuffle enabled",
            "Die Queue wurde mit " + songs.size() + " Songs im Shuffle-Modus gefüllt.",
            "The queue was filled with " + songs.size() + " songs in shuffle mode."
        )
    ).useComponentsV2().queue();
  }

  private void handleRequestSong(SlashCommandInteractionEvent event, Guild guild, Member member, GuildMessageChannel channel) {
    int id = (int) event.getOption("id").getAsLong();
    Song song = apiClient.getSongById(id);
    if (song == null) {
      event.getHook().editOriginalComponents(
          info(
              "Nicht gefunden", "Not found",
              "Dieser Song existiert nicht auf Boocord Music.",
              "This song does not exist on Boocord Music."
          )
      ).useComponentsV2().queue();
      return;
    }

    playbackService.enqueueSong(guild, member, channel, song, false, language());
    event.getHook().editOriginalComponents(
        info(
            "Song hinzugefügt", "Song added",
            "\"" + song.name() + "\" (ID " + song.id() + ") wurde der Queue hinzugefügt.",
            "\"" + song.name() + "\" (ID " + song.id() + ") was added to the queue."
        )
    ).useComponentsV2().queue();
  }

  private void handleLyrics(SlashCommandInteractionEvent event) {
    Song currentSong = playbackService.getCurrentSong(event.getGuild().getId());
    if (currentSong == null) {
      event.getHook().editOriginalComponents(
          noPlaybackInfo()
      ).useComponentsV2().queue();
      return;
    }

    Song song = currentSong;
    if (song.lyrics() == null && song.id() != null) {
      Song detailed = apiClient.getSongById(song.id());
      if (detailed != null) {
        song = playbackService.updateCurrentSong(event.getGuild().getId(), detailed);
      }
    }

    if (song == null || song.lyrics() == null || song.lyrics().isBlank()) {
      event.getHook().editOriginalComponents(
          info(
              "Keine Lyrics", "No lyrics",
              "Für diesen Song sind keine Lyrics verfügbar.", "No lyrics are available for this song."
          )
      ).useComponentsV2().queue();
      return;
    }

    paginationService.sendPaginatedReply(
        event.getHook(),
        event.getUser().getId(),
        ComponentFactory.buildLyricsPages(song, song.lyrics(), language()),
        language()
    );
  }

  private void handleFavorites(SlashCommandInteractionEvent event) throws SQLException {
    List<Integer> favoriteIds = databaseService.getFavorites(event.getUser().getId());
    if (favoriteIds.isEmpty()) {
      event.getHook().editOriginalComponents(
          noFavoritesInfo()
      ).useComponentsV2().queue();
      return;
    }

    List<Song> songs = apiClient.getSongsByIds(favoriteIds);
    paginationService.sendPaginatedReply(
        event.getHook(),
        event.getUser().getId(),
        ComponentFactory.buildPlaylistPages(
            language().text("Deine Favoriten", "Your favorites"),
            songs,
            language().text("Gesamt: ", "Total: ") + songs.size() + " Songs",
            language()
        ),
        language()
    );
  }

  private void handleFavoritesPlay(SlashCommandInteractionEvent event, Guild guild, Member member, GuildMessageChannel channel) throws SQLException {
    List<Integer> favoriteIds = databaseService.getFavorites(event.getUser().getId());
    if (favoriteIds.isEmpty()) {
      event.getHook().editOriginalComponents(
          noFavoritesInfo()
      ).useComponentsV2().queue();
      return;
    }

    List<Song> songs = apiClient.getSongsByIds(favoriteIds);
    if (songs.isEmpty()) {
      event.getHook().editOriginalComponents(
          info(
              "Keine Favoriten", "No favorites",
              "Es konnten keine gültigen Favoriten geladen werden.",
              "No valid favorites could be loaded."
          )
      ).useComponentsV2().queue();
      return;
    }

    playbackService.replaceQueueWithSongs(guild, member, channel, songs, true, language());
    event.getHook().editOriginalComponents(
        info(
            "Favoriten gestartet", "Favorites started",
            "Deine " + songs.size() + " Favoriten werden jetzt im Shuffle-Modus abgespielt.",
            "Your " + songs.size() + " favorites are now playing in shuffle mode."
        )
    ).useComponentsV2().queue();
  }

  private void handleVolume(SlashCommandInteractionEvent event, Guild guild, Member member) {
    String error = voiceAccessError(member, guild, true);
    if (error != null) {
      event.getHook().editOriginalComponents(errorInfo(error)).useComponentsV2().queue();
      return;
    }

    double value = event.getOption("value").getAsDouble();
    int percent = playbackService.setVolume(guild.getId(), value);
    event.getHook().editOriginalComponents(
        info(
            "Lautstärke geändert", "Volume changed",
            "Die Lautstärke steht jetzt auf " + percent + "%.",
            "The volume is now set to " + percent + "%."
        )
    ).useComponentsV2().queue();
  }

  private void handleLoop(SlashCommandInteractionEvent event, Guild guild, Member member) {
    String error = voiceAccessError(member, guild, true);
    if (error != null) {
      event.getHook().editOriginalComponents(errorInfo(error)).useComponentsV2().queue();
      return;
    }

    RepeatMode mode = playbackService.toggleTrackLoop(guild.getId());
    event.getHook().editOriginalComponents(
        info(
            "Song-Loop", "Song loop",
            mode == RepeatMode.TRACK ? "Song-Loop wurde aktiviert." : "Song-Loop wurde deaktiviert.",
            mode == RepeatMode.TRACK ? "Song loop has been enabled." : "Song loop has been disabled."
        )
    ).useComponentsV2().queue();
  }

  private void handleRepeat(SlashCommandInteractionEvent event, Guild guild, Member member) {
    String error = voiceAccessError(member, guild, true);
    if (error != null) {
      event.getHook().editOriginalComponents(errorInfo(error)).useComponentsV2().queue();
      return;
    }

    RepeatMode mode = playbackService.toggleQueueRepeat(guild.getId());
    event.getHook().editOriginalComponents(
        info(
            "Queue-Repeat", "Queue repeat",
            mode == RepeatMode.QUEUE ? "Queue-Repeat wurde aktiviert." : "Queue-Repeat wurde deaktiviert.",
            mode == RepeatMode.QUEUE ? "Queue repeat has been enabled." : "Queue repeat has been disabled."
        )
    ).useComponentsV2().queue();
  }

  private void handleFavoriteToggle(SlashCommandInteractionEvent event) throws SQLException {
    Song currentSong = playbackService.getCurrentSong(event.getGuild().getId());
    if (currentSong == null || currentSong.id() == null) {
      event.getHook().editOriginalComponents(
          noPlaybackInfo()
      ).useComponentsV2().queue();
      return;
    }

    boolean favoriteAdded = toggleFavorite(event.getUser().getId(), currentSong.id());
    event.getHook().editOriginalComponents(
        favoriteUpdatedInfo(currentSong, favoriteAdded)
    ).useComponentsV2().queue();
  }

  private void handleStats(SlashCommandInteractionEvent event) throws SQLException {
    long storedSeconds = databaseService.getUserStats(event.getUser().getId());
    long liveSeconds = playbackService.getCurrentSessionSeconds(event.getGuild().getId(), event.getUser().getId());
    event.getHook().editOriginalComponents(
        ComponentFactory.buildStats(storedSeconds + liveSeconds, language())
    ).useComponentsV2().queue();
  }

  private void handleLanguage(SlashCommandInteractionEvent event) throws SQLException {
    UserLanguage selectedLanguage = UserLanguage.fromCode(event.getOption("language").getAsString());
    databaseService.setUserLanguage(event.getUser().getId(), selectedLanguage);
    interactionLanguage.set(selectedLanguage);
    event.getHook().editOriginalComponents(
        info(
            "Sprache geändert", "Language changed",
            "Deine Sprache ist jetzt Deutsch.", "Your language is now English."
        )
    ).useComponentsV2().queue();
  }

  private void handlePauseResumeButton(ButtonInteractionEvent event, Guild guild, Member member) {
    String error = voiceAccessError(member, guild, true);
    if (error != null) {
      event.getHook().editOriginalComponents(errorInfo(error)).useComponentsV2().queue();
      return;
    }

    String result = playbackService.togglePause(guild.getId());
    event.getHook().editOriginalComponents(
        info(
            "paused".equals(result) ? "Wiedergabe pausiert" : "Wiedergabe fortgesetzt",
            "paused".equals(result) ? "Playback paused" : "Playback resumed",
            "paused".equals(result) ? "Die Wiedergabe wurde pausiert." : "Die Wiedergabe läuft wieder.",
            "paused".equals(result) ? "Playback has been paused." : "Playback is running again."
        )
    ).useComponentsV2().queue();
  }

  private void handleSkipButton(ButtonInteractionEvent event, Guild guild, Member member) {
    String error = voiceAccessError(member, guild, true);
    if (error != null) {
      event.getHook().editOriginalComponents(errorInfo(error)).useComponentsV2().queue();
      return;
    }
    playbackService.skip(guild.getId());
    event.getHook().editOriginalComponents(
        info("Song übersprungen", "Song skipped", "Der nächste Song wird gestartet.", "The next song is starting.")
    ).useComponentsV2().queue();
  }

  private void handleStopButton(ButtonInteractionEvent event, Guild guild, Member member) {
    String error = voiceAccessError(member, guild, true);
    if (error != null) {
      event.getHook().editOriginalComponents(errorInfo(error)).useComponentsV2().queue();
      return;
    }
    playbackService.stop(guild.getId());
    event.getHook().editOriginalComponents(
        info("Wiedergabe gestoppt", "Playback stopped", "Die Wiedergabe wurde beendet.", "Playback has ended.")
    ).useComponentsV2().queue();
  }

  private void handleFavoriteButton(ButtonInteractionEvent event, Guild guild) throws SQLException {
    Song currentSong = playbackService.getCurrentSong(guild.getId());
    if (currentSong == null || currentSong.id() == null) {
      event.getHook().editOriginalComponents(
          noPlaybackInfo()
      ).useComponentsV2().queue();
      return;
    }

    boolean favoriteAdded = toggleFavorite(event.getUser().getId(), currentSong.id());
    event.getHook().editOriginalComponents(
        favoriteUpdatedInfo(currentSong, favoriteAdded)
    ).useComponentsV2().queue();
  }

  private void handleLyricsButton(ButtonInteractionEvent event, Guild guild) {
    Song currentSong = playbackService.getCurrentSong(guild.getId());
    if (currentSong == null) {
      event.getHook().editOriginalComponents(
          noPlaybackInfo()
      ).useComponentsV2().queue();
      return;
    }

    Song song = currentSong;
    if (song.lyrics() == null && song.id() != null) {
      Song detailed = apiClient.getSongById(song.id());
      if (detailed != null) {
        song = playbackService.updateCurrentSong(guild.getId(), detailed);
      }
    }

    if (song == null || song.lyrics() == null || song.lyrics().isBlank()) {
      event.getHook().editOriginalComponents(
          info(
              "Keine Lyrics", "No lyrics",
              "Für diesen Song sind keine Lyrics verfügbar.", "No lyrics are available for this song."
          )
      ).useComponentsV2().queue();
      return;
    }

    paginationService.sendPaginatedReply(
        event.getHook(),
        event.getUser().getId(),
        ComponentFactory.buildLyricsPages(song, song.lyrics(), language()),
        language()
    );
  }

  private void handleShuffleButton(ButtonInteractionEvent event, Guild guild, Member member) {
    String error = voiceAccessError(member, guild, true);
    if (error != null) {
      event.getHook().editOriginalComponents(errorInfo(error)).useComponentsV2().queue();
      return;
    }

    boolean enabled = playbackService.toggleShuffle(guild.getId());
    event.getHook().editOriginalComponents(
        info(
            "Shuffle umgeschaltet", "Shuffle toggled",
            enabled ? "Shuffle ist jetzt aktiviert." : "Shuffle ist jetzt deaktiviert.",
            enabled ? "Shuffle is now enabled." : "Shuffle is now disabled."
        )
    ).useComponentsV2().queue();
  }

  private void handleLoopButton(ButtonInteractionEvent event, Guild guild, Member member) {
    String error = voiceAccessError(member, guild, true);
    if (error != null) {
      event.getHook().editOriginalComponents(errorInfo(error)).useComponentsV2().queue();
      return;
    }

    RepeatMode mode = playbackService.toggleTrackLoop(guild.getId());
    event.getHook().editOriginalComponents(
        info(
            "Song-Loop", "Song loop",
            mode == RepeatMode.TRACK ? "Song-Loop wurde aktiviert." : "Song-Loop wurde deaktiviert.",
            mode == RepeatMode.TRACK ? "Song loop has been enabled." : "Song loop has been disabled."
        )
    ).useComponentsV2().queue();
  }

  private void handleRepeatButton(ButtonInteractionEvent event, Guild guild, Member member) {
    String error = voiceAccessError(member, guild, true);
    if (error != null) {
      event.getHook().editOriginalComponents(errorInfo(error)).useComponentsV2().queue();
      return;
    }

    RepeatMode mode = playbackService.toggleQueueRepeat(guild.getId());
    event.getHook().editOriginalComponents(
        info(
            "Queue-Repeat", "Queue repeat",
            mode == RepeatMode.QUEUE ? "Queue-Repeat wurde aktiviert." : "Queue-Repeat wurde deaktiviert.",
            mode == RepeatMode.QUEUE ? "Queue repeat has been enabled." : "Queue repeat has been disabled."
        )
    ).useComponentsV2().queue();
  }

  private UserLanguage loadLanguage(String userId) {
    try {
      return databaseService.getUserLanguage(userId);
    } catch (SQLException exception) {
      LOGGER.log(Level.WARNING, "Nutzersprache konnte nicht geladen werden.", exception);
      return UserLanguage.GERMAN;
    }
  }

  private UserLanguage language() {
    return interactionLanguage.get();
  }

  private String voiceAccessError(Member member, Guild guild, boolean requirePlayback) {
    return playbackService.getVoiceAccessError(member, guild, requirePlayback, language());
  }

  private List<MessageTopLevelComponent> info(
      String germanTitle,
      String englishTitle,
      String germanDescription,
      String englishDescription
  ) {
    return ComponentFactory.createInfo(
        language().text(germanTitle, englishTitle),
        language().text(germanDescription, englishDescription),
        language()
    );
  }

  private List<MessageTopLevelComponent> errorInfo(String error) {
    return ComponentFactory.createInfo(language().text("Fehler", "Error"), error, language());
  }

  private List<MessageTopLevelComponent> noPlaybackInfo() {
    return info(
        "Keine Wiedergabe", "Nothing playing",
        "Momentan läuft kein Song.", "No song is currently playing."
    );
  }

  private List<MessageTopLevelComponent> noFavoritesInfo() {
    return info(
        "Keine Favoriten", "No favorites",
        "Du hast noch keine Favoriten gespeichert.", "You have not saved any favorites yet."
    );
  }

  private List<MessageTopLevelComponent> favoriteUpdatedInfo(Song song, boolean favoriteAdded) {
    return info(
        "Favorit aktualisiert", "Favorite updated",
        "\"" + song.name() + "\" wurde " + (favoriteAdded ? "hinzugefügt" : "entfernt") + ".",
        "\"" + song.name() + "\" was " + (favoriteAdded ? "added to" : "removed from") + " your favorites."
    );
  }

  private boolean toggleFavorite(String userId, int songId) throws SQLException {
    List<Integer> favorites = databaseService.getFavorites(userId);
    if (favorites.contains(songId)) {
      databaseService.removeFavorite(userId, songId);
      return false;
    }

    databaseService.addFavorite(userId, songId);
    return true;
  }

  private void logInteractionFailure(String interaction, Exception exception) {
    if (exception instanceof UserFacingException) {
      LOGGER.log(Level.INFO, "{0} abgelehnt: {1}", new Object[]{interaction, exception.getMessage()});
      return;
    }

    LOGGER.log(Level.WARNING, "Unerwarteter Fehler bei " + interaction + ".", exception);
  }
}
