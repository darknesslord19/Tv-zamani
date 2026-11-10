package com.tvzamani.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

/**
 * TV Zamanı — web/index.html dosyasını tam ekran çalıştıran kabuk.
 * Tüm arayüz ve mantık HTML'dedir; bu sınıf yalnızca:
 *  - sayfayı uygulama içinden sunar,
 *  - ağ isteklerini yerel olarak yapıp CORS engelini kaldırır (Net sınıfı),
 *  - kumanda tuşlarını, geri tuşunu, dosya seçmeyi, yedek kaydetmeyi
 *    ve harici oynatıcı (VLC/MX) açmayı sağlar.
 */
public class MainActivity extends Activity {

    static final String HOST = "appassets.androidplatform.net";
    static final String HOME = "https://" + HOST + "/index.html";
    private static final int FILE_REQ = 4242;
    private static final int PLAYER_REQ = 4343;

    private WebView web;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ValueCallback<Uri[]> fileCallback;
    private long lastBack;
    private Chrome chrome;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0D12);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        hideSystemUi();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " TVZamani/1.0");

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new Client());
        chrome = new Chrome();
        web.setWebChromeClient(chrome);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();

        if (state != null) web.restoreState(state);
        if (web.getUrl() == null) web.loadUrl(HOME);
    }

    // ---------------------------------------------------------------- ekran
    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        hideSystemUi();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    // ---------------------------------------------------------------- tuşlar
    /** Android TV kumanda tuşlarını HTML'in anladığı tuş kodlarına çevirir. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int js = 0;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_CHANNEL_UP: js = 33; break;          // kanal +
            case KeyEvent.KEYCODE_CHANNEL_DOWN: js = 34; break;        // kanal -
            case KeyEvent.KEYCODE_PROG_RED: js = 403; break;           // favori
            case KeyEvent.KEYCODE_PROG_GREEN: js = 404; break;         // kanal listesi
            case KeyEvent.KEYCODE_PROG_YELLOW: js = 405; break;
            case KeyEvent.KEYCODE_PROG_BLUE:
            case KeyEvent.KEYCODE_MENU: js = 406; break;               // oynatıcı ayarları
            case KeyEvent.KEYCODE_GUIDE: js = 404; break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK: js = 179; break;
            case KeyEvent.KEYCODE_MEDIA_PLAY: js = 415; break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: js = 19; break;
            case KeyEvent.KEYCODE_MEDIA_STOP: js = 413; break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: js = 417; break;
            case KeyEvent.KEYCODE_MEDIA_REWIND: js = 412; break;
            case KeyEvent.KEYCODE_MEDIA_NEXT: js = 34; break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: js = 33; break;
            default: break;
        }
        if (js != 0 && web != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                web.evaluateJavascript("window.tvKey&&window.tvKey(" + js + ")", null);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** Geri tuşu: önce sayfaya sorar (modal/oynatıcı kapatma), değilse çift basışla çıkar. */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (customView != null) {
            chrome.onHideCustomView();
            return;
        }
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.tvBack&&window.tvBack())?'1':'0'", v -> {
            if (v != null && v.contains("1")) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastBack < 2000) {
                finish();
            } else {
                lastBack = now;
                toast("Çıkmak için tekrar geri tuşuna basın");
            }
        });
    }

    // ---------------------------------------------------------------- dosya seçici
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PLAYER_REQ && data != null && web != null) {
            String js = "window.nativeDone&&window.nativeDone(" + data.getIntExtra("index", 0) + ","
                    + data.getLongExtra("pos", 0) + "," + data.getLongExtra("dur", 0) + ","
                    + JSONObject.quote(data.getStringExtra("err") == null ? "" : data.getStringExtra("err")) + ","
                    + JSONObject.quote(data.getStringExtra("next") == null ? "" : data.getStringExtra("next")) + ")";
            web.evaluateJavascript(js, null);
            return;
        }
        if (req == FILE_REQ && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCallback = null;
        }
    }

    // ---------------------------------------------------------------- dış bağlantılar
    private boolean handleUrl(String url) {
        if (url == null || url.startsWith("https://" + HOST)) return false;
        try {
            if (url.startsWith("intent:")) {
                Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.setComponent(null);
                i.setSelector(null);
                try {
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    if (i.getPackage() != null) {
                        i.setPackage(null);
                        try {
                            startActivity(Intent.createChooser(i, "Oynatıcı seç"));
                            return true;
                        } catch (Exception ignored) { }
                    }
                    toast("Uygun video oynatıcı bulunamadı. VLC yüklemeyi deneyin.");
                }
                return true;
            }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("Bağlantı açılamadı");
        }
        return true;
    }

    private class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return Net.handle(MainActivity.this, request);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl().toString());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url);
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            try {
                Intent i = p.createIntent();
                i.setType("*/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(i, FILE_REQ);
            } catch (Exception e) {
                fileCallback = null;
                toast("Dosya seçici açılamadı");
                return false;
            }
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customCallback = callback;
            root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            hideSystemUi();
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;
            root.removeView(customView);
            customView = null;
            if (customCallback != null) customCallback.onCustomViewHidden();
            customCallback = null;
            hideSystemUi();
        }

        /** Videoların başında görünen gri oynat simgesini gizler. */
        @Override
        public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny();
        }
    }

    // ---------------------------------------------------------------- JS köprüsü
    public class Bridge {
        /** Yedeği İndirilenler/TV Zamani klasörüne kaydeder, kaydedilen yeri döndürür. */
        @JavascriptInterface
        public String saveFile(String name, String content) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentResolver cr = getContentResolver();
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TV Zamani");
                    Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return "";
                    try (OutputStream os = cr.openOutputStream(uri)) {
                        if (os == null) return "";
                        os.write(bytes);
                    }
                    return "İndirilenler/TV Zamani/" + name;
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) dir = getFilesDir();
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, name);
                    try (FileOutputStream os = new FileOutputStream(f)) {
                        os.write(bytes);
                    }
                    return f.getAbsolutePath();
                }
            } catch (Exception e) {
                return "";
            }
        }

        /** Videoyu yerel oynatıcıda (ExoPlayer) açar. */
        @JavascriptInterface
        public boolean playNative(String json) {
            try {
                JSONObject o = new JSONObject(json);
                Pending.items = o.getJSONArray("items");
                Pending.index = o.optInt("index", 0);
                Pending.start = o.optLong("start", 0);
                Pending.autoNext = o.optBoolean("autoNext", true);
                Pending.softwareDecoder = "sw".equals(o.optString("decoder"));
                String engine = o.optString("engine", "exo");
                Class<?> cls = "vlc".equals(engine) ? VlcPlayerActivity.class
                        : "system".equals(engine) ? SystemPlayerActivity.class : PlayerActivity.class;
                runOnUiThread(() -> startActivityForResult(new Intent(MainActivity.this, cls), PLAYER_REQ));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * Kısaltılmış / yönlendirmeli linki sonuna kadar takip eder, türünü anlar.
         * Sonuç window.resolved(id, json) ile sayfaya döner.
         */
        @JavascriptInterface
        public void resolveUrl(String url, String headersJson, int id) {
            new Thread(() -> {
                JSONObject res = new JSONObject();
                try {
                    String cur = url;
                    java.util.Map<String, String> hh = new java.util.HashMap<>();
                    try {
                        JSONObject h = new JSONObject(headersJson == null || headersJson.isEmpty() ? "{}" : headersJson);
                        java.util.Iterator<String> ks = h.keys();
                        while (ks.hasNext()) { String k = ks.next(); hh.put(k, h.optString(k)); }
                    } catch (Exception ignored) { }
                    String type = "", ct = "";
                    int code = 0;
                    for (int i = 0; i < 10; i++) {
                        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(cur).openConnection();
                        c.setInstanceFollowRedirects(false);
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(10000);
                        c.setRequestProperty("User-Agent", hh.containsKey("User-Agent") ? hh.get("User-Agent") : "VLC/3.0.20 LibVLC/3.0.20");
                        for (java.util.Map.Entry<String, String> e : hh.entrySet())
                            if (!e.getKey().equals("User-Agent")) c.setRequestProperty(e.getKey(), e.getValue());
                        code = c.getResponseCode();
                        if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                            cur = new java.net.URL(new java.net.URL(cur), c.getHeaderField("Location")).toString();
                            c.disconnect();
                            continue;
                        }
                        ct = c.getContentType() == null ? "" : c.getContentType().toLowerCase(java.util.Locale.ROOT);
                        byte[] buf = new byte[512];
                        int n = 0;
                        if (code < 400) {
                            java.io.InputStream in = c.getInputStream();
                            int r;
                            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
                        }
                        c.disconnect();
                        String head = new String(buf, 0, n, StandardCharsets.ISO_8859_1).replace("ï»¿", "");
                        if (head.startsWith("#EXTM3U") || ct.contains("mpegurl")) type = "hls";
                        else if (ct.contains("text/html") || head.trim().toLowerCase(java.util.Locale.ROOT).startsWith("<")) type = "html";
                        else if (n > 0 && (buf[0] == 0x47 || ct.contains("mp2t"))) type = "ts";
                        else if (head.startsWith("FLV")) type = "flv";
                        else if (n > 8 && (head.substring(4, 8).equals("ftyp") || ct.startsWith("video/"))) type = "file";
                        break;
                    }
                    res.put("url", cur);
                    res.put("type", type);
                    res.put("code", code);
                    res.put("ct", ct);
                } catch (Exception e) {
                    try { res.put("url", url); res.put("type", ""); res.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) { }
                }
                final String js = "window.resolved&&window.resolved(" + id + "," + JSONObject.quote(res.toString()) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        @JavascriptInterface
        public boolean isTv() {
            UiModeManager m = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
            return m != null && m.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        }

        @JavascriptInterface
        public String version() {
            return "1.0";
        }

        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(MainActivity.this::finish);
        }
    }
}
