package com.boocord.music.discord;

import com.boocord.music.model.RepeatMode;
import com.boocord.music.model.Song;
import com.boocord.music.model.UserLanguage;
import com.boocord.music.util.AppConstants;
import com.boocord.music.util.StringSanitizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

/** Builds every Discord surface as a native Components V2 layout. */
public final class ComponentFactory {
  private ComponentFactory() {
  }

  public static List<MessageTopLevelComponent> createInfo(String title, String description) {
    return createInfo(title, description, UserLanguage.GERMAN);
  }

  public static List<MessageTopLevelComponent> createInfo(
      String title,
      String description,
      UserLanguage language
  ) {
    String safeTitle = safeInline(title, "Information", 120);
    String safeDescription = description == null || description.isBlank()
        ? language.text("Keine weiteren Details verfügbar.", "No further details are available.")
        : MarkdownSanitizer.escape(StringSanitizer.truncate(description, 3_400));

    return List.of(Container.of(
        TextDisplay.of("## " + iconFor(safeTitle) + "  " + safeTitle),
        Separator.createDivider(Separator.Spacing.SMALL),
        TextDisplay.of(safeDescription)
    ).withAccentColor(AppConstants.PRIMARY_COLOR));
  }

  public static List<MessageTopLevelComponent> buildNowPlaying(
      Song song,
      int queueLength,
      int volume,
      boolean paused,
      boolean shuffleMode,
      RepeatMode repeatMode,
      UserLanguage language
  ) {
    List<ContainerChildComponent> children = new ArrayList<>();
    children.add(TextDisplay.of("## 🎵  " + language.text("Jetzt läuft", "Now playing")));

    String songDetails = "### " + safeInline(
        song.name(), language.text("Unbekannter Song", "Unknown song"), 120
    ) + "\n**" + safeInline(
        song.artistName(), language.text("Unbekannter Artist", "Unknown artist"), 80
    ) + "**";
    if (song.thumbnailUrl() != null && !song.thumbnailUrl().isBlank()) {
      try {
        children.add(Section.of(
            Thumbnail.fromUrl(song.thumbnailUrl()).withDescription(
                language.text("Cover von ", "Cover for ") + StringSanitizer.sanitizeLine(
                    song.name(), language.text("diesem Song", "this song"), 100
                )),
            TextDisplay.of(songDetails)
        ));
      } catch (IllegalArgumentException ignored) {
        children.add(TextDisplay.of(songDetails));
      }
    } else {
      children.add(TextDisplay.of(songDetails));
    }

    children.add(Separator.createDivider(Separator.Spacing.SMALL));
    children.add(TextDisplay.of(
        (paused ? "**" + language.text("Pausiert", "Paused") + "**  ·  " : "")
            + "**" + queueLength + "** " + language.text("in Queue", "in queue") + "  ·  "
            + language.text("Lautstärke", "Volume") + " **" + Math.max(0, volume) + "%**"
            + "\nShuffle **" + (shuffleMode ? language.text("An", "On") : language.text("Aus", "Off")) + "**  ·  "
            + "Loop **" + repeatLabel(repeatMode, language) + "**"
    ));
    children.add(Separator.createInvisible(Separator.Spacing.SMALL));
    children.add(buildPrimaryControls(paused, language));
    children.add(buildModeControls(shuffleMode, repeatMode, language));

    return List.of(Container.of(children).withAccentColor(AppConstants.PRIMARY_COLOR));
  }

  public static List<MessageTopLevelComponent> buildInactivePlayer(UserLanguage language) {
    return List.of(Container.of(
        TextDisplay.of("## ⏹️  " + language.text("Wiedergabe beendet", "Playback ended")),
        TextDisplay.of(language.text(
            "Die Session ist abgeschlossen. Starte mit `/play` jederzeit eine neue.",
            "The session has ended. Use `/play` to start a new one at any time."
        ))
    ).withAccentColor(AppConstants.PRIMARY_COLOR));
  }

