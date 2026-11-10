package com.tvzamani.app;

import org.json.JSONArray;

/** HTML'den gelen oynatma isteği (büyük listeler Intent sınırına takılmasın diye bellekte tutulur). */
final class Pending {
    static JSONArray items;
    static int index;
    static long start;
    static boolean autoNext = true;
    static boolean softwareDecoder;
    static String ua = "VLC/3.0.20 LibVLC/3.0.20";

    private Pending() { }

    static final String[] ENGINE_CODES = {"exo", "vlc", "system", "web"};
    static final String[] ENGINE_NAMES = {"ExoPlayer", "VLC", "Sistem oynatıcısı", "HLS oynatıcı (web)"};
}
