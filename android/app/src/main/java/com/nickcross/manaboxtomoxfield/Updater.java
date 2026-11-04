package com.nickcross.manaboxtomoxfield;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads web app updates published as GitHub Releases (see .github/workflows/release.yml).
 *
 * The manifest lives at a fixed URL (releases/latest/download/manifest.json) and names a
 * versioned zip of the web app plus its SHA-256. A newer, compatible build is downloaded,
 * checked, unpacked into files/web/b{N}/ and made active by rewriting files/web/active.json.
 * Anything that fails leaves the current version in place. If nothing has been downloaded
 * (or the active copy is missing), the copy bundled in the APK is used.
 *
 * No Android dependencies besides org.json, so it runs in plain JVM unit tests.
 */
public class Updater {
    static final int MAX_MANIFEST_BYTES = 64 * 1024;
    static final long MAX_ZIP_BYTES = 5L * 1024 * 1024;
    static final long MAX_UNZIPPED_BYTES = 20L * 1024 * 1024;

    private final File root;
    private final String manifestUrl;
    private final String allowedPrefix;
    private final int bundledBuild;
    private final int shellApi;
    private final int timeoutMs;

    public Updater(File root, String manifestUrl, String allowedPrefix,
                   int bundledBuild, int shellApi, int timeoutMs) {
        this.root = root;
        this.manifestUrl = manifestUrl;
        this.allowedPrefix = allowedPrefix;
        this.bundledBuild = bundledBuild;
        this.shellApi = shellApi;
        this.timeoutMs = timeoutMs;
    }

    /** Folder of the downloaded web app in use, or null to use the copy bundled in the APK. */
    public synchronized File activeDir() {
        JSONObject a = readActive();
        if (a == null) return null;
        File dir = new File(root, a.optString("dir", ""));
        if (a.optInt("build", 0) <= bundledBuild || !new File(dir, "index.html").isFile()) return null;
        return dir;
    }

    public synchronized int activeBuild() {
        JSONObject a = readActive();
        return activeDir() != null && a != null ? a.optInt("build", bundledBuild) : bundledBuild;
    }

    /**
     * Checks the manifest and installs a newer build if there is one.
     * Returns the newly active build number, or 0 if nothing changed.
     * Throws only for unexpected I/O problems; callers treat any exception as "no update".
     */
    public synchronized int checkAndInstall() throws Exception {
        JSONObject m = new JSONObject(new String(fetch(manifestUrl, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8));
        int build = m.getInt("build");
        if (m.optInt("format", 0) != 1) throw new IOException("unknown manifest format");
        if (build <= activeBuild()) return 0;
        if (m.optInt("min_shell", 1) > shellApi) return 0; // needs a newer APK
        String url = m.getString("url");
        String sha = m.getString("sha256").toLowerCase();
        long size = m.getLong("size");
        if (!url.startsWith(allowedPrefix)) throw new IOException("update URL not allowed: " + url);
        if (!sha.matches("[0-9a-f]{64}")) throw new IOException("bad sha256");
        if (size <= 0 || size > MAX_ZIP_BYTES) throw new IOException("bad size");

        byte[] zip = fetch(url, MAX_ZIP_BYTES);
        if (zip.length != size) throw new IOException("size mismatch");
        if (!sha.equals(sha256(zip))) throw new IOException("sha256 mismatch");

        root.mkdirs();
        String dirName = "b" + build;
        File tmp = new File(root, dirName + ".tmp");
        deleteTree(tmp);
        unzip(zip, tmp);
        if (!new File(tmp, "index.html").isFile()) {
            deleteTree(tmp);
            throw new IOException("update has no index.html");
        }
        File dir = new File(root, dirName);
        deleteTree(dir);
        if (!tmp.renameTo(dir)) throw new IOException("couldn't install update");

        File previous = activeDir();
        JSONObject active = new JSONObject();
        active.put("build", build);
        active.put("dir", dirName);
        active.put("sha256", sha);
        writeAtomic(new File(root, "active.json"), active.toString().getBytes(StandardCharsets.UTF_8));
        prune(dir, previous);
        return build;
    }

    // ---- helpers ----

    private JSONObject readActive() {
        File f = new File(root, "active.json");
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return new JSONObject(new String(readAll(in, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] fetch(String url, long limit) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(true); // GitHub release URLs redirect to a CDN
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("User-Agent", "ManaBoxToMoxfield-Updater");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for " + url);
            try (InputStream in = c.getInputStream()) {
                return readAll(in, limit);
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > limit) throw new IOException("too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static String sha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void unzip(byte[] zip, File dest) throws IOException {
        String base = dest.getCanonicalPath() + File.separator;
        long total = 0;
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = z.getNextEntry()) != null) {
                File f = new File(dest, e.getName());
                if (!f.getCanonicalPath().startsWith(base)) throw new IOException("bad zip entry " + e.getName());
                if (e.isDirectory()) {
                    f.mkdirs();
                    continue;
                }
                f.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(f)) {
                    int n;
                    while ((n = z.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_UNZIPPED_BYTES) throw new IOException("update too large");
                        out.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private static void writeAtomic(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("couldn't write " + f);
    }

    /** Keeps the new build and the one before it; removes everything else. */
    private void prune(File keep, File previous) {
        File[] all = root.listFiles();
        if (all == null) return;
        for (File f : all) {
            if (!f.isDirectory() || f.equals(keep) || f.equals(previous)) continue;
            deleteTree(f);
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
