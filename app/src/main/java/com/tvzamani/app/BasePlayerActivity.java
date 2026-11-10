package com.tvzamani.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VLC ve Sistem oynatıcısı için ortak ekran: üst çubuk, alt kontroller,
 * kanal değiştirme, numara ile kanal, hata ekranı, kaldığı yer bilgisi.
 */
public abstract class BasePlayerActivity extends Activity {

    protected static final String UA = "VLC/3.0.20 LibVLC/3.0.20";
    /** Geçerli yayının özel istek başlıkları (User-Agent, Referer...). */
    protected java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();

    protected JSONArray items;
    protected int index;
    protected boolean autoNext;
    protected boolean softwareDecoder;
    protected final Handler h = new Handler(Looper.getMainLooper());

    private LinearLayout top, bottom;
    private TextView title, timeText, err;
    private ProgressBar spin;
    private SeekBar seek;
    private Button playBtn, prevBtn, nextBtn;
    private boolean dragging;
    protected boolean played;
    protected int retries;
    protected String lastError = "";
    private String nextEngine = "";
    private String numBuf = "";

    private final Runnable hideRun = this::hideControls;
    private final Runnable numRun = this::applyNumber;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateProgress();
            h.postDelayed(this, 500);
        }
    };

    // ------------------------------------------------ motorun uygulaması gerekenler
    protected abstract String engineCode();
    protected abstract View createVideoView();
    protected abstract void engineStart(String url, long startMs);
    protected abstract void engineStop();
    protected abstract void engineRelease();
    protected abstract void enginePlayPause();
    protected abstract boolean engineIsPlaying();
    protected abstract long enginePosition();
    /** Bilinmiyor veya canlı yayın ise 0 ya da negatif. */
    protected abstract long engineDuration();
    protected abstract void engineSeek(long ms);
    protected void engineAudioTracks() { toast("Bu oynatıcıda ses parçası seçimi yok"); }
    protected void engineSubtitles() { toast("Bu oynatıcıda altyazı seçimi yok"); }
    protected void engineResize() { toast("Bu oynatıcıda görüntü oranı değiştirilemiyor"); }

    // ------------------------------------------------ kurulum
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        items = Pending.items != null ? Pending.items : new JSONArray();
        index = Math.max(0, Math.min(Pending.index, items.length() - 1));
        autoNext = Pending.autoNext;
        softwareDecoder = Pending.softwareDecoder;
        long start = Pending.start;
        Pending.items = null;
        if (items.length() == 0) { finish(); return; }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        View video;
        try {
            video = createVideoView();
        } catch (Throwable t) {
            setContentView(root);
            lastError = "Bu oynatıcı bu cihazda çalışamıyor: " + t.getMessage();
            nextEngine = "exo";
            finishWithResult();
            return;
        }
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));

        // Dokunarak kontrolleri aç/kapat
        View catcher = new View(this);
        catcher.setOnClickListener(v -> { if (top.getVisibility() == View.VISIBLE) hideControls(); else showControls(); });
        root.addView(catcher, new FrameLayout.LayoutParams(-1, -1));

        spin = new ProgressBar(this);
        root.addView(spin, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));

        // Üst çubuk
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
        TextView badge = new TextView(this);
        badge.setText(engineName());
        badge.setTextColor(0xFFBBBBBB);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        top.addView(badge);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Alt kontroller
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(16), dp(8), dp(16), dp(12));
        bottom.setBackgroundColor(0xAA000000);
        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setKeyProgressIncrement(10);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && !dragging) seekToFraction(p);
                if (user) showControls();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; seekToFraction(s.getProgress()); }
        });
        seekRow.addView(seek, new LinearLayout.LayoutParams(0, -2, 1f));
        timeText = new TextView(this);
        timeText.setTextColor(Color.WHITE);
        timeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        seekRow.addView(timeText);
        bottom.addView(seekRow);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        prevBtn = button("⏮", v -> zap(-1));
        row.addView(prevBtn);
        row.addView(button("−10", v -> seekBy(-10000)));
        playBtn = button("❚❚", v -> { enginePlayPause(); h.postDelayed(this::updatePlayIcon, 200); showControls(); });
        row.addView(playBtn);
        row.addView(button("+10", v -> seekBy(10000)));
        nextBtn = button("⏭", v -> zap(1));
        row.addView(nextBtn);
        row.addView(button("Ses", v -> engineAudioTracks()));
        row.addView(button("Altyazı", v -> engineSubtitles()));
        row.addView(button("Oran", v -> engineResize()));
        row.addView(button("Oynatıcı", v -> chooseEngine()));
        bottom.addView(row);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

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
        h.post(tick);
        startCurrent(start);
        showControls();
    }

    // ------------------------------------------------ motor geri bildirimleri
    protected void onBuffering(boolean b) {
        spin.setVisibility(b ? View.VISIBLE : View.GONE);
    }

    protected void onReady() {
        played = true;
        retries = 0;
        err.setVisibility(View.GONE);
        spin.setVisibility(View.GONE);
        updatePlayIcon();
    }

    protected void onEnded() {
        if (isLiveItem()) {
            onError("Yayın kesildi.", true);
            return;
        }
        if (autoNext && index < items.length() - 1) {
            index++;
            played = false; retries = 0;
            toast("Sonraki bölüm");
            startCurrent(0);
        } else {
            finishWithResult();
        }
    }

    protected void onError(String msg, boolean network) {
        lastError = msg;
        if (network && retries < 3 && (played || retries == 0)) {
            retries++;
            toast("Bağlantı sorunu, yeniden deneniyor (" + retries + "/3)…");
            final long p = isLiveItem() ? 0 : Math.max(0, enginePosition());
            h.postDelayed(() -> startCurrent(p), 1500L * retries);
            return;
        }
        spin.setVisibility(View.GONE);
        err.setText(msg + "\n\nOK / dokun: tekrar dene\nSarı tuş: başka oynatıcı seç\nGeri: çık");
        err.setVisibility(View.VISIBLE);
        err.requestFocus();
    }

    // ------------------------------------------------ oynatma akışı
    protected JSONObject cur() { return items.optJSONObject(index); }

    protected boolean isLiveItem() {
        return engineDuration() <= 0;
    }

    private void startCurrent(long startMs) {
        JSONObject it = cur();
        if (it == null) { finishWithResult(); return; }
        title.setText(it.optString("title"));
        int vis = items.length() > 1 ? View.VISIBLE : View.GONE;
        prevBtn.setVisibility(vis);
        nextBtn.setVisibility(vis);
        err.setVisibility(View.GONE);
        spin.setVisibility(View.VISIBLE);
        try { engineStop(); } catch (Throwable ignored) { }
        headers = Hdr.of(it);
        engineStart(it.optString("url"), startMs);
    }

    private void retry() {
        retries = 0;
        startCurrent(isLiveItem() ? 0 : Math.max(0, enginePosition()));
    }

    private void zap(int d) {
        int n = items.length();
        if (n < 2) return;
        int ni = index + d;
        JSONObject it = cur();
        if (it != null && it.optBoolean("live")) ni = (ni + n) % n;
        else if (ni < 0 || ni >= n) return;
        index = ni;
        played = false; retries = 0;
        startCurrent(0);
        showControls();
    }

    private void seekBy(long ms) {
        long d = engineDuration();
        if (d <= 0) return;
        engineSeek(Math.max(0, Math.min(d - 1000, enginePosition() + ms)));
        showControls();
    }

    private void seekToFraction(int p) {
        long d = engineDuration();
        if (d > 0) engineSeek(d * p / 1000);
    }

    private void updateProgress() {
        if (seek == null) return;
        long d = engineDuration(), p = enginePosition();
        if (d > 0) {
            seek.setEnabled(true);
            if (!dragging) seek.setProgress((int) (p * 1000 / d));
            timeText.setText("  " + fmt(p) + " / " + fmt(d));
        } else {
            seek.setEnabled(false);
            seek.setProgress(0);
            timeText.setText("  CANLI");
        }
    }

    private void updatePlayIcon() {
        if (playBtn != null) playBtn.setText(engineIsPlaying() ? "❚❚" : "▶");
    }

    private void applyNumber() {
        if (numBuf.isEmpty()) return;
        int n;
        try { n = Integer.parseInt(numBuf); } catch (Exception e) { n = -1; }
        numBuf = "";
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && it.optInt("num") == n) {
                index = i; played = false; retries = 0;
                startCurrent(0);
                return;
            }
        }
        toast("Kanal " + n + " bulunamadı");
    }

    private void chooseEngine() {
        List<String> names = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < Pending.ENGINE_CODES.length; i++) {
            if (Pending.ENGINE_CODES[i].equals(engineCode())) continue;
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

    // ------------------------------------------------ kontrollerin görünürlüğü
    private void showControls() {
        top.setVisibility(View.VISIBLE);
        bottom.setVisibility(View.VISIBLE);
        View f = getCurrentFocus();
        if (f == null || !(isChild(bottom, f) || isChild(top, f))) playBtn.requestFocus();
        h.removeCallbacks(hideRun);
        h.postDelayed(hideRun, 4500);
        updatePlayIcon();
    }

    private void hideControls() {
        if (!engineIsPlaying() && played) return;   // duraklatılmışken açık kalsın
        top.setVisibility(View.GONE);
        bottom.setVisibility(View.GONE);
        h.removeCallbacks(hideRun);
    }

    private static boolean isChild(ViewGroup g, View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == g) return true;
        }
        return false;
    }

    // ------------------------------------------------ tuşlar
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
            return super.dispatchKeyEvent(e);
        }
        if (k == KeyEvent.KEYCODE_CHANNEL_UP || k == KeyEvent.KEYCODE_PAGE_UP) { if (down) zap(-1); return true; }
        if (k == KeyEvent.KEYCODE_CHANNEL_DOWN || k == KeyEvent.KEYCODE_PAGE_DOWN) { if (down) zap(1); return true; }
        if (k == KeyEvent.KEYCODE_PROG_BLUE) { if (down) engineResize(); return true; }
        if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (down) { enginePlayPause(); h.postDelayed(this::updatePlayIcon, 200); showControls(); }
            return true;
        }
        if (k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) { if (down) seekBy(30000); return true; }
        if (k == KeyEvent.KEYCODE_MEDIA_REWIND) { if (down) seekBy(-30000); return true; }
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
            if (down) {
                numBuf += (k - KeyEvent.KEYCODE_0);
                toast("Kanal: " + numBuf);
                h.removeCallbacks(numRun);
                h.postDelayed(numRun, 1500);
            }
            return true;
        }
        boolean visible = top.getVisibility() == View.VISIBLE;
        if (!visible) {
            boolean live = isLiveItem();
            if ((k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) && live && items.length() > 1) {
                if (down) zap(k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                return true;
            }
            if ((k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) && !live) {
                if (down) seekBy(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 10000 : -10000);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_DPAD_UP
                    || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) showControls();
                return true;
            }
        } else if (down) {
            h.removeCallbacks(hideRun);
            h.postDelayed(hideRun, 4500);
        }
        return super.dispatchKeyEvent(e);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (err.getVisibility() != View.VISIBLE && top.getVisibility() == View.VISIBLE && played) {
            top.setVisibility(View.GONE);
            bottom.setVisibility(View.GONE);
            return;
        }
        finishWithResult();
    }

    protected void finishWithResult() {
        Intent d = new Intent();
        d.putExtra("index", index);
        long pos = 0, dur = 0;
        try {
            pos = Math.max(0, enginePosition());
            dur = Math.max(0, engineDuration());
        } catch (Throwable ignored) { }
        d.putExtra("pos", pos);
        d.putExtra("dur", dur);
        d.putExtra("err", err != null && err.getVisibility() == View.VISIBLE ? lastError : (nextEngine.isEmpty() ? "" : lastError));
        d.putExtra("next", nextEngine);
        setResult(RESULT_OK, d);
        finish();
    }

    // ------------------------------------------------ yaşam döngüsü
    @Override
    protected void onStop() {
        super.onStop();
        try { if (!isFinishing() && engineIsPlaying()) enginePlayPause(); } catch (Throwable ignored) { }
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        try { engineRelease(); } catch (Throwable ignored) { }
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

    // ------------------------------------------------ yardımcılar
    protected String engineName() {
        for (int i = 0; i < Pending.ENGINE_CODES.length; i++) if (Pending.ENGINE_CODES[i].equals(engineCode())) return Pending.ENGINE_NAMES[i];
        return engineCode();
    }

    protected void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    protected interface IntPick { void on(int which); }

    protected void pick(String titleText, String[] names, int selected, IntPick onPick) {
        new AlertDialog.Builder(this)
                .setTitle(titleText)
                .setSingleChoiceItems(names, selected, (dlg, w) -> { onPick.on(w); dlg.dismiss(); })
                .show();
    }

    private static String fmt(long ms) {
        long s = ms / 1000, hh = s / 3600, mm = (s % 3600) / 60, ss = s % 60;
        return hh > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hh, mm, ss) : String.format(Locale.ROOT, "%d:%02d", mm, ss);
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
        b.setMinWidth(dp(48));
        b.setMinimumWidth(dp(48));
        b.setPadding(dp(12), dp(4), dp(12), dp(4));
        b.setOnFocusChangeListener((v, f) -> bg.setStroke(f ? dp(2) : 0, Color.WHITE));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    protected int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
