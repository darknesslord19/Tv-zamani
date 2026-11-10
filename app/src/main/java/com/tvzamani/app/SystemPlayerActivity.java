package com.tvzamani.app;

import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Android'in kendi oynatıcısı (MediaPlayer). IPTV Smarters'taki "Native player" ve
 * OTT Navigator'daki "System" seçeneğinin karşılığı. Bazı cihazlarda donanıma en uyumlu seçenektir.
 */
public class SystemPlayerActivity extends BasePlayerActivity {

    private VideoView vv;
    private MediaPlayer mp;
    private long pendingSeek;

    @Override protected String engineCode() { return "system"; }

    @Override
    protected View createVideoView() {
        FrameLayout wrap = new FrameLayout(this);
        vv = new VideoView(this);
        wrap.addView(vv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        vv.setOnPreparedListener(p -> {
            mp = p;
            p.setOnInfoListener((m, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) onBuffering(true);
                else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onBuffering(false);
                return false;
            });
            if (pendingSeek > 0) { vv.seekTo((int) pendingSeek); pendingSeek = 0; }
            vv.start();
            onReady();
        });
        vv.setOnCompletionListener(p -> onEnded());
        vv.setOnErrorListener((p, what, extra) -> {
            boolean net = extra == MediaPlayer.MEDIA_ERROR_IO || extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT
                    || what == MediaPlayer.MEDIA_ERROR_SERVER_DIED;
            String msg = extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED || extra == MediaPlayer.MEDIA_ERROR_MALFORMED
                    ? "Sistem oynatıcısı bu formatı desteklemiyor. ExoPlayer veya VLC'yi dene."
                    : "Sistem oynatıcısı yayını açamadı (kod " + what + "/" + extra + ").";
            onError(msg, net);
            return true;
        });
        return wrap;
    }

    @Override
    protected void engineStart(String url, long startMs) {
        mp = null;
        pendingSeek = startMs;
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        vv.setVideoURI(Uri.parse(url), headers);
    }

    @Override protected void engineStop() { if (vv != null) vv.stopPlayback(); mp = null; }
    @Override protected void engineRelease() { engineStop(); }

    @Override
    protected void enginePlayPause() {
        if (vv == null) return;
        if (vv.isPlaying()) vv.pause(); else vv.start();
    }

    @Override protected boolean engineIsPlaying() { return vv != null && mp != null && vv.isPlaying(); }
    @Override protected long enginePosition() { return vv == null || mp == null ? 0 : vv.getCurrentPosition(); }
    @Override protected long engineDuration() { return vv == null || mp == null ? 0 : vv.getDuration(); }
    @Override protected void engineSeek(long ms) { if (vv != null && mp != null && vv.canSeekForward()) vv.seekTo((int) ms); }

    @Override
    protected void engineAudioTracks() {
        if (mp == null) { toast("Yayın henüz hazır değil"); return; }
        MediaPlayer.TrackInfo[] all;
        try { all = mp.getTrackInfo(); } catch (Exception e) { toast("Ses parçaları okunamadı"); return; }
        List<Integer> idx = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < all.length; i++) {
            if (all[i].getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                idx.add(i);
                String lang = all[i].getLanguage();
                names.add("Ses " + idx.size() + (lang != null && !lang.equals("und") ? " (" + lang + ")" : ""));
            }
        }
        if (idx.size() <= 1) { toast("Tek ses parçası mevcut"); return; }
        pick("Ses parçası", names.toArray(new String[0]), -1, w -> {
            try { mp.selectTrack(idx.get(w)); } catch (Exception e) { toast("Değiştirilemedi"); }
        });
    }
}
