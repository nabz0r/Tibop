package fr.lettrine.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;

final class GameAudio {
    private final Context context;
    private final SharedPreferences preferences;
    private final AudioManager manager;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    private final AudioFocusRequest focus;
    private MediaPlayer music;
    private SoundPool sounds;
    private int tileSound;
    private int playedSound;
    private int currentTrack;
    private boolean foreground;
    private boolean requested;
    private boolean focused;
    private boolean ducked;

    GameAudio(Context context, SharedPreferences preferences) {
        this.context = context;
        this.preferences = preferences;
        manager = context.getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(this::focusChanged, new Handler(Looper.getMainLooper()))
                .build();
    }

    void foreground(boolean value) {
        foreground = value;
        refresh();
    }

    void refresh() {
        if (!foreground || !preferences.getBoolean("music", false)) {
            if (music != null) music.pause();
            abandonFocus();
            return;
        }
        if (!requested) {
            focused = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            requested = focused;
        }
        if (focused) play();
    }

    private void focusChanged(int change) {
        if (!foreground || !requested) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            focused = true;
            ducked = false;
            play();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            ducked = true;
            adjustVolume();
        } else {
            focused = false;
            if (music != null) music.pause();
            if (change == AudioManager.AUDIOFOCUS_LOSS) abandonFocus();
        }
    }

    private void play() {
        int track = preferences.getInt("music_track", 0) == 1 ? R.raw.atelier : R.raw.ambient;
        if (currentTrack != track || music == null) {
            if (music != null) music.release();
            music = MediaPlayer.create(context, track, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE);
            currentTrack = track;
            if (music != null) music.setLooping(true);
        }
        if (music != null) {
            adjustVolume();
            music.start();
        }
    }

    void adjustVolume() {
        float volume = preferences.getInt("music_volume", 50) / 100f * (ducked ? 0.15f : 1f);
        if (music != null) music.setVolume(volume, volume);
    }

    private void abandonFocus() {
        if (requested) manager.abandonAudioFocusRequest(focus);
        requested = false;
        focused = false;
        ducked = false;
    }

    void prepareEffects() {
        if (sounds != null || !preferences.getBoolean("effects", false)) return;
        AudioAttributes effects = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        sounds = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(effects).build();
        tileSound = sounds.load(context, R.raw.tile_sound, 1);
        playedSound = sounds.load(context, R.raw.played_sound, 1);
    }

    void tile() { effect(tileSound); }
    void played() { effect(playedSound); }

    private void effect(int sound) {
        if (foreground && sounds != null && preferences.getBoolean("effects", false)) {
            sounds.play(sound, 0.30f, 0.30f, 1, 0, 1f);
        }
    }

    void close() {
        abandonFocus();
        if (music != null) music.release();
        if (sounds != null) sounds.release();
    }
}
