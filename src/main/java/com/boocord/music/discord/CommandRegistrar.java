package com.boocord.music.discord;

import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

public final class CommandRegistrar {
  private CommandRegistrar() {
  }

  public static void register(JDA jda) {
    jda.updateCommands()
        .addCommands(List.of(
            Commands.slash("play", "Fügt einen Song zur Warteschlange hinzu und startet die Wiedergabe.")
                .addOptions(new OptionData(OptionType.STRING, "query", "Songname oder Playlist-Nummer", true)
                    .setRequiredLength(1, 200)),
            Commands.slash("playlist", "Zeigt die komplette Boocord-Playlist an."),
            Commands.slash("search", "Sucht Songs in der Boocord-Playlist.")
                .addOptions(new OptionData(OptionType.STRING, "search", "Suchbegriff", true)
                    .setRequiredLength(1, 200)),
            Commands.slash("queue", "Zeigt die aktuelle Warteschlange an."),
            Commands.slash("skip", "Überspringt den aktuellen Song."),
            Commands.slash("pause", "Pausiert die Wiedergabe."),
            Commands.slash("resume", "Setzt die Wiedergabe fort."),
            Commands.slash("stop", "Stoppt die Wiedergabe und trennt den Bot."),
            Commands.slash("shuffle", "Aktiviert Shuffle und füllt die Queue mit allen Songs."),
            Commands.slash("requestsong", "Fügt einen Song per ID zur Warteschlange hinzu.")
                .addOptions(new OptionData(OptionType.INTEGER, "id", "Die Song-ID", true)
                    .setRequiredRange(1, Integer.MAX_VALUE)),
            Commands.slash("lyrics", "Zeigt die Lyrics des aktuell laufenden Songs an."),
            Commands.slash("favorites", "Zeigt deine Favoriten an."),
            Commands.slash("favoplay", "Spielt alle deine Favoriten als Shuffle ab."),
            Commands.slash("volume", "Stellt die Lautstärke ein (0.0 bis 2.0).")
                .addOptions(new OptionData(OptionType.NUMBER, "value", "Lautstärke-Multiplikator, z. B. 1.0", true)
                    .setRequiredRange(0, 2)),
            Commands.slash("loop", "Schaltet den Song-Loop ein oder aus."),
            Commands.slash("repeat", "Schaltet den Queue-Repeat ein oder aus."),
            Commands.slash("favotoggle", "Fügt den aktuellen Song als Favorit hinzu oder entfernt ihn."),
            Commands.slash("stats", "Zeigt deine gesamte Hörzeit mit dem Bot an."),
            Commands.slash("language", "Ändert deine Sprache / Changes your language.")
                .addOptions(new OptionData(OptionType.STRING, "language", "Deutsch oder English", true)
                    .addChoice("Deutsch", "de")
                    .addChoice("English", "en"))
        ))
        .queue();
  }
}
