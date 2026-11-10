package com.tvzamani.app;

import android.net.Uri;
import android.view.View;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

/**
 * Yerleşik VLC oynatıcı (libVLC). IPTV Smarters ve OTT Navigator'daki "VLC" seçeneğinin karşılığı.
 * MKV, AC3/DTS ses, uzantısız TS, RTSP/UDP gibi ExoPlayer'ın zorlandığı yayınları da oynatır.
 */
public class VlcPlayerActivity extends BasePlayerActivity {

    private LibVLC vlc;
    private MediaPlayer mp;
    private VLCVideoLayout layout;
    private int scaleIndex = 0;
    private boolean attached;

    private static final MediaPlayer.ScaleType[] SCALES = {
            MediaPlayer.ScaleType.SURFACE_BEST_FIT,
            MediaPlayer.ScaleType.SURFACE_FIT_SCREEN,
            MediaPlayer.ScaleType.SURFACE_FILL,
            MediaPlayer.ScaleType.SURFACE_16_9,
            MediaPlayer.ScaleType.SURFACE_4_3
    };
    private static final String[] SCALE_NAMES = {"Sığdır", "Ekrana yay", "Doldur", "16:9", "4:3"};

    @Override protected String engineCode() { return "vlc"; }

    @Override
    protected View createVideoView() {
        ArrayList<String> opts = new ArrayList<>();
        opts.add("--network-caching=3000");
        opts.add("--live-caching=3000");
        opts.add("--http-reconnect");
        opts.add("--http-user-agent=" + UA);
        opts.add("--audio-time-stretch");
        opts.add("--no-stats");
        vlc = new LibVLC(this, opts);
        mp = new MediaPlayer(vlc);
        layout = new VLCVideoLayout(this);
        mp.setEventListener(ev -> runOnUiThread(() -> onVlcEvent(ev)));
        return layout;
    }

    private void onVlcEvent(MediaPlayer.Event ev) {
        switch (ev.type) {
            case MediaPlayer.Event.Opening:
                onBuffering(true);
                break;
            case MediaPlayer.Event.Buffering:
                onBuffering(ev.getBuffering() < 100f);
                break;
            case MediaPlayer.Event.Playing:
                onReady();
                break;
            case MediaPlayer.Event.EndReached:
                onEnded();
                break;
            case MediaPlayer.Event.EncounteredError:
                onError("VLC yayını açamadı. Link geçersiz, sunucu kapalı ya da erişim reddedilmiş olabilir.", true);
                break;
            default:
                break;
        }
    }

    @Override
    protected void engineStart(String url, long startMs) {
        if (!attached) { mp.attachViews(layout, null, true, false); attached = true; }
        Media m = new Media(vlc, Uri.parse(url));
        m.setHWDecoderEnabled(!softwareDecoder, false);
        m.addOption(":network-caching=3000");
        m.addOption(":http-user-agent=" + UA);
        if (startMs > 0) m.addOption(":start-time=" + (startMs / 1000));
        mp.setMedia(m);
        m.release();
        mp.setVideoScale(SCALES[scaleIndex]);
        mp.play();
    }

    @Override protected void engineStop() { if (mp != null) mp.stop(); }

    @Override
    protected void engineRelease() {
        if (mp != null) {
            mp.setEventListener(null);
            mp.stop();
            if (attached) mp.detachViews();
            mp.release();
            mp = null;
        }
        if (vlc != null) { vlc.release(); vlc = null; }
    }

    @Override
    protected void enginePlayPause() {
        if (mp == null) return;
        if (mp.isPlaying()) mp.pause(); else mp.play();
    }

    @Override protected boolean engineIsPlaying() { return mp != null && mp.isPlaying(); }
    @Override protected long enginePosition() { return mp == null ? 0 : Math.max(0, mp.getTime()); }
    @Override protected long engineDuration() { return mp == null ? 0 : mp.getLength(); }
    @Override protected void engineSeek(long ms) { if (mp != null && mp.isSeekable()) mp.setTime(ms); }

    @Override
    protected void engineAudioTracks() {
        MediaPlayer.TrackDescription[] t = mp == null ? null : mp.getAudioTracks();
        if (t == null || t.length <= 1) { toast("Tek ses parçası mevcut"); return; }
        String[] names = new String[t.length];
        int sel = 0, cur = mp.getAudioTrack();
        for (int i = 0; i < t.length; i++) { names[i] = t[i].name; if (t[i].id == cur) sel = i; }
        pick("Ses parçası", names, sel, w -> mp.setAudioTrack(t[w].id));
    }

    @Override
    protected void engineSubtitles() {
        MediaPlayer.TrackDescription[] t = mp == null ? null : mp.getSpuTracks();
        if (t == null || t.length == 0) { toast("Altyazı bulunamadı"); return; }
        String[] names = new String[t.length];
        int sel = 0, cur = mp.getSpuTrack();
        for (int i = 0; i < t.length; i++) { names[i] = t[i].name; if (t[i].id == cur) sel = i; }
        pick("Altyazı", names, sel, w -> mp.setSpuTrack(t[w].id));
    }

    @Override
    protected void engineResize() {
        scaleIndex = (scaleIndex + 1) % SCALES.length;
        if (mp != null) mp.setVideoScale(SCALES[scaleIndex]);
        toast("Görüntü: " + SCALE_NAMES[scaleIndex]);
    }
}
