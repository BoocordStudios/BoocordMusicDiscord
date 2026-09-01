package com.boocord.music.discord;

import com.boocord.music.db.DatabaseService;
import com.boocord.music.model.UserLanguage;
import com.boocord.music.util.AppConstants;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;

public final class PaginationService {
  private static final long SESSION_TTL_MS = 15 * 60 * 1_000L;
  private static final int MAX_SESSIONS = 1_000;

  private final DatabaseService databaseService;
  private final Map<String, PaginationSession> sessions = new ConcurrentHashMap<>();

  public PaginationService(DatabaseService databaseService) {
    this.databaseService = databaseService;
  }

  public void sendPaginatedReply(
      InteractionHook hook,
      String ownerId,
      List<List<MessageTopLevelComponent>> pages,
      UserLanguage language
  ) {
    if (pages.isEmpty()) {
      hook.editOriginalComponents(ComponentFactory.createInfo(
              language.text("Leer", "Empty"),
              language.text("Keine Einträge gefunden.", "No entries found."),
              language
          ))
          .useComponentsV2()
          .queue();
      return;
    }

    if (pages.size() == 1) {
      hook.editOriginalComponents(pages.get(0)).useComponentsV2().queue();
      return;
    }

    makeRoomForSession();
    String token = UUID.randomUUID().toString();
    sessions.put(token, new PaginationSession(
        ownerId,
        pages,
        Instant.now().toEpochMilli() + SESSION_TTL_MS,
        language
    ));

    hook.editOriginalComponents(withNavigation(pages.get(0), token, 0, pages.size(), language))
        .useComponentsV2()
        .queue();
  }

  public boolean handle(ButtonInteractionEvent event) {
    String customId = event.getComponentId();
    if (!customId.startsWith(AppConstants.PAGINATION_PREFIX + ":")) {
      return false;
    }

    UserLanguage interactionLanguage = getUserLanguage(event.getUser().getId());

    String[] parts = customId.split(":");
    if (parts.length != 3) {
      event.replyComponents(ComponentFactory.createInfo(
              interactionLanguage.text("Fehler", "Error"),
              interactionLanguage.text("Ungültige Pagination-Aktion.", "Invalid pagination action."),
              interactionLanguage
          ))
          .useComponentsV2()
          .setEphemeral(true)
          .queue();
      return true;
    }

    int targetPage;
    try {
      targetPage = Integer.parseInt(parts[2]);
    } catch (NumberFormatException exception) {
      event.replyComponents(ComponentFactory.createInfo(
              interactionLanguage.text("Fehler", "Error"),
              interactionLanguage.text("Ungültige Pagination-Aktion.", "Invalid pagination action."),
              interactionLanguage
          ))
          .useComponentsV2()
          .setEphemeral(true)
          .queue();
      return true;
    }

    PaginationSession session = sessions.get(parts[1]);
    if (session == null || session.expiresAt() < Instant.now().toEpochMilli()) {
      sessions.remove(parts[1]);
      UserLanguage language = session == null ? interactionLanguage : session.language();
      event.replyComponents(ComponentFactory.createInfo(
              language.text("Abgelaufen", "Expired"),
              language.text("Diese Pagination ist nicht mehr verfügbar.", "This pagination is no longer available."),
              language
          ))
          .useComponentsV2()
          .setEphemeral(true)
          .queue();
      return true;
    }

    if (!session.ownerId().equals(event.getUser().getId())) {
      event.replyComponents(ComponentFactory.createInfo(
              interactionLanguage.text("Nicht erlaubt", "Not allowed"),
              interactionLanguage.text("Diese Navigation gehört nicht zu dir.", "This navigation does not belong to you."),
              interactionLanguage
          ))
          .useComponentsV2()
          .setEphemeral(true)
          .queue();
      return true;
    }

    int safePage = Math.max(0, Math.min(targetPage, session.pages().size() - 1));
    event.editComponents(withNavigation(
            session.pages().get(safePage),
            parts[1],
            safePage,
            session.pages().size(),
            session.language()
        ))
        .useComponentsV2()
        .queue();
    return true;
  }

  private List<ActionRow> buildButtons(
      String token,
      int currentPage,
      int totalPages,
      UserLanguage language
  ) {
    return List.of(ActionRow.of(
        Button.secondary(
            pageId(token, Math.max(0, currentPage - 1)),
            language.text("← Zurück", "← Back")
        )
            .withDisabled(currentPage == 0),
        Button.primary(
            pageId(token, Math.min(totalPages - 1, currentPage + 1)),
            language.text("Weiter →", "Next →")
        )
            .withDisabled(currentPage >= totalPages - 1)
    ));
  }

  private List<MessageTopLevelComponent> withNavigation(
      List<MessageTopLevelComponent> page,
      String token,
      int currentPage,
      int totalPages,
      UserLanguage language
  ) {
    List<MessageTopLevelComponent> components = new java.util.ArrayList<>(page);
    components.addAll(buildButtons(token, currentPage, totalPages, language));
    return components;
  }

  private String pageId(String token, int page) {
    return AppConstants.PAGINATION_PREFIX + ":" + token + ":" + page;
  }

  private void makeRoomForSession() {
    long now = Instant.now().toEpochMilli();
    sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
    if (sessions.size() < MAX_SESSIONS) {
      return;
    }

    sessions.entrySet().stream()
        .min(Comparator.comparingLong(entry -> entry.getValue().expiresAt()))
        .ifPresent(entry -> sessions.remove(entry.getKey(), entry.getValue()));
  }

  private UserLanguage getUserLanguage(String userId) {
    try {
      return databaseService.getUserLanguage(userId);
    } catch (SQLException exception) {
      return UserLanguage.GERMAN;
    }
  }

  private record PaginationSession(
      String ownerId,
      List<List<MessageTopLevelComponent>> pages,
      long expiresAt,
      UserLanguage language
  ) {
    private PaginationSession {
      pages = pages.stream().map(List::copyOf).toList();
    }
  }
}
