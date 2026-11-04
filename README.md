# ManaBox → Moxfield

An Android app (installable web app) that shows up in ManaBox's **export / share menu**.
Send it your ManaBox collection CSV and it:

1. **Keeps only cards in binders**: cards in decks and lists (wishlists) are removed.
2. Merges the same printing across binders into one row.
3. Converts it to **Moxfield's collection import format** (condition, language, foil, set code, collector number).

You then save the CSV and upload it with Moxfield's collection import.

> Why not sync to Moxfield automatically? Moxfield has no public API, and automating its website
> is against its Terms of Service. If Moxfield ever grants API access
> (support@moxfield.com), the upload step could be automated.

Everything runs on your phone. Your collection is never uploaded anywhere.

## Install on your phone

1. Open the app's GitHub Pages URL in **Chrome on Android**:
   `https://nickcross-79.github.io/manabox-to-moxfield/`
2. Chrome menu (⋮) → **Install app** (or **Add to Home screen**).
3. Done. "Mana→Mox" now appears in Android's share menu.

## Use it

1. ManaBox → Collection → **Export** → **CSV**. Export the **whole collection**, since the binder filter needs the
   "Binder Type" column that the whole-collection export includes.
2. In the share sheet, tap **Mana→Mox**.
3. Check the Kept / Removed summary, then tap **Save CSV** (or **Share…** to send it to Drive, email and so on).
4. In Moxfield: **Collection → Import**, then pick the saved file.

If Mana→Mox doesn't appear in the share sheet, save the CSV from ManaBox to your phone and use
**Choose CSV file** in the app instead.

Options: **Tag cards with their binder name** adds each ManaBox binder name as a Moxfield tag.

## Updates

Every push to `main` runs the tests and publishes the app to GitHub Pages. Each deploy stamps the commit
SHA into the service worker. The installed app checks for a new version whenever you open it or switch back to it,
then reloads itself (or shows an **Update** button if a conversion is on screen). The version is shown at the
bottom of the app.

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
