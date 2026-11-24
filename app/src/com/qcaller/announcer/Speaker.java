package com.qcaller.announcer;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.HashMap;
import java.util.Locale;

/**
 * 语音播报器：封装系统 TTS + 「叮咚」提示音 + 重复播报队列。
 * 使用 USAGE_MEDIA / STREAM_MUSIC，声音会跟随手机媒体通道走，连接蓝牙音箱后自动外放。
 */
public class Speaker implements TextToSpeech.OnInitListener {

    public interface Listener {
        void onTtsReady(boolean ready, boolean chineseOk);
        void onSpeakingChanged(boolean speaking);
    }

    private final Context app;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private Listener listener;

    private boolean ready = false;
    private boolean chineseOk = false;
    private boolean speaking = false;

    private int session = 0;
    private String text = "";
    private int steps = 1;
    private float rate = 0.95f;
    private float volume = 1f;

    private final Runnable watchdog = new Runnable() {
        @Override public void run() { setSpeaking(false); }
    };

    public Speaker(Context c, Listener l) {
        app = c.getApplicationContext();
        listener = l;
        tts = new TextToSpeech(app, this);
    }

    public void setListener(Listener l) { listener = l; }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            ready = false;
            notifyReady();
            return;
        }
        int r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            int r2 = tts.setLanguage(Locale.CHINESE);
            chineseOk = !(r2 == TextToSpeech.LANG_MISSING_DATA || r2 == TextToSpeech.LANG_NOT_SUPPORTED);
        } else {
            chineseOk = true;
        }
        tts.setSpeechRate(rate);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }
            @Override public void onDone(String utteranceId) { handleStepDone(utteranceId); }
            @Override public void onError(String utteranceId) { handleStepDone(utteranceId); }
        });
        ready = true;
        notifyReady();
    }

    private void notifyReady() {
        if (listener != null) {
            h.post(new Runnable() {
                @Override public void run() { listener.onTtsReady(ready, chineseOk); }
            });
        }
    }

    private void setSpeaking(final boolean s) {
        if (speaking == s) return;
        speaking = s;
        if (listener != null) {
            h.post(new Runnable() {
                @Override public void run() { listener.onSpeakingChanged(s); }
            });
        }
    }

    public boolean isReady() { return ready; }
    public boolean isChineseOk() { return chineseOk; }
    public boolean isSpeaking() { return speaking; }

    public String engineName() {
        try {
            String e = tts == null ? null : tts.getDefaultEngine();
            return e == null ? "系统默认" : e;
        } catch (Exception e) {
            return "系统默认";
        }
    }

    /** 播报一段文本，重复 repeats 次 */
    public void speak(String content, int repeats, float speechRate, float vol, boolean chime) {
        if (!ready) return;
        session++;
        final int my = session;
        try { tts.stop(); } catch (Exception ignore) { }
        h.removeCallbacksAndMessages(null);

        text = content;
        steps = Math.max(1, Math.min(5, repeats));
        rate = speechRate;
        volume = vol;
        setSpeaking(true);

        if (chime) {
            playChime(vol);
            h.postDelayed(new Runnable() {
                @Override public void run() { if (my == session) speakStep(0); }
            }, 700);
        } else {
            h.postDelayed(new Runnable() {
                @Override public void run() { if (my == session) speakStep(0); }
            }, 120);
        }
    }

    private void speakStep(int idx) {
        if (!ready) { setSpeaking(false); return; }
        try {
            tts.setSpeechRate(rate);
            android.os.Bundle params = new android.os.Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
            String uttId = "u" + session + "_" + idx;
            tts.speak(text, idx == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, params, uttId);
            // 保险：个别引擎不回调 onDone
            h.removeCallbacks(watchdog);
            long est = 5000L + (long) (text.length() * 800 / Math.max(0.6f, rate));
            h.postDelayed(watchdog, est);
        } catch (Exception e) {
            setSpeaking(false);
        }
    }

    private void handleStepDone(String uttId) {
        h.post(new Runnable() {
            @Override public void run() {
                // 解析 u{session}_{idx}
                if (uttId == null || !uttId.startsWith("u")) return;
                int us = uttId.indexOf('_');
                if (us < 0) return;
                int my, idx;
                try {
                    my = Integer.parseInt(uttId.substring(1, us));
                    idx = Integer.parseInt(uttId.substring(us + 1));
                } catch (Exception e) { return; }
                if (my != session) return;
                final int next = idx + 1;
                if (next < steps) {
                    h.removeCallbacks(watchdog);
                    h.postDelayed(new Runnable() {
                        @Override public void run() { if (my == session) speakStep(next); }
                    }, 720);
                } else {
                    h.removeCallbacks(watchdog);
                    setSpeaking(false);
                }
            }
        });
    }

    public void stop() {
        session++;
        h.removeCallbacksAndMessages(null);
        try { if (tts != null) tts.stop(); } catch (Exception ignore) { }
        setSpeaking(false);
    }

    public void shutdown() {
        stop();
        try { if (tts != null) { tts.shutdown(); tts = null; } } catch (Exception ignore) { }
        ready = false;
    }

    /** 叮咚提示音（正弦合成，走媒体通道 → 蓝牙音箱） */
    private void playChime(final float vol) {
        new Thread(new Runnable() {
            @Override public void run() {
                AudioTrack track = null;
                try {
                    final int sr = 22050;
                    final int n = (int) (sr * 0.62);
                    short[] buf = new short[n];
                    for (int i = 0; i < n; i++) {
                        double t = i / (double) sr;
                        double v = Math.sin(2 * Math.PI * 987.77 * t) * Math.exp(-t * 7.0) * 0.55;
                        if (t > 0.155) {
                            double t2 = t - 0.155;
                            v += Math.sin(2 * Math.PI * 1318.51 * t2) * Math.exp(-t2 * 6.0) * 0.5;
                        }
                        double val = v * 0.55 * vol;
                        if (val > 1) val = 1;
                        if (val < -1) val = -1;
                        buf[i] = (short) (val * 32767);
                    }
                    track = new AudioTrack(AudioManager.STREAM_MUSIC, sr,
                            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            buf.length * 2, AudioTrack.MODE_STATIC);
                    track.write(buf, 0, buf.length);
                    track.play();
                    Thread.sleep(720);
                } catch (Exception ignore) {
                } finally {
                    if (track != null) {
                        try { track.stop(); } catch (Exception ignore) { }
                        try { track.release(); } catch (Exception ignore) { }
                    }
                }
            }
        }, "chime").start();
    }
}
