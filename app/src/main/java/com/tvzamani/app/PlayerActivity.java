package com.tvzamani.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Yerel oynatıcı (Google ExoPlayer / Media3).
 * HLS, MPEG-TS (uzantısız canlı yayınlar dahil), MP4, MKV, FLV oynatır;
 * yönlendirmeleri takip eder, kopan yayına yeniden bağlanır.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {


    private static final String UA = "VLC/3.0.20 LibVLC/3.0.20";
    private DefaultHttpDataSource.Factory http;

    private ExoPlayer player;
    private PlayerView view;
    private LinearLayout top;
    private TextView title, err;
    private Button prevBtn, nextBtn;
    private JSONArray items;
    private int index;
    private int attempt;          // 0: otomatik tür, 1: diğer tür (HLS <-> düz akış)
    private int retries;
    private boolean played;
    private boolean autoNext;
    private String nextEngine = "";
    private String lastError = "";
    private int resize = 0;
    private String numBuf = "";
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable numRun = this::applyNumber;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        items = Pending.items != null ? Pending.items : new JSONArray();
        index = Math.max(0, Math.min(Pending.index, items.length() - 1));
        long start = Pending.start;
        autoNext = Pending.autoNext;
        boolean sw = Pending.softwareDecoder;
        Pending.items = null;
        if (items.length() == 0) { finish(); return; }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        view = new PlayerView(this);
        view.setKeepScreenOn(true);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        // Üst çubuk: başlık + önceki/sonraki + oran + HLS'e geç
        top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(10), dp(16), dp(10));
        top.setBackgroundColor(0xAA000000);
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        prevBtn = button("‹ Önceki", v -> zap(-1));
        nextBtn = button("Sonraki ›", v -> zap(1));
        top.addView(prevBtn);
        top.addView(nextBtn);
        top.addView(button("Oran", v -> cycleResize()));
        top.addView(button("Oynatıcı", v -> chooseEngine()));
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Hata ekranı
        err = new TextView(this);
        err.setTextColor(Color.WHITE);
        err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        err.setGravity(Gravity.CENTER);
        err.setPadding(dp(32), dp(32), dp(32), dp(32));
        err.setBackgroundColor(0xE6000000);
        err.setVisibility(View.GONE);
        err.setOnClickListener(v -> retry());
        root.addView(err, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        hideSystemUi();

        http = new DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000);
        DataSource.Factory ds = new DefaultDataSource.Factory(this, http);
        DefaultExtractorsFactory ef = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS);
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this).setEnableDecoderFallback(true);
        if (sw) {
            // TiviMate'teki "yazılım kod çözücü" seçeneği: önce yazılım çözücüleri dene
            MediaCodecSelector softwareFirst = (mime, secure, tunneling) -> {
                List<MediaCodecInfo> l = new ArrayList<>(MediaCodecUtil.getDecoderInfos(mime, secure, tunneling));
                Collections.sort(l, (a, b) -> Boolean.compare(!a.softwareOnly, !b.softwareOnly));
                return l;
            };
            rf.setMediaCodecSelector(softwareFirst);
        }

        player = new ExoPlayer.Builder(this, rf)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(ds, ef))
                .build();
        player.setPlayWhenReady(true);
        view.setPlayer(player);
        view.setShowSubtitleButton(true);
        view.setShowNextButton(false);
        view.setShowPreviousButton(false);
        view.setControllerShowTimeoutMs(4000);
        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) vis -> top.setVisibility(vis));

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_READY) {
                    played = true;
                    retries = 0;
                    err.setVisibility(View.GONE);
                } else if (st == Player.STATE_ENDED) {
                    onEnded();
                }
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                handleError(e);
            }
        });

        load(start);
    }

    // ------------------------------------------------------------ yükleme
    private JSONObject cur() { return items.optJSONObject(index); }

    private static boolean isHlsUrl(String u) {
        return u.toLowerCase(Locale.ROOT).matches(".*\\.m3u8?(\\?.*)?$");
    }

    private void load(long startMs) {
        JSONObject it = cur();
        if (it == null) { finishWithResult(); return; }
        String url = it.optString("url");
        title.setText(it.optString("title"));
        prevBtn.setVisibility(items.length() > 1 ? View.VISIBLE : View.GONE);
        nextBtn.setVisibility(items.length() > 1 ? View.VISIBLE : View.GONE);
        err.setVisibility(View.GONE);

        java.util.Map<String, String> hh = Hdr.of(it);
        String ua = hh.remove("User-Agent");
        http.setUserAgent(ua != null ? ua : UA);
        http.setDefaultRequestProperties(hh);

        boolean hls = isHlsUrl(url);
        String mime = null;
        if (attempt == 0 && hls) mime = MimeTypes.APPLICATION_M3U8;
        if (attempt == 1 && !hls) mime = MimeTypes.APPLICATION_M3U8;   // uzantısız HLS olabilir
        MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(url));
        if (mime != null) b.setMimeType(mime);

        if (startMs > 0) player.setMediaItem(b.build(), startMs);
        else player.setMediaItem(b.build());
        player.prepare();
        player.play();
    }

    private void retry() {
        attempt = 0;
        retries = 0;
        long p = isLive() ? 0 : Math.max(0, player.getCurrentPosition());
        load(p);
    }

    private boolean isLive() {
        return player == null || player.isCurrentMediaItemLive() || player.getDuration() == C.TIME_UNSET;
    }

    private void onEnded() {
        if (played && isLiveItem()) {           // canlı yayın koptu → yeniden bağlan
            if (retries < 3) { retries++; h.postDelayed(() -> load(0), 1000); return; }
            showError("Yayın kesildi.");
            return;
        }
        if (autoNext && index < items.length() - 1) {
            index++;
            attempt = 0; retries = 0; played = false;
            Toast.makeText(this, "Sonraki bölüm", Toast.LENGTH_SHORT).show();
            load(0);
        } else {
            finishWithResult();
        }
    }

    private boolean isLiveItem() {
        JSONObject it = cur();
        return it != null && it.optBoolean("live") && (player.getDuration() == C.TIME_UNSET || player.isCurrentMediaItemLive());
    }

    // ------------------------------------------------------------ hatalar
    private void handleError(PlaybackException e) {
        int code = e.errorCode;
        if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        lastError = describe(e);
        boolean parse = code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                || code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
        boolean net = code >= 2000 && code < 3000;

        if (parse && attempt == 0) {                 // tür yanlış tahmin edildi → diğerini dene
            attempt = 1;
            load(0);
            return;
        }
        if (net && retries < 3 && (played || retries == 0)) {
            retries++;
            Toast.makeText(this, "Bağlantı sorunu, yeniden deneniyor (" + retries + "/3)…", Toast.LENGTH_SHORT).show();
            final long p = isLive() ? 0 : Math.max(0, player.getCurrentPosition());
            h.postDelayed(() -> load(p), 1500L * retries);
            return;
        }
        showError(lastError);
    }

    private String describe(PlaybackException e) {
        Throwable c = e.getCause();
        while (c != null) {
            if (c instanceof HttpDataSource.InvalidResponseCodeException) {
                int rc = ((HttpDataSource.InvalidResponseCodeException) c).responseCode;
                if (rc == 401 || rc == 403) return "Sunucu erişimi reddetti (HTTP " + rc + "). Hesabın süresi dolmuş veya bağlantı sınırı aşılmış olabilir.";
                if (rc == 404) return "Yayın bulunamadı (HTTP 404).";
                return "Sunucu hatası (HTTP " + rc + ").";
            }
            c = c.getCause();
        }
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
                return "Sunucuya bağlanılamadı. İnternet bağlantını veya linki kontrol et.";
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return "Sunucu yanıt vermedi (zaman aşımı).";
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
                return "Bu link bir video yayını değil ya da formatı tanınmıyor.";
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES:
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
                return "Bu cihaz videonun görüntü/ses formatını çözemiyor. VLC ile açmayı dene.";
            default:
                return "Oynatma hatası: " + e.getErrorCodeName();
        }
    }

    private void showError(String msg) {
        err.setText(msg + "\n\nOK / dokun: tekrar dene\nSarı tuş: başka oynatıcı seç\nGeri: çık");
        err.setVisibility(View.VISIBLE);
        err.requestFocus();
    }

    // ------------------------------------------------------------ kontroller
    private void zap(int d) {
        int n = items.length();
        if (n < 2) return;
        int ni = index + d;
        JSONObject it = cur();
        if (it != null && it.optBoolean("live")) ni = (ni + n) % n;
        else if (ni < 0 || ni >= n) return;
        index = ni;
        attempt = 0; retries = 0; played = false;
        load(0);
        view.showController();
    }

    private void cycleResize() {
        int[] modes = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL};
        String[] names = {"Sığdır", "Doldur", "Uzat"};
        resize = (resize + 1) % modes.length;
        view.setResizeMode(modes[resize]);
        Toast.makeText(this, "Görüntü: " + names[resize], Toast.LENGTH_SHORT).show();
    }

    private void chooseEngine() {
        List<String> names = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < Pending.ENGINE_CODES.length; i++) {
            if (Pending.ENGINE_CODES[i].equals("exo")) continue;
            codes.add(Pending.ENGINE_CODES[i]);
            names.add(Pending.ENGINE_NAMES[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle("Oynatıcı seç")
                .setItems(names.toArray(new String[0]), (dlg, w) -> {
                    nextEngine = codes.get(w);
                    finishWithResult();
                })
                .show();
    }

    private void applyNumber() {
        if (numBuf.isEmpty()) return;
        int n;
        try { n = Integer.parseInt(numBuf); } catch (Exception e) { n = -1; }
        numBuf = "";
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && it.optInt("num") == n) {
                index = i; attempt = 0; retries = 0; played = false;
                load(0);
                return;
            }
        }
        Toast.makeText(this, "Kanal " + n + " bulunamadı", Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (k == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);

        if (k == KeyEvent.KEYCODE_PROG_YELLOW) { if (down) chooseEngine(); return true; }
        if (err.getVisibility() == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (down) retry();
                return true;
            }
        }
        if (k == KeyEvent.KEYCODE_CHANNEL_UP || k == KeyEvent.KEYCODE_PAGE_UP) { if (down) zap(-1); return true; }
        if (k == KeyEvent.KEYCODE_CHANNEL_DOWN || k == KeyEvent.KEYCODE_PAGE_DOWN) { if (down) zap(1); return true; }
        if (k == KeyEvent.KEYCODE_PROG_BLUE) { if (down) cycleResize(); return true; }
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
            if (down) {
                numBuf += (k - KeyEvent.KEYCODE_0);
                Toast.makeText(this, "Kanal: " + numBuf, Toast.LENGTH_SHORT).show();
                h.removeCallbacks(numRun);
                h.postDelayed(numRun, 1500);
            }
            return true;
        }
        boolean ctrl = view.isControllerFullyVisible();
        if (!ctrl && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) && items.length() > 1 && isLive()) {
            if (down) zap(k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            return true;
        }
        if (!ctrl && !isLive() && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (down) {
                long p = player.getCurrentPosition() + (k == KeyEvent.KEYCODE_DPAD_RIGHT ? 10000 : -10000);
                player.seekTo(Math.max(0, p));
                view.showController();
            }
            return true;
        }
        return view.dispatchKeyEvent(e) || super.dispatchKeyEvent(e);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (err.getVisibility() != View.VISIBLE && view.isControllerFullyVisible()) {
            view.hideController();
            return;
        }
        finishWithResult();
    }

    private void finishWithResult() {
        Intent d = new Intent();
        d.putExtra("index", index);
        long pos = 0, dur = 0;
        if (player != null) {
            pos = Math.max(0, player.getCurrentPosition());
            long du = player.getDuration();
            dur = (du == C.TIME_UNSET || player.isCurrentMediaItemLive()) ? 0 : du;
        }
        d.putExtra("pos", pos);
        d.putExtra("dur", dur);
        d.putExtra("err", err.getVisibility() == View.VISIBLE ? lastError : "");
        d.putExtra("next", nextEngine);
        setResult(RESULT_OK, d);
        finish();
    }

    // ------------------------------------------------------------ yaşam döngüsü
    @Override
    protected void onStop() {
        super.onStop();
        if (player != null && !isFinishing()) player.pause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null) player.play();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        b.setOnFocusChangeListener((v, f) -> bg.setStroke(f ? dp(2) : 0, Color.WHITE));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.leftMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
