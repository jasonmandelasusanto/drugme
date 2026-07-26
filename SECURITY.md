# Security Policy

DrugMe handles medication schedules and health-related personal data. Security and privacy
reports are taken seriously, even when the issue appears to affect only local data or a
development build.

## Supported versions

DrugMe is currently maintained by one person. Security fixes are made for:

| Version | Supported |
|---|---|
| Latest release | Yes |
| Current `main` branch | Yes |
| Older releases and arbitrary forks | No |

Users should update to the newest release after a security fix is published.

## Reporting a vulnerability

**Do not open a public issue, discussion, or pull request containing vulnerability details.**

Use
[GitHub private vulnerability reporting](https://github.com/jasonmandelasusanto/drugme/security/advisories/new).
The report is visible only to the repository's security advisory collaborators.

If that form is unavailable, open a public issue that contains no technical details and asks
the maintainer to provide a private reporting channel. Do not include logs, screenshots,
proof-of-concept code, affected endpoints, or user data in that issue.

Please include as much of the following as is practical:

- A concise description of the vulnerability and its potential impact.
- The affected commit, release, Android version, and device type.
- Reproduction steps or a minimal proof of concept.
- Whether authentication, physical device access, a rooted device, or user interaction is
  required.
- Any suggested mitigation.
- Whether you intend to disclose the issue publicly and, if so, your proposed timeline.

Reports are acknowledged and assessed on a best-effort basis. There is no guaranteed response
or remediation SLA. The maintainer will try to keep the reporter informed, coordinate a fix
and release, and agree on disclosure timing appropriate to the severity.

## Research guidelines

When investigating DrugMe:

- Use devices, accounts, Firebase projects, and data that you own or are authorised to test.
- Do not access, modify, retain, or disclose another person's medication or account data.
- Do not perform denial-of-service testing, spam notifications, degrade the public backend,
  or use social engineering.
- Minimise collected data and delete it after the report is resolved.
- Stop testing and report immediately if you encounter real user data.
- Give the project a reasonable opportunity to investigate and publish a fix before public
  disclosure.

Good-faith research that follows this policy is welcomed. This policy does not authorise
testing of Google, Firebase, GitHub, Android, or any other third-party service.

## In scope

- The Android application code in this repository.
- Authentication, account deletion, and encrypted backup/sync flows.
- The AES-GCM envelope, Argon2id key derivation, recovery flow, and key caching.
- Firestore access controls in [`firestore.rules`](firestore.rules).
- Exported Android components, notification actions, alarm intents, and file sharing.
- The GitHub Release update checker, APK digest verification, signer verification, and
  PackageInstaller flow.
- Privacy failures that expose medication names, schedules, adherence history, notes, or
  account identifiers outside their intended boundary.

## Out of scope and documented boundaries

The following are not vulnerabilities by themselves:

- **Loss of both the passphrase and recovery code.** Encrypted backup is intentionally
  unrecoverable without either secret.
- **The Firebase client API key in an APK.** It identifies a Firebase project and is not an
  authentication secret. Firestore rules, authentication, and App Check provide the actual
  backend controls.
- **Local data on a rooted, instrumented, or already-unlocked compromised device.** The Room
  database is plaintext inside Android's credential-encrypted app sandbox; it is not protected
  by SQLCipher. Android backup and device-transfer extraction are disabled.
- **Modified forks or builds signed and distributed by another party.** Fork maintainers are
  responsible for their backend, release endpoint, signing key, and published APKs.
- **Misconfiguration in a contributor's own Firebase project or deployment** when it does not
  affect the official project.
- **Reminder delivery blocked by denied notification permission, battery restrictions, a
  powered-off device, or OEM task killing**, unless an attacker can trigger the failure or the
  app misrepresents its diagnostic state.

Third-party Android, Firebase, Google sign-in, GitHub, and dependency vulnerabilities should
normally be reported to the relevant vendor. A DrugMe-specific exploit or unsafe integration
of a dependency remains in scope.

## Security design context

### Local data

- Medication and dose records are stored in Room within credential-encrypted app storage.
- To preserve reminders between a reboot and first unlock, device-protected storage contains
  only a rolling list of anonymous reminder timestamps. It contains no medication identifiers,
  names, doses, notes, account data, or adherence history.
- Android Auto Backup and device-transfer backup are disabled.
- The app does not claim to resist a rooted device, malware with equivalent privileges, or an
  attacker who can use an already-unlocked phone.

### Encrypted sync

- Records are encrypted locally with AES-256-GCM before upload.
- Record and owner identifiers are authenticated as additional data.
- The data-encryption key is wrapped using an Argon2id-derived passphrase key and separately
  for the recovery path.
- Firestore stores ciphertext and wrapped key material. Security rules enforce ownership as a
  separate layer.

### Application updates

- Release builds request only stable releases from this repository.
- The downloaded APK must have a GitHub-provided SHA-256 digest.
- The APK package, version, digest, and signing certificate are verified before installation.
- Android still requires explicit user approval to install an update.

These controls describe the intended design; they are not a claim that the project has
received an independent security audit.
