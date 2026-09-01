# Contributing

Thanks for helping improve Boocord Music Discord. Bug reports, documentation improvements, tests, and focused code changes are welcome.

By participating, you agree to follow the [Code of Conduct](./CODE_OF_CONDUCT.md).

## Before opening an issue

- Search existing issues first.
- Use the provided bug or feature template.
- Remove bot tokens, database credentials, private server IDs, and signed media URLs from logs and screenshots.
- Report security problems privately as described in [SECURITY.md](./SECURITY.md).
- For API availability or content-policy questions, consult [API_TERMS.md](./API_TERMS.md) and the linked service terms.

## Local setup

1. Fork and clone the repository.
2. Install Java 17+ and Maven 3.9+.
3. Copy `.env.example` to `.env` only if you need to run the bot. The unit-test build does not require credentials.
4. Run `mvn clean verify` before making changes.

For runtime testing, use a development Discord application and a disposable database rather than production credentials or data.

## Pull requests

1. Create a focused branch from `main`.
2. Keep unrelated formatting or dependency changes out of the pull request.
3. Add or update tests for changed behavior.
4. Update the README, changelog, and `.env.example` when configuration or user behavior changes.
5. Run `mvn clean verify` and include meaningful manual smoke-test results in the pull request description.

The existing Java style uses UTF-8, LF line endings, two-space indentation, explicit names, and small purpose-specific classes. Do not commit generated files from `target/`, local IDE metadata, `.env`, or credentials.

## Commit and review expectations

- Use a concise imperative summary, such as `Fix queue restoration after restart`.
- Explain the reason for non-obvious behavior in code comments or the pull request.
- Expect maintainers to request changes for security, licensing, API compatibility, or operational risk.
- Contributions are accepted under the repository's MIT License.
