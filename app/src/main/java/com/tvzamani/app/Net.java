package com.tvzamani.app;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Uygulamanın ağ katmanı.
 *
 * Tarayıcıda IPTV sunucuları "CORS" nedeniyle yayınları engeller. Burada sayfanın
 * yaptığı istekleri Android kendisi yapar ve cevaba izin başlıklarını ekleyerek
 * sayfaya geri verir. Böylece liste ve yayınlar uygulama içinde sorunsuz açılır.
 */
final class Net {

    private Net() { }

    static WebResourceResponse handle(Context ctx, WebResourceRequest r) {
        Uri u = r.getUrl();
        String scheme = u.getScheme();
        String host = u.getHost();
        if (host == null || scheme == null) return null;

        // 1) Uygulamanın kendi sayfası (web/ klasörü)
        if (MainActivity.HOST.equals(host)) return asset(ctx, u.getPath());

        if (!"http".equals(scheme) && !"https".equals(scheme)) return null;

        // 2) Kütüphaneler: web/lib içinde varsa internetsiz oradan ver, yoksa normal yükle
        if (host.endsWith("jsdelivr.net") || host.endsWith("unpkg.com") || host.endsWith("cdnjs.cloudflare.com")) {
            String last = u.getLastPathSegment();
            if (last != null) {
                WebResourceResponse local = assetOrNull(ctx, "lib/" + last);
                if (local != null) return local;
            }
            return null;
        }
        if (host.endsWith("peerjs.com")) return null;

        String method = r.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) return preflight();
        if (!"GET".equalsIgnoreCase(method)) return null;
        if (r.isForMainFrame()) return null;

        // 3) Resimler CORS gerektirmez; WebView kendi yüklesin (önbellek dahil)
        Map<String, String> reqHeaders = r.getRequestHeaders();
        String accept = header(reqHeaders, "Accept");
        String path = u.getPath() == null ? "" : u.getPath().toLowerCase(Locale.ROOT);
        if ((accept != null && accept.startsWith("image/"))
                || path.matches(".*\\.(png|jpe?g|gif|webp|svg|ico|bmp|css|woff2?|ttf)$")) {
            return null;
        }

        // 4) Liste, API ve yayın istekleri: yerel olarak yap, CORS izni ekle
        try {
            return fetch(u.toString(), reqHeaders, 0);
        } catch (Exception e) {
            return error(502, "Bad Gateway", "Bağlantı hatası: " + e.getMessage());
        }
    }

    private static WebResourceResponse fetch(String url, Map<String, String> reqHeaders, int depth) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        if (reqHeaders != null) {
            for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                String k = e.getKey();
                if (k == null || e.getValue() == null) continue;
                String kl = k.toLowerCase(Locale.ROOT);
                if (kl.equals("origin") || kl.equals("referer") || kl.startsWith("sec-")
                        || kl.equals("accept-encoding") || kl.equals("host")) continue;
                c.setRequestProperty(k, e.getValue());
            }
        }
        // Sıkıştırmasız iste: içerik uzunluğu doğru kalsın (mp4 sarma için önemli)
        c.setRequestProperty("Accept-Encoding", "identity");

        int code = c.getResponseCode();

        // Yönlendirmeleri elle takip et (http <-> https geçişleri dahil)
        if (code >= 300 && code < 400) {
            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (loc != null && depth < 8) {
                String next = new URL(new URL(url), loc).toString();
                return fetch(next, reqHeaders, depth + 1);
            }
            return error(502, "Bad Gateway", "Çok fazla yönlendirme");
        }
        if (code < 100 || code > 599) code = 200;

        InputStream in;
        try {
            in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        } catch (IOException e) {
            in = c.getErrorStream();
        }
        if (in == null) in = new ByteArrayInputStream(new byte[0]);

        String mime = "application/octet-stream";
        String enc = null;
        String ct = c.getContentType();
        if (ct != null) {
            String[] parts = ct.split(";");
            if (!parts[0].trim().isEmpty()) mime = parts[0].trim();
            for (String p : parts) {
                p = p.trim();
                if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    enc = p.substring(8).replace("\"", "").trim();
                }
            }
        }

        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            String k = e.getKey();
            List<String> v = e.getValue();
            if (k == null || v == null || v.isEmpty()) continue;
            String kl = k.toLowerCase(Locale.ROOT);
            if (kl.startsWith("access-control-") || kl.equals("content-encoding") || kl.equals("transfer-encoding")
                    || kl.equals("connection") || kl.equals("set-cookie") || kl.equals("content-type")) continue;
            out.put(k, v.get(v.size() - 1));
        }
        cors(out);

        String reason = c.getResponseMessage();
        if (reason == null || reason.trim().isEmpty()) reason = code < 400 ? "OK" : "Error";
        return new WebResourceResponse(mime, enc, code, reason, out, in);
    }

    private static void cors(Map<String, String> h) {
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
        h.put("Access-Control-Allow-Headers", "*");
        h.put("Access-Control-Expose-Headers", "*");
    }

    private static WebResourceResponse preflight() {
        Map<String, String> h = new HashMap<>();
        cors(h);
        h.put("Access-Control-Max-Age", "86400");
        return new WebResourceResponse("text/plain", "utf-8", 204, "No Content", h,
                new ByteArrayInputStream(new byte[0]));
    }

    private static WebResourceResponse error(int code, String reason, String msg) {
        Map<String, String> h = new HashMap<>();
        cors(h);
        return new WebResourceResponse("text/plain", "utf-8", code, reason, h,
                new ByteArrayInputStream(msg.getBytes(StandardCharsets.UTF_8)));
    }

    private static WebResourceResponse asset(Context ctx, String path) {
        if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
        WebResourceResponse r = assetOrNull(ctx, path.substring(1));
        return r != null ? r : error(404, "Not Found", "Bulunamadı");
    }

    private static WebResourceResponse assetOrNull(Context ctx, String name) {
        try {
            InputStream in = ctx.getAssets().open(name);
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-cache");
            cors(h);
            return new WebResourceResponse(mime(name), "UTF-8", 200, "OK", h, in);
        } catch (IOException e) {
            return null;
        }
    }

    private static String mime(String n) {
        n = n.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private static String header(Map<String, String> h, String name) {
        if (h == null) return null;
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}
