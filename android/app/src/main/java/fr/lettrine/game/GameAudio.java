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
import android.util.SparseBooleanArray;

final class GameAudio {
    private static final int DEFAULT_VOLUME = 50;
    private static final int[] TRACKS = {R.raw.ambient, R.raw.atelier};
    private final Context context;
    private final SharedPreferences preferences;
    private final AudioManager manager;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    private final AudioFocusRequest focus;
    private final SparseBooleanArray loadedSounds = new SparseBooleanArray();
    private MediaPlayer music;
    private SoundPool sounds;
    private int tileSound;
    private int playedSound;
    private int celebrationSound;
    private int blockedSound;
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
        if (value) prepareEffects();
        else releaseEffects();
    }

    int musicTrack() {
        return Math.max(0, Math.min(TRACKS.length - 1, preferences.getInt("music_track", 0)));
    }

    void setMusicTrack(int track) {
        if (track < 0 || track >= TRACKS.length) return;
        preferences.edit().putInt("music_track", track).apply();
        refresh();
    }

    int musicVolume() { return volume("music_volume"); }

    void setMusicVolume(int percent) {
        preferences.edit().putInt("music_volume", clampVolume(percent)).apply();
        refresh();
    }

    int effectsVolume() { return volume("effects_volume"); }

    void setEffectsVolume(int percent) {
        preferences.edit().putInt("effects_volume", clampVolume(percent)).apply();
    }

    private int volume(String setting) {
        return clampVolume(preferences.getInt(setting, DEFAULT_VOLUME));
    }

    private static int clampVolume(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    void refresh() {
        if (!foreground || !preferences.getBoolean("music", false) || musicVolume() == 0) {
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
        int track = TRACKS[musicTrack()];
        if (currentTrack != track || music == null) {
            if (music != null) music.release();
            music = MediaPlayer.create(context, track, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE);
            currentTrack = track;
            if (music != null) music.setLooping(true);
        }
        if (music != null) {
            adjustVolume();
            if (!music.isPlaying()) music.start();
        }
    }

    void adjustVolume() {
        float volume = musicVolume() / 100f * (ducked ? 0.15f : 1f);
        if (music != null) music.setVolume(volume, volume);
    }

    private void abandonFocus() {
        if (requested) manager.abandonAudioFocusRequest(focus);
        requested = false;
        focused = false;
        ducked = false;
    }

    void prepareEffects() {
        if (!foreground || !preferences.getBoolean("effects", false)) {
            releaseEffects();
            return;
        }
        if (sounds != null) return;
        AudioAttributes effects = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        sounds = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(effects).build();
        sounds.setOnLoadCompleteListener((pool, sound, status) -> {
            if (pool == sounds && status == 0) loadedSounds.put(sound, true);
        });
        tileSound = sounds.load(context, R.raw.tile_sound, 1);
        playedSound = sounds.load(context, R.raw.played_sound, 1);
        celebrationSound = sounds.load(context, R.raw.celebration_sound, 1);
        blockedSound = sounds.load(context, R.raw.blocked_sound, 1);
    }

    void tile() { effect(tileSound, 1); }
    void played() { effect(playedSound, 2); }
    void celebrate() { effect(celebrationSound, 3); }
    void reject() { effect(blockedSound, 2); }
    void previewEffect() { played(); }

    private void effect(int sound, int priority) {
        float gain = effectsVolume() * 0.006f;
        if (foreground && gain > 0 && sounds != null && loadedSounds.get(sound)
                && preferences.getBoolean("effects", false)) {
            sounds.play(sound, gain, gain, priority, 0, 1f);
        }
    }

    private void releaseEffects() {
        if (sounds != null) {
            sounds.release();
            sounds = null;
            loadedSounds.clear();
        }
    }

    void close() {
        foreground = false;
        abandonFocus();
        if (music != null) {
            music.release();
            music = null;
        }
        releaseEffects();
    }
}
