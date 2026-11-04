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
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Hosts the web app in a WebView.
 *
 * - Loads the published site so updates pushed to main reach the app automatically.
 * - Falls back to the copy bundled in the APK if the site can't be reached (offline,
 *   or before GitHub Pages is set up).
 * - Receives CSV files from the share menu / "open with" and hands them to the page.
 * - Exposes window.Android so the page can save and share the converted CSV,
 *   which a WebView can't do by itself.
 */
public class MainActivity extends Activity {
    private static final String FALLBACK_URL = "file:///android_asset/web/index.html";
    private static final int FILE_CHOOSER_REQUEST = 1;

    private WebView webView;
    private boolean usingFallback = false;
    private volatile String pendingShare = null; // JSON {name, text}
    private ValueCallback<Uri[]> fileChooserCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#1c192e"));
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);

        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.setWebViewClient(new Client());
        webView.setWebChromeClient(new Chrome());

        handleIntent(getIntent());
        webView.loadUrl(BuildConfig.WEB_URL);
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
            Uri home = Uri.parse(BuildConfig.WEB_URL);
            boolean ours = "file".equals(url.getScheme())
                    || (home.getHost() != null && home.getHost().equals(url.getHost())
                        && url.getPath() != null && url.getPath().startsWith(home.getPath()));
            if (ours) return false;
            // Links like Moxfield open in the browser.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, url));
            } catch (Exception ignored) {
            }
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) useFallback();
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() >= 400) useFallback();
        }

        private void useFallback() {
            if (usingFallback) return;
            usingFallback = true;
            webView.loadUrl(FALLBACK_URL);
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
