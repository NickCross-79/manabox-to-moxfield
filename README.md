# ManaBox → Moxfield

An Android app that shows up in ManaBox's **export / share menu**.
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

1. Download the newest APK from this repo's **Releases** (the `apk-b…` prerelease, "Android app build N").
2. Open it and allow "Install unknown apps" for your browser or file manager when asked. Android may warn that the
   app is from an unknown developer, because it isn't from the Play Store.
3. Done. **Mana→Mox** now appears in Android's share menu (and "Open with" for CSV files).

You only reinstall (uninstall, then install the new APK) when the Android part itself changes. Changes to the web app arrive by themselves (see
[Updates](#updates)).

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

Updates are published as GitHub Releases in this repo, the same pattern as Keystone. There's no server, no
GitHub Pages and nothing to set up for web updates.

```
push to main ──► "Release web update" workflow
                  tests, zip the web app ─► web-b{N}.zip   (N = commit count)
                  write manifest.json (build, url, size, sha256, min_shell)
                  draft release web-b{N}: upload zip, download it again, check it
                  add manifest, publish as "latest", fetch the public URLs and check them

phone opens the app (or switches back to it)
  GET releases/latest/download/manifest.json
  newer build, and min_shell <= this APK's shell-api?  download the zip
  check size + SHA-256 + URL prefix, unzip to files/web/b{N}/, switch to it, reload
  any problem or offline: keep the current version (falls back to the copy inside the APK)
```

- On launch the app waits up to 3 seconds for the check so you open straight into the new version. If a newer
  build arrives while the app is open, it reloads into it, and an open file is restored after the reload.
- The previous build is kept on the phone; older ones are deleted. The 10 newest web releases are kept on GitHub.
- The version (`b{N} · app …`) is shown at the bottom of the app.
- **`shell-api.txt`** is the version of the bridge between the page and the Android code (`window.Android`). Bump it
  when the web app starts needing something new from the Android side: older APKs then ignore those web updates
  until you install the new APK, instead of loading a page they can't run.

### Android app releases

The **Build Android app** workflow runs when `android/` or `shell-api.txt` changes on `main` (or by hand). It runs
the updater tests, builds the APK and attaches it to an `apk-b{N}` prerelease. Prereleases never become "latest",
so they don't interfere with the web manifest.

No setup is needed. Each APK is signed with the build machine's own key, and Android only installs an update
signed with the same key, so **uninstall the old app before installing a new APK**. Nothing is lost: the app
doesn't store your collection. New APKs are only needed when `android/` or `shell-api.txt` changes. Everything else
arrives as a web update.

## Development

```bash
npm test                                     # converter unit tests
(cd android && ./gradlew testReleaseUnitTest) # updater tests
tools/build-apk.sh                           # build the APK locally
python3 -m http.server 8000                  # try the web app at http://localhost:8000
```

| File | Purpose |
| --- | --- |
| `converter.js` | ManaBox → Moxfield conversion and binder filtering (pure JS, no dependencies) |
| `app.js`, `index.html`, `style.css` | The app UI |
| `android/.../MainActivity.java` | Share-menu receiver, serves the web app locally, save/share bridge (`window.Android`) |
| `android/.../Updater.java` | Checks the release manifest, downloads, verifies and installs web updates |
| `tools/release.py` | Packs the web app and writes/checks the manifest (used by the workflow) |
| `shell-api.txt` | Bridge version; web updates need an APK with at least this |
| `sw.js`, `manifest.webmanifest` | Only for running it as an installable website (not used by the Android app) |
