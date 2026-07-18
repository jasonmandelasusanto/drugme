# Firebase hardening checklist

The app's backend protection is the Firestore security rules plus App Check — **not** secrecy
of the Firebase config (the API key ships in the APK and is a project identifier, not a
secret). Work through this once before relying on the backend in production.

## 1. Deploy the Firestore rules

The rules live in [`firestore.rules`](../firestore.rules) but are only enforced once deployed.

```bash
firebase deploy --only firestore:rules
```

Then verify in the Firebase console → **Firestore → Rules** that the published rules match the
file, and use the **Rules Playground** to confirm:
- an owner (`request.auth.uid == uid`) can read/write `users/{uid}/records/*`, and
- a different signed-in uid is **denied** on someone else's `users/{other}/**`.

## 2. Enable App Check (Play Integrity)

App Check ensures requests come from the genuine app, not a script replaying the public
config against your Firestore/Auth.

### Code (in this repo)

- Add the App Check dependency via the existing Firebase BOM in
  [`app/build.gradle.kts`](../app/build.gradle.kts):
  - `com.google.firebase:firebase-appcheck-playintegrity` (release)
  - `com.google.firebase:firebase-appcheck-debug` (debug builds only)
- Initialize it in `DrugMeApplication.onCreate()`:
  - Release: install the **Play Integrity** provider.
  - Debug: install the **debug** provider so local and CI builds still reach Firebase.

### Console (you do this)

1. Firebase console → **App Check** → register the Android app with the **Play Integrity**
   provider.
2. For debug builds: copy the debug token printed in Logcat on first run and add it under
   **App Check → Apps → Manage debug tokens**.
3. Keep enforcement **unenforced (monitoring)** at first. Ship/run the app and watch the
   **App Check** dashboard until you see verified requests from real traffic.
4. Only **then** turn on enforcement for **Cloud Firestore** and **Authentication**.

> Enforcing before the app is correctly registered will lock the app out of its own backend —
> always verify in monitoring mode first.

## 3. Before flipping the repo to public

- Confirm no real `google-services.json` or keystore is tracked (`git ls-files | grep -i
  google-services` should show only the placeholder).
- Confirm the git history carries a single intended identity (see the identity-rewrite step
  in the project plan).
