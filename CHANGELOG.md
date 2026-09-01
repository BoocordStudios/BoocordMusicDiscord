# Changelog

All notable changes to this project are documented here. The project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [5.0.0] - 2026-09-01

### Added

- Native Java 17 application built with JDA and Lavaplayer
- Discord Components V2 layouts for all commands and playback controls
- Discord DAVE voice-encryption support
- German and English user interfaces
- Track loop, queue repeat, volume controls, favorites, lyrics, pagination, and listening statistics
- Playback-session persistence and restoration
- API caching, request retries, timeouts, and safer user-facing string handling
- Automated tests, static analysis, dependency checks, license inventory, and GitHub Actions CI

### Changed

- Replaced the Node.js runtime and Discord.js implementation
- Moved configuration to a root `.env` file and expanded the available player/API settings
- Replaced FFmpeg subprocess playback with in-process Lavaplayer playback
- Updated the database schema while retaining compatible existing data

### Security

- Updated direct dependencies and constrained vulnerable transitive dependencies to patched versions
- Added private vulnerability-reporting guidance and automated dependency updates

[Unreleased]: https://github.com/BoocordStudios/BoocordMusicDiscord/compare/v5.0.0...HEAD
[5.0.0]: https://github.com/BoocordStudios/BoocordMusicDiscord/releases/tag/v5.0.0
