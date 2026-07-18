# Security Policy

DrugMe is a medication reminder app. It handles medical data, so security reports are taken
seriously and reviewed personally.

## Reporting a vulnerability

**Please report privately — do not open a public issue** for anything touching
authentication, encryption, the sync layer, or user data.

Use GitHub's private vulnerability reporting: open the **Security** tab of this repository and
click **Report a vulnerability**. That opens a private advisory visible only to the
maintainer — no email address required on either side.

- Include: what you found, how to reproduce it, and the impact you think it has. A proof of
  concept helps but isn't required.

You'll get an acknowledgement as soon as it's seen. Please give a reasonable window to fix an
issue before disclosing it publicly.

## Scope

In scope:
- The Android app in this repository.
- The end-to-end encryption / vault design and the Firestore sync path.
- The Firestore security rules ([firestore.rules](firestore.rules)).

Out of scope / by design (not vulnerabilities):
- **Losing both the passphrase and the recovery code makes the synced data unrecoverable.**
  This is intentional. The data is encrypted with a key derived from the user's passphrase
  and wrapped under a recovery code; neither is ever sent to the server in a form anyone can
  reverse. No one — including the developer or Google — can decrypt the backup without one of
  those secrets. That is the guarantee, not a bug.
- The Firebase API key shipped in the app. Firebase client keys are project identifiers, not
  secrets; the backend is protected by the Firestore rules and App Check, not by hiding the
  key.

## How the data is protected (context for reviewers)

- Records are encrypted client-side with **AES-256-GCM** before they ever leave the device;
  each blob is bound to its record id and owner via AAD.
- The data-encryption key is wrapped with a key derived from the passphrase using
  **Argon2id**, with the KDF parameters stored per key so cost can be raised without locking
  out existing users.
- Firestore stores only ciphertext plus two wrapped copies of the key. Ownership is enforced
  by security rules; confidentiality is enforced by the encryption. Both layers are required.

## Supported versions

This is an actively developed, single-maintainer project. Only the latest `main` and the most
recent release receive security fixes.