  public static List<List<MessageTopLevelComponent>> buildLyricsPages(
      Song song,
      String lyrics,
      UserLanguage language
  ) {
    List<String> chunks = StringSanitizer.chunkLyrics(lyrics);
    if (chunks.isEmpty()) {
      return List.of(createInfo(
          language.text("Keine Lyrics", "No lyrics"),
          language.text("Für diesen Song sind keine Lyrics verfügbar.", "No lyrics are available for this song."),
          language
      ));
    }

    List<List<MessageTopLevelComponent>> pages = new ArrayList<>();
    for (int index = 0; index < chunks.size(); index++) {
      String title = safeInline(
          song.name(), language.text("Unbekannter Song", "Unknown song"), 120
      );
      pages.add(pageContainer(
          "🎤  Lyrics",
          "### " + title + "\n-# " + safeInline(
              song.artistName(), language.text("Unbekannter Artist", "Unknown artist"), 80
          ),
          "```text\n" + chunks.get(index) + "\n```",
          index + 1,
          chunks.size(),
          language
      ));
    }
    return pages;
  }

  public static List<List<MessageTopLevelComponent>> buildQueuePages(
      Song currentSong,
      List<Song> upcomingSongs,
      UserLanguage language
  ) {
    String intro = currentSong == null
        ? language.text("Aktuell läuft kein Song.", "No song is currently playing.")
        : "**" + language.text("Jetzt:", "Now:") + "** " + songLine(currentSong, language);

    List<String> items = new ArrayList<>();
    for (int index = 0; index < upcomingSongs.size(); index++) {
      items.add("`" + String.format("%02d", index + 1) + "`  " + songLine(upcomingSongs.get(index), language));
    }

    return buildPagedList(
        "🎶  " + language.text("Warteschlange", "Queue"),
        intro,
        items,
        currentSong == null
            ? language.text("Die Warteschlange ist leer.", "The queue is empty.")
            : language.text("Keine weiteren Songs in der Queue.", "There are no more songs in the queue."),
        language
    );
  }

  public static List<List<MessageTopLevelComponent>> buildPlaylistPages(
      String title,
      List<Song> songs,
      String intro,
      UserLanguage language
  ) {
    List<String> items = new ArrayList<>();
    for (int index = 0; index < songs.size(); index++) {
      items.add("`" + String.format("%03d", index + 1) + "`  " + songLine(songs.get(index), language));
    }
    return buildPagedList(
        "💜  " + MarkdownSanitizer.escape(title),
        intro == null ? null : MarkdownSanitizer.escape(intro),
        items,
        language.text("Keine Songs gefunden.", "No songs found."),
        language
    );
  }

  public static List<MessageTopLevelComponent> buildStats(long totalSeconds, UserLanguage language) {
    return List.of(Container.of(
        TextDisplay.of("## 📊  " + language.text("Deine Hörstatistik", "Your listening stats")),
        Separator.createDivider(Separator.Spacing.SMALL),
        TextDisplay.of("### " + StringSanitizer.formatListeningTime(totalSeconds)
            + "\n-# " + language.text(
                "So lange hast du mit Boocord Music gehört.",
                "This is how long you have listened with Boocord Music."
            ))
    ).withAccentColor(AppConstants.PRIMARY_COLOR));
  }

  private static List<List<MessageTopLevelComponent>> buildPagedList(
      String title,
      String intro,
      List<String> items,
      String emptyMessage,
      UserLanguage language
  ) {
    if (items.isEmpty()) {
      return List.of(createInfo(title, emptyMessage, language));
    }

    int totalPages = (int) Math.ceil((double) items.size() / AppConstants.MAX_LIST_ITEMS_PER_PAGE);
    List<List<MessageTopLevelComponent>> pages = new ArrayList<>();
    for (int start = 0, page = 1; start < items.size(); start += AppConstants.MAX_LIST_ITEMS_PER_PAGE, page++) {
      List<String> pageItems = items.subList(start, Math.min(items.size(), start + AppConstants.MAX_LIST_ITEMS_PER_PAGE));
      pages.add(pageContainer(title, intro, String.join("\n", pageItems), page, totalPages, language));
    }
    return pages;
  }

