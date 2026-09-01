package com.boocord.music;

import com.boocord.music.api.BoocordApiClient;
import com.boocord.music.audio.PlaybackService;
import com.boocord.music.config.BotConfig;
import com.boocord.music.config.ConfigLoader;
import com.boocord.music.db.DatabaseService;
import com.boocord.music.discord.CommandRegistrar;
import com.boocord.music.discord.InteractionListener;
import com.boocord.music.discord.PaginationService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import moe.kyokobot.libdave.NativeDaveFactory;
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.messages.MessageRequest;

public final class BoocordMusicApplication {
  private static final Logger LOGGER = Logger.getLogger(BoocordMusicApplication.class.getName());

  private BoocordMusicApplication() {
  }

  public static void main(String[] args) throws Exception {
    BotConfig config = new ConfigLoader().load();
    DatabaseService databaseService = new DatabaseService(config.database());
    databaseService.initialize();
    MessageRequest.setDefaultMentions(java.util.Set.of());

    JDA jda = JDABuilder.createDefault(config.discordToken())
        .enableIntents(
            GatewayIntent.GUILD_VOICE_STATES
        )
        .enableCache(CacheFlag.VOICE_STATE)
        .setMemberCachePolicy(MemberCachePolicy.VOICE)
        .setAudioModuleConfig(new AudioModuleConfig()
            .withDaveSessionFactory(new LDJDADaveSessionFactory(new NativeDaveFactory())))
        .build()
        .awaitReady();

    BoocordApiClient apiClient = new BoocordApiClient(config.api());
    PaginationService paginationService = new PaginationService(databaseService);
    PlaybackService playbackService = new PlaybackService(jda, config, apiClient, databaseService);
    InteractionListener interactionListener = new InteractionListener(
        apiClient,
        databaseService,
        playbackService,
        paginationService
    );

    jda.addEventListener(interactionListener);
    CommandRegistrar.register(jda);
    playbackService.restoreSessions();

    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    Runnable presenceTask = () -> jda.getPresence().setPresence(
        OnlineStatus.ONLINE,
        Activity.playing("music.boocord.com")
    );
    presenceTask.run();
    scheduler.scheduleAtFixedRate(presenceTask, 5, 5, TimeUnit.MINUTES);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      scheduler.shutdownNow();
      try {
        interactionListener.shutdown();
        playbackService.shutdown();
      } catch (Exception exception) {
        LOGGER.log(Level.WARNING, "Fehler beim Playback-Shutdown.", exception);
      }

      try {
        databaseService.close();
      } catch (Exception exception) {
        LOGGER.log(Level.WARNING, "Fehler beim Datenbank-Shutdown.", exception);
      }

      try {
        jda.shutdownNow();
      } catch (Exception exception) {
        LOGGER.log(Level.WARNING, "Fehler beim Discord-Shutdown.", exception);
      }
    }));
  }
}
