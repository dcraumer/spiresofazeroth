package com.thewowdb.spiresofazeroth;

import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves the Blizzard art, models and sounds that are not bundled in the APK the same way the desktop shell does:
 * any request for /assets/, /static/, /spiresofazeroth/assets/ or /media/sounds/ that is not in the APK is fetched
 * natively from thewowdb.com, cached on disk, and returned to the WebView as a same-origin response.
 * Doing this natively (instead of in a service worker) avoids CORS and covers the /spiresofazeroth/assets/ paths
 * (card icons, models, fx textures, sounds) that the old service worker never proxied.
 */
public class MainActivity extends BridgeActivity {

    private static final String REMOTE = "https://thewowdb.com";
    private static final String[] PROXY = { "/assets/", "/static/", "/spiresofazeroth/assets/", "/media/sounds/" };
    private final Map<String, Boolean> bundled = new ConcurrentHashMap<>();
    private File cacheRoot;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Phones: the game has a purpose-built portrait layout; in landscape it shrinks the whole 1920x1080 desktop layout to ~35%
        // (card and item text around 5-8px). Tablets (600dp+) keep free rotation, where landscape is readable.
        if (getResources().getConfiguration().smallestScreenWidthDp < 600) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }
        super.onCreate(savedInstanceState);
        cacheRoot = new File(getCacheDir(), "remote-assets");
        bridge.setWebViewClient(
            new BridgeWebViewClient(bridge) {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    WebResourceResponse r = proxy(request);
                    return r != null ? r : super.shouldInterceptRequest(view, request);
                }
            }
        );
        hideBars();
    }

    private WebResourceResponse proxy(WebResourceRequest req) {
        if (!"GET".equalsIgnoreCase(req.getMethod())) return null;
        Uri u = req.getUrl();
        if (u == null || u.getHost() == null || !u.getHost().equals(bridge.getHost())) return null;
        String path = u.getPath();
        if (path == null || path.contains("..") || !proxyable(path) || isBundled(path)) return null;

        File cached = new File(cacheRoot, path.substring(1));
        if (cached.isFile() && cached.length() > 0) {
            try {
                return ok(path, null, new FileInputStream(cached));
            } catch (IOException ignored) {}
        }

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(REMOTE + path).openConnection();
            c.setRequestProperty("User-Agent", "spires-android");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            if (c.getResponseCode() != 200) return notFound();
            String remoteType = c.getContentType();
            File parent = cached.getParentFile();
            if (parent != null) parent.mkdirs();
            File tmp = new File(cached.getPath() + ".part" + Thread.currentThread().getId());
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (!tmp.renameTo(cached)) {
                // another thread won the race; serve whichever file exists
                if (!cached.isFile()) return ok(path, remoteType, new FileInputStream(tmp));
                tmp.delete();
            }
            return ok(path, remoteType, new FileInputStream(cached));
        } catch (IOException e) {
            return notFound();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean proxyable(String p) {
        for (String prefix : PROXY) if (p.startsWith(prefix)) return true;
        return false;
    }

    /** True when the file ships inside the APK (www/ is copied to assets/public/ by `cap sync`). */
    private boolean isBundled(String path) {
        Boolean known = bundled.get(path);
        if (known != null) return known;
        boolean found;
        try (InputStream in = getAssets().open("public" + path)) {
            found = true;
        } catch (IOException e) {
            found = false;
        }
        bundled.put(path, found);
        return found;
    }

    private static WebResourceResponse ok(String path, String remoteType, InputStream body) {
        String mime = mimeFor(path);
        if (mime == null && remoteType != null) mime = remoteType.split(";")[0].trim();
        if (mime == null) mime = "application/octet-stream";
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "max-age=31536000");
        String enc = mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("javascript") ? "utf-8" : null;
        return new WebResourceResponse(mime, enc, 200, "OK", h, body);
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeFor(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
        switch (ext) {
            case "html": return "text/html";
            case "js": case "mjs": return "text/javascript";
            case "json": return "application/json";
            case "css": return "text/css";
            case "svg": return "image/svg+xml";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "glb": return "model/gltf-binary";
            case "gltf": return "model/gltf+json";
            case "mp3": return "audio/mpeg";
            case "ogg": return "audio/ogg";
            case "wav": return "audio/wav";
            case "ttf": return "font/ttf";
            case "woff2": return "font/woff2";
            case "woff": return "font/woff";
            default: return null;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    private void hideBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsetsCompat.Type.systemBars());
    }
}
