package com.tvzamani.app;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** M3U'daki #EXTVLCOPT / #EXTHTTP başlıklarını (User-Agent, Referer...) oynatıcılara taşır. */
final class Hdr {
    private Hdr() { }

    /** Öğenin "h" nesnesini okur; anahtarlar User-Agent / Referer / Origin / Cookie biçiminde normalleştirilir. */
    static Map<String, String> of(JSONObject it) {
        Map<String, String> m = new LinkedHashMap<>();
        JSONObject h = it == null ? null : it.optJSONObject("h");
        if (h == null) return m;
        Iterator<String> k = h.keys();
        while (k.hasNext()) {
            String key = k.next();
            String v = h.optString(key, "");
            if (key == null || key.isEmpty() || v.isEmpty()) continue;
            m.put(norm(key), v);
        }
        return m;
    }

    private static String norm(String k) {
        String l = k.toLowerCase();
        if (l.equals("user-agent") || l.equals("http-user-agent")) return "User-Agent";
        if (l.equals("referer") || l.equals("referrer") || l.equals("http-referrer") || l.equals("http-referer")) return "Referer";
        if (l.equals("origin") || l.equals("http-origin")) return "Origin";
        if (l.equals("cookie") || l.equals("http-cookie")) return "Cookie";
        return k;
    }
}
