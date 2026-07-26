# Contributing to DrugMe

Thank you for helping improve DrugMe. Bug reports, feature ideas, documentation fixes, tests,
and code contributions are welcome.

DrugMe is medication-related software. A change that appears small can alter reminder timing,
medical-data privacy, adherence history, or recovery from an app update. Please favour clear,
well-tested changes over large rewrites.

## Before opening an issue

Search the [existing issues](https://github.com/jasonmandelasusanto/drugme/issues) first.

For a bug report, include:

- The DrugMe version or commit.
- Android version and device manufacturer/model.
- What you expected and what happened.
- Minimal reproduction steps.
- Relevant logs with medication names, email addresses, account identifiers, recovery codes,
  Firebase configuration, and other personal or secret data removed.

For a feature request, explain the user problem before proposing an implementation. For
substantial features or architecture changes, open an issue before investing in a pull
request so the direction can be discussed.

Security vulnerabilities must follow [`SECURITY.md`](SECURITY.md) and must not be filed as
public issues.

## Development setup

Follow the build instructions in [`README.md`](README.md). A local build can use the committed
placeholder Firebase configuration:

```bash
cp .github/google-services.placeholder.json app/google-services.json
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

On Windows, use `Copy-Item` and `.\gradlew.bat`.

Cloud backup and authentication work only with a real Firebase project configured according
to [`docs/firebase-hardening.md`](docs/firebase-hardening.md). Never commit real Firebase
configuration, credentials, recovery material, or signing keys.

## Pull requests

1. Fork the repository and create a focused branch from the latest `main`.
2. Keep the pull request limited to one coherent bug or feature.
3. Add or update tests for behavioural changes.
4. Update documentation when behaviour, setup, permissions, privacy, or security boundaries
   change.
5. Run the relevant unit tests and lint locally.
6. Open a pull request with the problem, approach, user-visible effect, and verification
   commands.

The complete pre-submission check is:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

CI repeats unit tests, lint, and the debug build on every pull request to `main`.

In the pull request description:

- Link the related issue, if one exists.
- Call out database schema or migration changes.
- Call out new permissions, network destinations, analytics, or data collection.
- Include screenshots or a short recording for visible UI changes.
- Describe real-device reminder testing when alarm or notification behaviour changes.

Do not include generated build output, APKs, IDE configuration, real health data, or unrelated
formatting changes.

## High-risk areas

Changes in these areas need especially careful review and focused regression tests:

- Alarm scheduling, reboot recovery, time zones, snoozing, and missed-dose transitions.
- Room entities, migrations, backup/restore, stock adjustment, and adherence calculations.
- Authentication, account deletion, Firestore rules, encryption, passphrase handling, and
  recovery codes.
- Notification privacy and exported Android components.
- Release signing, version comparison, download verification, and self-updates.

Avoid weakening a safety check merely to make a test or build pass. Explain any deliberate
trade-off in the pull request.

## Medical and privacy content

- Do not present app output as medical advice.
- Prefer authoritative medication data sources and preserve attribution.
- Do not add analytics, tracking, advertising, or new data transmission without explicit
  discussion and documentation.
- Use synthetic test fixtures; never commit real medication histories or account data.

## Review and licensing

Opening a pull request does not guarantee that it will be merged. Maintainers may request
changes, choose another implementation, or decline work that does not fit the project.

By contributing, you agree that your contribution is provided under the repository's
[MIT License](LICENSE).
