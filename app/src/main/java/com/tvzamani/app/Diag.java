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

    private Diag() { }

    static void probe(final String url, final Map<String, String> hdr, final Done cb) {
        new Thread(() -> cb.result(run(url, hdr))).start();
    }

    private static String run(String url, Map<String, String> hdr) {
        StringBuilder sb = new StringBuilder("Tanı: ");
        try {
            String cur = url;
            int hops = 0;
            for (; hops < 10; hops++) {
                HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                String ua = hdr != null && hdr.containsKey("User-Agent") ? hdr.get("User-Agent") : "VLC/3.0.20 LibVLC/3.0.20";
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
                String host = new URL(cur).getHost();
                sb.append("HTTP ").append(code).append(" · ").append(ct).append(" · ").append(host);
                if (hops > 0) sb.append(" (").append(hops).append(" yönlendirme)");
                String l = head.toLowerCase(Locale.ROOT);
                if (code == 401 || code == 403) sb.append("\nSunucu erişimi reddetti: hesap süresi, cihaz/IP kısıtı veya User-Agent/Referer gerekiyor olabilir.");
                else if (code == 404) sb.append("\nİçerik sunucuda bulunamadı (link veya dosya uzantısı yanlış).");
                else if (code == 458 || code == 884 || code >= 500) sb.append("\nSunucu hata veriyor veya bağlantı limiti dolu.");
                else if (l.startsWith("<") || ct.toLowerCase(Locale.ROOT).contains("text/html")) sb.append("\nSunucu video yerine web sayfası döndürdü; bu bir yayın linki değil.");
                else if (code < 400) sb.append("\nSunucu yayını veriyor; sorun biçim/codec olabilir, başka oynatıcı dene (sarı tuş).");
                return sb.toString();
            }
            return sb.append("Çok fazla yönlendirme (").append(cur).append(")").toString();
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
