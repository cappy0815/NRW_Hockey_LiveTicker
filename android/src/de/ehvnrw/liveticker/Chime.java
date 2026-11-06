package de.ehvnrw.liveticker;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;

/**
 * Dezenter Hinweiston zum Display-Wecker (res/raw/chime.wav, ca. 1 s).
 * Wird wie eine Navigationsansage abgespielt: Musik wird kurz leiser ("ducking") und der Ton
 * laeuft dorthin, wo auch Medien laufen – also auch ueber Bluetooth im Auto.
 * Lautstaerke = Medienlautstaerke.
 */
final class Chime {

    private Chime() {
    }

    static void play(Context c) {
        final Context app = c.getApplicationContext();
        try {
            final AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            final AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            final AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .build();
            am.requestAudioFocus(focus);
            MediaPlayer mp = MediaPlayer.create(app, R.raw.chime, attrs, am.generateAudioSessionId());
            if (mp == null) {
                am.abandonAudioFocusRequest(focus);
                return;
            }
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer p) {
                    p.release();
                    am.abandonAudioFocusRequest(focus);
                }
            });
            mp.start();
        } catch (Exception ignored) {
        }
    }
}
