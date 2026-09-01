# Owner update guide

This guide covers an existing self-hosted installation. For first-time setup, follow the main [README](../README.md#quick-start).

## Before updating

1. Confirm the working tree is clean with `git status`.
2. Back up the application database and the local `.env` file.
3. Read [CHANGELOG.md](../CHANGELOG.md), especially any breaking changes.
4. Confirm Java 17+ and Maven 3.9+ are available.

## Update from Git

```bash
git pull --ff-only
mvn clean verify
java -jar target/boocordmusic.jar
```

Compare `.env.example` with the existing `.env` and add any new variables before restarting the service.

## Update from a release

1. Download `boocordmusic-<version>-linux-x86-64.jar` from the matching GitHub release.
2. Verify the SHA-256 checksum shown in the release notes.
3. Stop the old process cleanly so playback state and listening statistics can be flushed.
4. Replace the previous JAR and start the new one with the same environment configuration.

## Database migrations

The application creates required tables and performs compatible schema extensions at startup. A database backup is still recommended before every upgrade. Do not run two versions against the same database during an upgrade.

## Smoke checks

After the bot reports ready, verify:

1. `/playlist` and `/search`
2. `/play`, `/pause`, `/resume`, and `/skip`
3. `/shuffle`, `/loop`, and `/repeat`
4. `/lyrics`, `/favorites`, `/favoplay`, and `/stats`
5. Voice reconnect and playback-session restoration after one controlled restart

## Rollback

Stop the application, restore the previous JAR, and restart it. If an update changed persisted data in a way the old version cannot read, restore the database backup as well.

## Common failures

- `Missing environment variable` / `Fehlende Umgebungsvariable`: compare `.env` with `.env.example`.
- The bot cannot join or speak: verify Discord permissions, the selected DAVE native artifact, and outbound connectivity.
- Playback fails after deployment: verify the runtime platform, Java version, and direct HTTP access to the song URLs.
- Database startup fails: verify credentials, grants, server availability, and the configured database name.
