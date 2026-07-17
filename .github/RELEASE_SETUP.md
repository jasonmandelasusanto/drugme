# CI and release setup

Two workflows:

| Workflow | Triggers | Produces |
|---|---|---|
| `ci.yml` | push/PR to `main`, or manually | Unit tests, lint, **debug APK** artifact |
| `release.yml` | tag `v*`, or manually | **Signed release APK**, attached to a GitHub Release |

CI runs without any secrets — it falls back to a placeholder Firebase config so the build
and tests still verify. `release.yml` refuses to run without the real ones.

---

## 1. `GOOGLE_SERVICES_JSON`

Required for a working Firebase build. Base64-encode your real file:

```bash
base64 -w0 app/google-services.json
```

Copy the output into **Settings → Secrets and variables → Actions → New repository secret**
named `GOOGLE_SERVICES_JSON`.

> `-w0` matters — without it `base64` wraps at 76 columns and the workflow decodes garbage.

This file is gitignored on purpose. It isn't a credential in the strict sense (it ships
inside every APK and anyone can extract it), but publishing it in a public repo invites
strangers to point traffic at your Firebase quota. The real protections are your SHA-1
registration and the Firestore rules.

---

## 2. Release signing keystore

Generate one **once**, and do not lose it. Android identifies an app by its signing key:
if you lose this, you cannot ship an update that upgrades an existing install — users
would have to uninstall and lose their local data.

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias drugme
```

It prompts for a keystore password, then details, then a key password (pressing Enter
reuses the keystore password — simplest, and fine here).

Then set four secrets:

```bash
base64 -w0 release.jks     # -> KEYSTORE_BASE64
```

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | output of the command above |
| `KEYSTORE_PASSWORD` | the keystore password you chose |
| `KEY_ALIAS` | `drugme` |
| `KEY_PASSWORD` | the key password (same as keystore password unless you set another) |

**Back up `release.jks` somewhere safe and offline.** A GitHub secret is write-only — you
cannot read it back out. If your local copy is lost and the secret is all that remains, you
have effectively lost the key.

Keep `release.jks` out of the repo. `.gitignore` already covers `*.jks`, `*.keystore`, and
`keystore.properties`.

---

## 3. Register the release SHA-1 with Firebase

The debug and release keys are **different**, and each needs its own fingerprint
registered — otherwise Google sign-in works throughout development and breaks the moment
you install the release APK. This is the single most common late-stage surprise in this
setup.

```bash
keytool -list -v -keystore release.jks -alias drugme | grep SHA1
```

Firebase console → Project settings → the `com.drugme.app` app → **Add fingerprint** →
paste. Then re-download `google-services.json` and update the `GOOGLE_SERVICES_JSON`
secret, since the file now contains an extra OAuth client.

The debug key's fingerprint (already registered, for `com.drugme.app.debug`):

```
95:21:02:2C:58:7B:2D:3D:64:95:5F:86:AC:12:90:4D:C5:BF:AD:33
```

Debug builds install as `com.drugme.app.debug` so they can sit alongside a release build
on the same phone.

---

## 4. Cutting a release

```bash
git tag v0.1.0
git push origin v0.1.0
```

The workflow tests, builds, verifies the APK is genuinely signed (`assembleRelease`
succeeds even when it isn't — it just emits `app-release-unsigned.apk`, which no phone
will install), and attaches it to a GitHub Release.

Or: **Actions → Release APK → Run workflow**, and enter a version.

---

## Getting the APK onto your phone

1. Actions → the run → **Artifacts** → download the `.apk` (or grab it from the Release).
2. Transfer it to the phone, open it, and allow installing from unknown sources.
3. Grant notifications, and allow it to skip battery optimisation.
4. **On Xiaomi / Huawei / Oppo / vivo / Samsung: do the extra vendor step the app shows.**
   Those systems terminate background apps no matter how correctly the alarm was
   scheduled, and reminders will simply stop with no error.
5. Set one medication a few minutes out, lock the phone, and leave it. That overnight-style
   test is the only honest check that reminders survive Doze on real hardware — the
   emulator cannot reproduce it.
