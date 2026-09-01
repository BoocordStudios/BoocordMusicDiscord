# Boocord Music Discord

[![CI](https://github.com/BoocordStudios/BoocordMusicDiscord/actions/workflows/ci.yml/badge.svg)](https://github.com/BoocordStudios/BoocordMusicDiscord/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/BoocordStudios/BoocordMusicDiscord)](https://github.com/BoocordStudios/BoocordMusicDiscord/releases/latest)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-ED8B00)](https://adoptium.net/)
[![MIT license](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)

Boocord Music is a self-hosted Java Discord music bot built with JDA, Lavaplayer, Discord Components V2, and MySQL. It streams tracks from the Boocord Music API directly in the bot process, so a separate Lavalink server is not required.

Version 5 is a breaking rewrite of the former Node.js bot. Existing database data is migrated in place, but the runtime and deployment commands have changed.

## Highlights

- Native Discord Components V2 interface for commands, playback controls, and pagination
- Direct in-process audio playback with Discord DAVE voice encryption
- Queue, shuffle, track loop, queue repeat, favorites, lyrics, volume, and listening statistics
- Automatic database setup and schema upgrades
- Playback-session restoration after restarts
- German and English user interfaces
- API caching, timeouts, retries, and Discord-safe text handling

## Requirements

- Java 17 or newer
- Maven 3.9 or newer when building from source
- MySQL or MariaDB
- A Discord application with a bot token
- Network access to `music.boocord.com`, Discord, and your database

The prebuilt release JAR includes the Linux x86-64 DAVE native library. See [Platform-specific builds](#platform-specific-builds) for other operating systems and architectures.

## Quick start

1. Clone the repository:

   ```bash
   git clone https://github.com/BoocordStudios/BoocordMusicDiscord.git
   cd BoocordMusicDiscord
   ```

2. Create an empty database and a dedicated database user. The bot creates and upgrades its own tables at startup.

3. Copy the environment template and fill in your credentials:

   ```bash
   cp .env.example .env
   ```

   On Windows PowerShell, use `Copy-Item .env.example .env`.

4. Build and verify the project:

   ```bash
   mvn clean verify
   ```

5. Start the bot:

   ```bash
   java -jar target/boocordmusic.jar
   ```

You can also download the prebuilt JAR from the [latest GitHub release](https://github.com/BoocordStudios/BoocordMusicDiscord/releases/latest).

## Discord setup

Create a bot in the [Discord Developer Portal](https://discord.com/developers/applications), reset or copy its token, and invite it with the `bot` and `applications.commands` scopes. Give it these permissions in the channels where it will run:

- View Channels
- Send Messages
- Read Message History
- Connect
- Speak

The bot does not require the privileged Message Content intent. Never commit or paste your bot token into an issue or log.

## Configuration

Environment variables take precedence over values in the local `.env` file.

| Variable | Required | Default | Description |
| --- | --- | --- | --- |
| `DISCORD_TOKEN` | Yes | — | Discord bot token |
| `DB_HOST` | Yes | — | MySQL or MariaDB host |
| `DB_PORT` | No | `3306` | Database port from 1 to 65535 |
| `DB_USER` | Yes | — | Database user |
| `DB_PASSWORD` | Yes | — | Database password |
| `DB_NAME` | Yes | — | Database name |
| `PLAYER_IDLE_TIMEOUT_MS` | No | `300000` | Disconnect delay after playback becomes idle; minimum 30000 ms |
| `PLAYER_DEFAULT_VOLUME_PERCENT` | No | `100` | Initial player volume from 0 to 200 percent |
| `API_TIMEOUT_MS` | No | `15000` | Boocord Music API request timeout from 1000 to 120000 ms |
| `API_RETRY_COUNT` | No | `2` | Additional attempts after a failed API request; maximum 10 |
| `PLAYLIST_CACHE_TTL_MS` | No | `60000` | Playlist cache lifetime; minimum 1000 ms |
| `SONG_CACHE_TTL_MS` | No | `600000` | Song cache lifetime; minimum 1000 ms |

## Platform-specific builds

The default Maven build targets Linux x86-64 with glibc. To build for another supported platform, override the DAVE native artifact:

```bash
mvn -Dlibdave.native.artifact=natives-win-x86-64 clean verify
```

Common values are:

- `natives-linux-x86-64`
- `natives-linux-aarch64`
- `natives-linux-musl-x86-64`
- `natives-linux-musl-aarch64`
- `natives-win-x86-64`
- `natives-win-aarch64`
- `natives-darwin`

## Commands

| Command | Purpose |
| --- | --- |
| `/play` | Find and enqueue a song |
| `/playlist` | Browse the complete playlist |
| `/search` | Search the playlist |
| `/queue` | Show the current queue |
| `/skip`, `/pause`, `/resume`, `/stop` | Control playback |
| `/shuffle`, `/loop`, `/repeat` | Control playback modes |
| `/requestsong` | Enqueue a song by API ID |
| `/lyrics` | Show lyrics for the current song |
| `/favorites`, `/favoplay`, `/favotoggle` | Manage and play favorites |
| `/volume` | Set volume from 0.0 to 2.0 |
| `/stats` | Show personal listening time |
| `/language` | Select German or English |

## Development

- Run all checks: `mvn clean verify`
- Compile only: `mvn compile`
- Run locally: `mvn exec:java`

The verification build runs unit tests, dependency convergence checks, static analysis, third-party license validation, and packaging. The generated fat JAR includes a `THIRD-PARTY.txt` dependency-license inventory.

See [CONTRIBUTING.md](./CONTRIBUTING.md) before opening a pull request. Maintainers can use the [owner update guide](./docs/OWNER_UPDATE_GUIDE.md) for deployment steps.

## Data and privacy

Each self-hosted operator controls their own bot instance and database. The bot has no separate analytics or telemetry service, but it stores the Discord IDs and playback data needed for its features. See [PRIVACY.md](./PRIVACY.md) for the exact data categories, retention behavior, and deletion guidance.

## Security

Do not open public issues for vulnerabilities or exposed credentials. Follow the private reporting process in [SECURITY.md](./SECURITY.md).

## API and content terms

The MIT license applies to this repository's source code. Music, metadata, and streams delivered by the Boocord Music API are separate and subject to [API_TERMS.md](./API_TERMS.md) and the current service terms.

## License

Copyright © 2025–2026 Boocord Studios. The source code is available under the [MIT License](./LICENSE).
