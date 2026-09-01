# Privacy information for self-hosted operators

Boocord Music Discord is self-hosted software. The person or organization running an instance controls that instance's Discord application, configuration, logs, and database. This document describes the software's default behavior; an operator may configure additional infrastructure with its own policies.

## Data stored by the bot

The application stores only the data required for its features:

- Discord guild, voice-channel, text-channel, and control-message IDs for playback restoration
- Current song, queue, playback mode, pause state, and volume for active sessions
- Discord user IDs paired with favorite song IDs
- Discord user IDs paired with accumulated listening time
- Discord user IDs paired with the selected interface language

Active listening start times and pagination sessions are also held temporarily in memory. The application does not persist Discord message content and does not include a separate analytics or advertising service.

## Network services

The bot communicates with Discord, the operator's MySQL or MariaDB server, and `music.boocord.com`. Song searches and API identifiers are sent to the Boocord Music API. Discord handles interactions and voice connectivity under Discord's own policies.

## Retention and deletion

Playback-session rows are removed when playback is stopped or an idle session is cleaned up. Favorites, listening statistics, and language settings remain until the operator deletes them.

An operator can delete a user's stored application data with the following parameterized queries, substituting the requesting user's Discord ID:

```sql
DELETE FROM user_favorites WHERE user_id = ?;
DELETE FROM user_stats WHERE user_id = ?;
DELETE FROM user_settings WHERE user_id = ?;
```

Operators should establish their own retention, backup, access-control, and user-request procedures and should publish contact information for users of their instance.

## Logs and credentials

Application logs can contain Discord guild IDs, song IDs, song names, and error details. Logs are written to standard output by default and are retained according to the operator's process manager or hosting platform. Bot tokens and database credentials are read from environment variables or `.env`; they are not intentionally logged.
