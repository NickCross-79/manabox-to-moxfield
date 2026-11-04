package com.nickcross.manaboxtomoxfield;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Hosts the web app in a WebView.
 *
 * - Serves the web app from https://appassets.androidplatform.net/: the newest update
 *   downloaded by {@link Updater}, or the copy bundled in the APK.
 * - Checks for an update on launch (and when brought back to the foreground) and applies
 *   it straight away; an open file is carried across the reload by the page.
 * - Receives CSV files from the share menu / "open with" and hands them to the page.
 * - Exposes window.Android so the page can save and share the converted CSV,
 *   which a WebView can't do by itself.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String APP_URL = "https://" + HOST + "/index.html";
    private static final int FILE_CHOOSER_REQUEST = 1;
    private static final int LAUNCH_WAIT_MS = 3000; // max wait for an update before showing the app

    private WebView webView;
    private Updater updater;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean loaded = false;
    private boolean checking = false;
    private boolean firstResume = true;
    private volatile String pendingShare = null; // JSON {name, text}
    private ValueCallback<Uri[]> fileChooserCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#1c192e"));

        // Android 15 draws apps edge to edge, under the status and navigation bars.
        // Pad the container so the page (and its buttons) stay clear of them.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#1c192e"));
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets b = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(b.left, b.top, b.right, b.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);

        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.setWebViewClient(new Client());
        webView.setWebChromeClient(new Chrome());

        updater = new Updater(new File(getFilesDir(), "web"), BuildConfig.MANIFEST_URL,
                BuildConfig.UPDATE_URL_PREFIX, BuildConfig.WEB_BUILD, BuildConfig.SHELL_API, 8000);

        handleIntent(getIntent());
        // Update first, then show the app; don't make the user wait more than a moment.
        main.postDelayed(this::showApp, LAUNCH_WAIT_MS);
        checkForUpdate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (firstResume) firstResume = false;
        else checkForUpdate();
    }

    private void showApp() {
        if (loaded) return;
        loaded = true;
        webView.loadUrl(APP_URL);
    }

    private void checkForUpdate() {
        if (checking) return;
        checking = true;
        new Thread(() -> {
            int installed = 0;
            try {
                installed = updater.checkAndInstall();
            } catch (Exception ignored) {
                // Offline or no release yet: keep the current version.
            }
            final boolean updated = installed > 0;
            main.post(() -> {
                checking = false;
                if (!loaded) showApp();
                else if (updated) applyUpdate();
            });
        }).start();
    }

    /** Reload into the new version, letting the page save any open file first. */
    private void applyUpdate() {
        webView.evaluateJavascript("window.beforeAppUpdate && window.beforeAppUpdate()",
                v -> webView.loadUrl(APP_URL + "?b=" + updater.activeBuild()));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    // ---- Incoming files -------------------------------------------------

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri uri = null;
        String text = null;
        if (Intent.ACTION_SEND.equals(action)) {
            uri = getStreamExtra(intent);
            if (uri == null) text = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        } else {
            return;
        }
        final Uri fUri = uri;
        final String fText = text;
        new Thread(() -> {
            try {
                String name = "manabox.csv";
                String content = fText;
                if (fUri != null) {
                    String n = displayName(fUri);
                    if (n != null) name = n;
                    content = readUri(fUri);
                }
                if (content == null) return;
                JSONObject o = new JSONObject();
                o.put("name", name);
                o.put("text", content);
                pendingShare = o.toString();
                runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onAndroidShare && window.onAndroidShare()", null));
            } catch (Exception e) {
                toast("Couldn't open the shared file: " + e.getMessage());
            }
        }).start();
    }

    @SuppressWarnings("deprecation")
    private Uri getStreamExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        return intent.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    private String displayName(Uri uri) {
        if (!"content".equals(uri.getScheme())) return uri.getLastPathSegment();
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return null;
    }

    private String readUri(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    // ---- Saving / sharing -----------------------------------------------

    /** Writes the CSV to the public Downloads folder and returns its content URI. */
    private Uri saveToDownloads(String name, String text) throws Exception {
        ContentResolver r = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception("Downloads folder unavailable");
        try (OutputStream out = r.openOutputStream(uri)) {
            if (out == null) throw new Exception("Couldn't write file");
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return uri;
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    /** Methods callable from JavaScript as window.Android.*. */
    private class Bridge {
        @JavascriptInterface
        public String appVersion() {
            return "b" + updater.activeBuild() + " · app " + BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public String takeSharedCsv() {
            String s = pendingShare;
            pendingShare = null;
            return s;
        }

        @JavascriptInterface
        public boolean saveCsv(String name, String text) {
            try {
                saveToDownloads(name, text);
                toast("Saved to Downloads: " + name);
                return true;
            } catch (Exception e) {
                toast("Couldn't save: " + e.getMessage());
                return false;
            }
        }

        @JavascriptInterface
        public void shareCsv(String name, String text) {
            try {
                Uri uri = saveToDownloads(name, text);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/csv");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_TITLE, name);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(send, name)));
            } catch (Exception e) {
                toast("Couldn't share: " + e.getMessage());
            }
        }
    }

    // ---- WebView plumbing -----------------------------------------------

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (HOST.equals(url.getHost())) return false;
            // Links like Moxfield open in the browser.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, url));
            } catch (Exception ignored) {
            }
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (!HOST.equals(url.getHost())) return null;
            String path = url.getPath() == null ? "" : url.getPath().replaceFirst("^/+", "");
            if (path.isEmpty()) path = "index.html";
            if (path.contains("..")) return notFound();
            try {
                InputStream in;
                File dir = updater.activeDir();
                if (dir != null) {
                    File f = new File(dir, path);
                    if (!f.isFile()) return notFound();
                    in = new FileInputStream(f);
                } else {
                    in = getAssets().open("web/" + path);
                }
                WebResourceResponse r = new WebResourceResponse(mimeType(path), "utf-8", in);
                java.util.Map<String, String> h = new java.util.HashMap<>();
                h.put("Cache-Control", "no-store");
                r.setResponseHeaders(h);
                return r;
            } catch (Exception e) {
                return notFound();
            }
        }

        private WebResourceResponse notFound() {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null,
                    new java.io.ByteArrayInputStream(new byte[0]));
        }

        private String mimeType(String path) {
            if (path.endsWith(".html")) return "text/html";
            if (path.endsWith(".js")) return "text/javascript";
            if (path.endsWith(".css")) return "text/css";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".svg")) return "image/svg+xml";
            if (path.endsWith(".json") || path.endsWith(".webmanifest")) return "application/json";
            return "application/octet-stream";
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = callback;
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "text/csv", "text/comma-separated-values", "text/plain",
                    "application/csv", "application/vnd.ms-excel", "application/octet-stream"});
            try {
                startActivityForResult(pick, FILE_CHOOSER_REQUEST);
            } catch (Exception e) {
                fileChooserCallback = null;
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || fileChooserCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            result = new Uri[]{data.getData()};
        }
        fileChooserCallback.onReceiveValue(result);
        fileChooserCallback = null;
    }
}