  private static List<MessageTopLevelComponent> pageContainer(
      String title,
      String intro,
      String content,
      int page,
      int totalPages,
      UserLanguage language
  ) {
    List<ContainerChildComponent> children = new ArrayList<>();
    children.add(TextDisplay.of("## " + StringSanitizer.truncate(title, 180)));
    if (intro != null && !intro.isBlank()) {
      children.add(TextDisplay.of(StringSanitizer.truncate(intro, 700)));
    }
    children.add(Separator.createDivider(Separator.Spacing.SMALL));
    children.add(TextDisplay.of(content));
    children.add(Separator.createInvisible(Separator.Spacing.SMALL));
    children.add(TextDisplay.of("-# " + language.text("Seite", "Page") + " " + page + " / " + totalPages));
    return List.of(Container.of(children).withAccentColor(AppConstants.PRIMARY_COLOR));
  }

  private static ActionRow buildPrimaryControls(boolean paused, UserLanguage language) {
    return ActionRow.of(
        Button.primary(AppConstants.ButtonIds.PAUSE_RESUME,
            paused ? language.text("Fortsetzen", "Resume") : language.text("Pause", "Pause")),
        Button.primary(AppConstants.ButtonIds.SKIP, language.text("Weiter", "Skip")),
        Button.danger(AppConstants.ButtonIds.STOP, "Stop"),
        Button.success(AppConstants.ButtonIds.TOGGLE_FAVORITE, language.text("Favorit", "Favorite")),
        Button.secondary(AppConstants.ButtonIds.LYRICS, "Lyrics")
    );
  }

  private static ActionRow buildModeControls(
      boolean shuffleMode,
      RepeatMode repeatMode,
      UserLanguage language
  ) {
    return ActionRow.of(
        Button.of(shuffleMode ? ButtonStyle.SUCCESS : ButtonStyle.SECONDARY,
            AppConstants.ButtonIds.TOGGLE_SHUFFLE,
            shuffleMode ? language.text("Shuffle an", "Shuffle on") : language.text("Shuffle aus", "Shuffle off")),
        Button.of(repeatMode == RepeatMode.TRACK ? ButtonStyle.SUCCESS : ButtonStyle.SECONDARY,
            AppConstants.ButtonIds.TOGGLE_LOOP_SONG,
            repeatMode == RepeatMode.TRACK
                ? language.text("Song-Loop an", "Song loop on")
                : language.text("Song-Loop aus", "Song loop off")),
        Button.of(repeatMode == RepeatMode.QUEUE ? ButtonStyle.SUCCESS : ButtonStyle.SECONDARY,
            AppConstants.ButtonIds.TOGGLE_REPEAT_QUEUE,
            repeatMode == RepeatMode.QUEUE
                ? language.text("Queue-Loop an", "Queue loop on")
                : language.text("Queue-Loop aus", "Queue loop off"))
    );
  }

  private static String songLine(Song song, UserLanguage language) {
    return "**" + safeInline(
        song.name(), language.text("Unbekannter Song", "Unknown song"), 110
    ) + "**  ·  " + safeInline(
        song.artistName(), language.text("Unbekannter Artist", "Unknown artist"), 70
    );
  }

  private static String repeatLabel(RepeatMode repeatMode, UserLanguage language) {
    return switch (repeatMode) {
      case TRACK -> language.text("Song", "Track");
      case QUEUE -> "Queue";
      case OFF -> language.text("Aus", "Off");
    };
  }

  private static String safeInline(String value, String fallback, int maxLength) {
    return MarkdownSanitizer.escape(StringSanitizer.sanitizeLine(value, fallback, maxLength));
  }

  private static String iconFor(String title) {
    String normalized = title.toLowerCase(Locale.ROOT);
    if (normalized.contains("fehler") || normalized.contains("error") || normalized.contains("problem")) {
      return "⚠️";
    }
    if (normalized.contains("nicht") || normalized.contains("keine") || normalized.contains("leer")
        || normalized.contains("no ") || normalized.startsWith("no")) {
      return "🔎";
    }
    if (normalized.contains("gestoppt") || normalized.contains("beendet")
        || normalized.contains("stopped") || normalized.contains("ended")) {
      return "⏹️";
    }
    return "💜";
  }
}
