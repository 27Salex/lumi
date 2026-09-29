# Connecting Lumi to Google Tasks (free, ~10 min)

The Google Tasks API is free (courtesy limit: 50,000 requests/day). You only need to tell Google that the Lumi app
may ask your account for permission. **No credit card needed.**

> Google Calendar does **not** need any of this: Lumi uses the calendars already on the phone
> (Settings → Google Calendar → Connect).

## Steps

1. Go to <https://console.cloud.google.com> with your Google account and create a project (e.g. "Lumi").
2. **APIs & Services → Library** → search for **Google Tasks API** → **Enable**.
3. **APIs & Services → OAuth consent screen**
   - User type: **External**.
   - App name: "Lumi"; support and contact email: yours.
   - Under **Test users**, add your own Google account.
   - You don't need to publish or verify the app (personal use in "testing" mode).
4. **APIs & Services → Credentials → Create credentials → OAuth client ID**
   - Application type: **Android**.
   - Package name: `io.github.salex27.lumi`
   - SHA-1 fingerprint: copy it from the app (**Settings → Google Tasks setup**, "Copy" button). Each signing
     certificate has its own SHA-1: a debug APK built on your PC has one; if you sign a release APK, add its SHA-1 too.
5. In the app: **Settings → Google Tasks → Connect** → choose your account → Allow.

Lumi creates a **"Lumi"** list in Google Tasks and syncs both ways (when the app opens and a few seconds after each
change).

## Known limitations

- Google Tasks only stores the due **date**, not the time. Lumi keeps the time on the phone while the day doesn't change.
- **Cancelled** tasks are deleted from Google Tasks (that status doesn't exist there).
- In "testing" mode Google may ask for the permission again now and then: Settings will show "Permission needs to be
  granted again" with a button to do it.
- An "OAuth client isn't configured" error means the package name or the SHA-1 from step 4 don't match.
