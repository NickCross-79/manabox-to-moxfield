# ManaBox → Moxfield

An Android app (installable web app) that shows up in ManaBox's **export / share menu**.
Send it your ManaBox collection CSV and it:

1. **Keeps only cards in binders**: cards in decks and lists (wishlists) are removed.
2. Leaves everything else exactly as ManaBox exported it, including the **Scryfall ID** column that
   Moxfield's ManaBox importer uses to match the exact printing.

You then save the CSV and upload it with Moxfield's collection import (format: ManaBox).

> Why not sync to Moxfield automatically? Moxfield has no public API, and automating its website
> is against its Terms of Service. If Moxfield ever grants API access
> (support@moxfield.com), the upload step could be automated.

Everything runs on your phone. Your collection is never uploaded anywhere.

## Install on your phone

1. Open the app's GitHub Pages URL in **Chrome on Android**:
   `https://nickcross-79.github.io/manabox-to-moxfield/`
2. Chrome menu (⋮) → **Install app** (or **Add to Home screen**).
3. Done. "Mana→Mox" now appears in Android's share menu.

### Or install the APK

`android/` contains a small Android app (`Mana→Mox`) that does the same thing as a regular installed app:

- Appears in the share menu (and "Open with") for CSV files.
- Loads the published web app, so updates pushed to `main` reach it automatically.
- Falls back to a copy of the web app built into the APK when offline or if the site isn't reachable.
- **Save CSV** writes to your Downloads folder; **Share…** sends it to any app.

To install: download the APK, open it, and allow "Install unknown apps" for your browser or file manager when
asked. Android may warn that the app is from an unknown developer, because it isn't from the Play Store.

Build it yourself with `cd android && ./gradlew assembleRelease` (needs the Android SDK; set `sdk.dir` in
`android/local.properties`). The output is `android/app/build/outputs/apk/release/app-release.apk`. GitHub
Actions also builds it whenever `android/` changes (the **Build Android APK** workflow, under Artifacts).

The APK is signed with the build machine's debug key. An APK built on a different machine has a different key, so
Android won't install it over the old one: uninstall the old app first. This only matters for changes to the
Android shell itself. Web app updates don't need a new APK.

## Use it

1. ManaBox → Collection → **Export** → **CSV**. Export the **whole collection**, since the binder filter needs the
   "Binder Type" column that the whole-collection export includes.
2. In the share sheet, tap **Mana→Mox**.
3. Check the Kept / Removed summary, then tap **Save CSV** (or **Share…** to send it to Drive, email and so on).
4. In Moxfield: **Collection → Import**, choose **ManaBox** as the format if asked, and pick the saved file.
   If Moxfield says "No Scryfall ID found", export again from ManaBox with the Scryfall ID column included.

If Mana→Mox doesn't appear in the share sheet, save the CSV from ManaBox to your phone and use
**Choose CSV file** in the app instead.

## Updates

Every push to `main` runs the tests and publishes the app to GitHub Pages. Each deploy stamps the commit
SHA into the service worker. The installed app checks for a new version whenever you open it or switch back to it,
and reloads itself into it automatically. If a file is open, it's restored after the reload. The version is shown
at the bottom of the app.

## Hosting (one-time setup)

The workflow in `.github/workflows/pages.yml` publishes pushes to `main` (and to the default branch, until `main`
exists). GitHub doesn't let a workflow switch Pages on, so do it once by hand:
repo **Settings → Pages → Build and deployment → Source: GitHub Actions**, then re-run the workflow.

## Development

```bash
npm test                      # converter unit tests
python3 -m http.server 8000   # then open http://localhost:8000
```

| File | Purpose |
| --- | --- |
| `converter.js` | ManaBox → Moxfield conversion and binder filtering (pure JS, no dependencies) |
| `app.js`, `index.html`, `style.css` | The app UI |
| `sw.js` | Service worker: offline support and receiving files from the share menu |
| `manifest.webmanifest` | Install metadata and the Android `share_target` registration |
| `android/` | Native Android app shell (share-menu receiver, save/share bridge, offline fallback) |
