package com.tvzamani.app;

import org.json.JSONArray;

/** HTML'den gelen oynatma isteği (büyük listeler Intent sınırına takılmasın diye bellekte tutulur). */
final class Pending {
    static JSONArray items;
    static int index;
    static long start;
    static boolean autoNext = true;
    static boolean softwareDecoder;
    static volatile String ua = "TVZamani/1.0 (Android TV)";

    private Pending() { }

    static final String[] ENGINE_CODES = {"exo", "vlc", "system", "web"};
    static final String[] ENGINE_NAMES = {"ExoPlayer", "VLC", "Sistem oynatıcısı", "HLS oynatıcı (web)"};
}
