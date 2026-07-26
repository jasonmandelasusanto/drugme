<div align="center">
  <img src="icon.png" width="128" alt="DrugMe logo" />

  # DrugMe

  **A private, offline-first medication reminder for Android.**

  Track medication schedules and adherence locally, with optional end-to-end encrypted
  backup across devices.

  [![CI](https://github.com/jasonmandelasusanto/drugme/actions/workflows/ci.yml/badge.svg)](https://github.com/jasonmandelasusanto/drugme/actions/workflows/ci.yml)
  [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
</div>

---

## About

A medication reminder can fail silently: a dropped alarm or unseen notification can look
identical to everything working. DrugMe keeps its reminder engine on-device, requires no
account, restores alarms after reboots and time changes, and runs a periodic repair pass.
Google sign-in is offered only for encrypted backup and sync.

DrugMe is a reminder and personal record-keeping tool. It does not provide medical advice,
diagnose conditions, verify prescriptions, or replace a doctor or pharmacist.

## Features

- **On-device dose reminders** with Taken, Snooze, and Skip actions.
- **Flexible schedules** for multiple daily times, selected weekdays, or every N days, with
  optional start and end dates, per-time doses, and food instructions.
- **Home dashboard** showing the next dose, today's progress, overdue doses, refill warnings,
  and reminder health.
- **Monthly schedule calendar** with medication-count dots and a focused dose list for the
  selected day.
- **Adherence and punctuality insights**, dose notes, status and medication filters, and CSV
  export.
- **Stock and refill tracking** with estimated run-out warnings.
- **Discreet notifications** that hide medication names and dose details.
- **Light and dark themes**, selected during first-time setup and changeable in Settings.
- **Medication lookup and information** using an offline RxNorm-derived catalog plus optional
  RxNorm, MedlinePlus, openFDA, and DailyMed data over HTTPS.
- **Optional end-to-end encrypted backup and sync** with Google sign-in and Cloud Firestore.
- **Verified self-updates** in release builds: DrugMe checks GitHub Releases daily, verifies
  the downloaded APK's SHA-256 digest and signing certificate, and asks Android for
  installation confirmation.

## Requirements

- Android 8.0 (API 26) or newer
- JDK 17
- Android SDK Platform 36
- Android Studio or the included Gradle wrapper

## Installing the app

Official APKs are published under
[GitHub Releases](https://github.com/jasonmandelasusanto/drugme/releases). Download the
release APK on your Android device and allow installation from that source when Android
asks.

After installation:

1. Complete the in-app disclaimer and appearance setup.
2. Allow notifications.
3. Review the battery-optimisation guidance.
4. On devices with aggressive background restrictions, follow the additional manufacturer
   instructions shown by the app.
5. Create a test reminder a few minutes ahead and confirm it fires while the phone is locked.

Release builds check for stable GitHub releases once per day. Debug builds never download
updates. Android always requires the user to approve installation.

## Building from source

Clone the repository:

```bash
git clone https://github.com/jasonmandelasusanto/drugme.git
cd drugme
```

The Google Services Gradle plugin requires a configuration file even when Firebase features
are not being used. For an offline/local development build, copy the committed placeholder:

```bash
cp .github/google-services.placeholder.json app/google-services.json
```

On Windows PowerShell:

```powershell
Copy-Item .github/google-services.placeholder.json app/google-services.json
```

Then run the same checks used by CI:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

On Windows, use `.\gradlew.bat` instead of `./gradlew`.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The placeholder
Firebase project is intentionally non-functional: medication reminders and local storage
work, but Google sign-in and cloud sync do not.

### Enabling Firebase backup and sync

To develop backup and sync features:

1. Create your own Firebase project and Android app registrations for
   `com.drugme.app` and `com.drugme.app.debug`.
2. Replace `app/google-services.json` with the file from that project.
3. Deploy [`firestore.rules`](firestore.rules).
4. Follow the App Check and backend checklist in
   [`docs/firebase-hardening.md`](docs/firebase-hardening.md).

Never commit a real Firebase configuration, service-account credential, signing key, or
`keystore.properties`. The repository's `.gitignore` excludes them.

Fork release builds should also change the hard-coded GitHub Releases endpoint in
`AppUpdateRepository` to their own repository. An APK downloaded from another signer will
be rejected by design.

## Privacy and security

Medication data is stored locally in a Room database inside Android's
credential-encrypted application sandbox. The database itself is not additionally encrypted
with SQLCipher. Android cloud/device-transfer backup is disabled so plaintext medication
history is not copied outside that boundary.

If encrypted backup is enabled:

- Records are encrypted on-device with AES-256-GCM before upload.
- Each encrypted record is bound to its owner and record identifier using authenticated
  additional data.
- The data-encryption key is wrapped using a key derived from the passphrase with Argon2id.
- A one-time recovery code provides the only alternative recovery path.
- Firestore stores ciphertext and wrapped key material; its security rules separately enforce
  ownership.

Losing both the passphrase and recovery code makes the encrypted backup unrecoverable.

Please report vulnerabilities privately according to [`SECURITY.md`](SECURITY.md). Do not
put vulnerability details in a public issue.

## Contributing

Bug reports, feature proposals, documentation improvements, and pull requests are welcome.

- Use [GitHub Issues](https://github.com/jasonmandelasusanto/drugme/issues) for ordinary bugs
  and feature requests.
- Read [`CONTRIBUTING.md`](CONTRIBUTING.md) before opening a pull request.
- Use GitHub's private vulnerability reporting for security issues.

This is a medication-related application, so changes to reminder timing, persistence,
database migrations, cryptography, authentication, or update verification require especially
careful tests and review.

## Technology

Kotlin · Jetpack Compose and Material 3 · Room · Hilt · WorkManager · Firebase Auth and
Firestore · Coil · Bouncy Castle

## License

[MIT](LICENSE) © 2026 Jason Mandela
