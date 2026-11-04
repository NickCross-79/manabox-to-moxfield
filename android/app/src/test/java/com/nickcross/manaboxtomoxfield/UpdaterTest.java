package com.nickcross.manaboxtomoxfield;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Runs the real Updater against a local HTTP server that mimics GitHub Releases. */
public class UpdaterTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private ServerSocket server;
    private final Map<String, byte[]> files = new HashMap<>();
    private final Map<String, String> redirects = new HashMap<>();
    private String base;

    /** Minimal HTTP/1.0 server: 302 for redirects, 200 for files, 404 otherwise. */
    @Before
    public void start() throws Exception {
        server = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        base = "http://127.0.0.1:" + server.getLocalPort();
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket c = server.accept()) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                    String line = r.readLine();
                    if (line == null) continue;
                    String path = line.split(" ")[1];
                    String h;
                    while ((h = r.readLine()) != null && !h.isEmpty()) { /* skip headers */ }
                    OutputStream o = c.getOutputStream();
                    byte[] body = new byte[0];
                    String head;
                    if (redirects.containsKey(path)) {
                        head = "HTTP/1.0 302 Found\r\nLocation: " + base + redirects.get(path) + "\r\n";
                    } else if (files.containsKey(path)) {
                        body = files.get(path);
                        head = "HTTP/1.0 200 OK\r\n";
                    } else {
                        head = "HTTP/1.0 404 Not Found\r\n";
                    }
                    o.write((head + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    o.write(body);
                    o.flush();
                } catch (Exception ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @After
    public void stop() throws Exception { server.close(); }

    private static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** Publishes build N the way the release workflow does: latest -> versioned manifest -> zip. */
    private void publish(int build, byte[] zip, String shaOverride, int minShell) throws Exception {
        String zipPath = "/releases/download/web-b" + build + "/web-b" + build + ".zip";
        files.put(zipPath, zip);
        String sha = shaOverride != null ? shaOverride : Updater.sha256(zip);
        String manifest = "{\"format\":1,\"build\":" + build + ",\"url\":\"" + base + zipPath
                + "\",\"sha256\":\"" + sha + "\",\"size\":" + zip.length + ",\"min_shell\":" + minShell + "}";
        files.put("/releases/download/web-b" + build + "/manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
        redirects.put("/releases/latest/download/manifest.json", "/releases/download/web-b" + build + "/manifest.json");
    }

    private Updater updater(File root, int bundled) {
        return new Updater(root, base + "/releases/latest/download/manifest.json",
                base + "/releases/download/web-b", bundled, 1, 5000);
    }

    private static Map<String, String> site(String marker) {
        Map<String, String> m = new HashMap<>();
        m.put("index.html", "<html>" + marker + "</html>");
        m.put("icons/icon.png", "png");
        return m;
    }

    @Test
    public void noReleaseKeepsBundledCopy() {
        Updater u = updater(tmp.getRoot(), 5);
        assertThrows(Exception.class, u::checkAndInstall);
        assertNull(u.activeDir());
        assertEquals(5, u.activeBuild());
    }

    @Test
    public void installsNewerBuildAndFollowsRedirects() throws Exception {
        publish(7, zip(site("seven")), null, 1);
        Updater u = updater(tmp.getRoot(), 5);
        assertEquals(7, u.checkAndInstall());
        assertEquals(7, u.activeBuild());
        File dir = u.activeDir();
        assertEquals("<html>seven</html>", new String(Files.readAllBytes(new File(dir, "index.html").toPath())));
        assertTrue(new File(dir, "icons/icon.png").isFile());
        // Same build again: nothing to do.
        assertEquals(0, u.checkAndInstall());
    }

    @Test
    public void ignoresBuildsNotNewerThanBundled() throws Exception {
        publish(5, zip(site("five")), null, 1);
        Updater u = updater(tmp.getRoot(), 5);
        assertEquals(0, u.checkAndInstall());
        assertNull(u.activeDir());
    }

    @Test
    public void rejectsShaMismatch() throws Exception {
        publish(8, zip(site("eight")), "0".repeat(64), 1);
        Updater u = updater(tmp.getRoot(), 5);
        assertThrows(Exception.class, u::checkAndInstall);
        assertNull(u.activeDir());
    }

    @Test
    public void skipsBuildsNeedingNewerApk() throws Exception {
        publish(9, zip(site("nine")), null, 2);
        Updater u = updater(tmp.getRoot(), 5);
        assertEquals(0, u.checkAndInstall());
        assertNull(u.activeDir());
    }

    @Test
    public void rejectsZipSlipAndMissingIndex() throws Exception {
        Map<String, String> evil = new HashMap<>();
        evil.put("../escape.txt", "x");
        evil.put("index.html", "y");
        publish(10, zip(evil), null, 1);
        Updater u = updater(tmp.getRoot(), 5);
        assertThrows(Exception.class, u::checkAndInstall);
        assertFalse(new File(tmp.getRoot().getParentFile(), "escape.txt").exists());

        Map<String, String> noIndex = new HashMap<>();
        noIndex.put("app.js", "x");
        publish(11, zip(noIndex), null, 1);
        assertThrows(Exception.class, u::checkAndInstall);
        assertNull(u.activeDir());
    }

    @Test
    public void keepsOnlyCurrentAndPreviousBuild() throws Exception {
        Updater u = updater(tmp.getRoot(), 5);
        for (int b = 6; b <= 9; b++) {
            publish(b, zip(site("b" + b)), null, 1);
            assertEquals(b, u.checkAndInstall());
        }
        assertTrue(new File(tmp.getRoot(), "b9").isDirectory());
        assertTrue(new File(tmp.getRoot(), "b8").isDirectory());
        assertFalse(new File(tmp.getRoot(), "b7").exists());
        assertFalse(new File(tmp.getRoot(), "b6").exists());
    }

    @Test
    public void rejectsUrlsOutsideTheReleaseFolder() throws Exception {
        byte[] z = zip(site("x"));
        files.put("/elsewhere/x.zip", z);
        String manifest = "{\"format\":1,\"build\":12,\"url\":\"" + base + "/elsewhere/x.zip\",\"sha256\":\""
                + Updater.sha256(z) + "\",\"size\":" + z.length + "}";
        files.put("/m.json", manifest.getBytes(StandardCharsets.UTF_8));
        redirects.put("/releases/latest/download/manifest.json", "/m.json");
        Updater u = updater(tmp.getRoot(), 5);
        assertThrows(Exception.class, u::checkAndInstall);
        assertNull(u.activeDir());
    }
}
