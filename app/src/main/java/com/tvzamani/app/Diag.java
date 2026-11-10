package com.tvzamani.app;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** Oynatma hatasında yayın adresine gerçekten ne cevap geldiğini bulup okunur bir tanı üretir. */
final class Diag {
    interface Done { void result(String text); }

    private static final String[][] UAS = {
            {"TV Zamanı", "TVZamani/1.0 (Android TV)"},
            {"TvKutu", "TvKutu/0.6 (Android TV)"},
            {"VLC", "VLC/3.0.20 LibVLC/3.0.20"},
            {"okhttp", "okhttp/4.12.0"},
            {"Chrome", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"},
            {"ExoPlayer", "ExoPlayerLib/2.19.1"},
            {"IPTV Smarters", "IPTVSmartersPro"},
            {"FFmpeg", "Lavf/60.3.100"},
    };

    private Diag() { }

    static void probe(final String url, final Map<String, String> hdr, final Done cb) {
        new Thread(() -> cb.result(run(url, hdr))).start();
    }

    /** code, content-type, host, hops, head */
    private static Object[] check(String url, Map<String, String> hdr, String ua) throws Exception {
        String cur = url;
        for (int hops = 0; hops < 10; hops++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", ua);
            if (hdr != null) for (Map.Entry<String, String> e : hdr.entrySet())
                if (!e.getKey().equals("User-Agent")) c.setRequestProperty(e.getKey(), e.getValue());
            int code = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            if (code >= 300 && code < 400 && loc != null) {
                cur = new URL(new URL(cur), loc).toString();
                c.disconnect();
                continue;
            }
            String ct = c.getContentType() == null ? "-" : c.getContentType();
            String head = "";
            if (code < 400) {
                InputStream in = c.getInputStream();
                byte[] b = new byte[256];
                int n = 0, r;
                while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
                head = new String(b, 0, n, StandardCharsets.ISO_8859_1).trim();
            }
            c.disconnect();
            return new Object[]{code, ct, new URL(cur).getHost(), hops, head};
        }
        throw new Exception("Çok fazla yönlendirme");
    }

    private static boolean ok(Object[] r) {
        int code = (Integer) r[0];
        String ct = ((String) r[1]).toLowerCase(Locale.ROOT);
        String l = ((String) r[4]).toLowerCase(Locale.ROOT);
        return code < 400 && !ct.contains("text/html") && !l.startsWith("<");
    }

    private static String run(String url, Map<String, String> hdr) {
        StringBuilder sb = new StringBuilder("Tanı: ");
        String ua0 = hdr != null && hdr.containsKey("User-Agent") ? hdr.get("User-Agent") : Pending.ua;
        try {
            Object[] r = check(url, hdr, ua0);
            int code = (Integer) r[0];
            String ct = (String) r[1];
            sb.append("HTTP ").append(code).append(" · ").append(ct).append(" · ").append(r[2]);
            if ((Integer) r[3] > 0) sb.append(" (").append(r[3]).append(" yönlendirme)");
            if (ok(r)) {
                return sb.append("\nSunucu yayını veriyor; sorun biçim/codec olabilir, başka oynatıcı dene (sarı tuş).").toString();
            }
            // Reddedildi: farklı istemci kimlikleriyle dene, hangisi açılıyorsa söyle
            StringBuilder good = new StringBuilder();
            for (String[] u : UAS) {
                if (u[1].equals(ua0)) continue;
                try {
                    Object[] x = check(url, hdr, u[1]);
                    if (ok(x)) good.append(good.length() > 0 ? ", " : "").append(u[0]);
                } catch (Exception ignored) { }
            }
            if (good.length() > 0) {
                sb.append("\nBu sunucu şu istemci kimliğiyle açılıyor: ").append(good)
                        .append("\nAyarlar > Oynatıcı > İstemci kimliği'nden seç.");
            } else if (code == 401 || code == 403) {
                sb.append("\nHiçbir istemci kimliği işe yaramadı: hesap süresi, bağlantı limiti, IP/cihaz kısıtı ya da özel oturum gerekiyor olabilir.");
            } else if (code == 404) {
                sb.append("\nİçerik sunucuda bulunamadı (link veya uzantı yanlış).");
            } else if (code >= 500 || code == 458 || code == 884) {
                sb.append("\nSunucu hata veriyor veya bağlantı limiti dolu.");
            } else {
                sb.append("\nSunucu video yerine web sayfası döndürdü; bu bir yayın linki değil.");
            }
            return sb.toString();
        } catch (java.net.UnknownHostException e) {
            return sb.append("Sunucu adı bulunamadı (internet/DNS veya yanlış adres).").toString();
        } catch (java.net.SocketTimeoutException e) {
            return sb.append("Sunucu zaman aşımına uğradı (kapalı veya engelli).").toString();
        } catch (javax.net.ssl.SSLException e) {
            return sb.append("SSL/sertifika hatası: ").append(e.getMessage()).toString();
        } catch (Exception e) {
            return sb.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).toString();
        }
    }
}
