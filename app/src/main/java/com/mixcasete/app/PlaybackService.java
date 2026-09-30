package com.mixcasete.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Servicio en primer plano (foreground) para reproducción de audio ininterrumpida.
 * Basado en AndroidX Media3 ExoPlayer, con MediaSession nativo, WakeLock determinista,
 * soporte de auriculares desconectados (Becoming Noisy) y caché de streaming.
 */
public class PlaybackService extends Service implements Player.Listener {

    public static final String CHANNEL = "mc_playback_channel";
    public static final String EXTRA_CMD = "cmd";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ARTIST = "artist";
    public static final String EXTRA_SEEK = "seek";

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private ExoPlayer player;
    private MediaSession mediaSession;
    private AudioBufferManager bufferManager;
    private boolean isBridgeMode = false;

    private String currentTitle = "Mix.Casete";
    private String currentArtist = "";
    private String currentUrl = null;
    private boolean becomingNoisyRegistered = false;

    private static volatile PlaybackService sInstance;

    public static PlaybackService getInstance() {
        return sInstance;
    }

    public static void start(Context c, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, PlaybackService.class));
    }

    private final BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                // Auriculares desconectados: pausar inmediatamente para evitar altavoz accidental
                handlePauseCommand();
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        bufferManager = new AudioBufferManager(this);

        createNotificationChannel();
        setupMediaSession();
        setupExoPlayer();
        registerNoisyReceiver();

        Notification initialNotif = buildNotification(currentTitle, false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(1, initialNotif);
        }
    }

    private void setupExoPlayer() {
        androidx.media3.common.AudioAttributes audioAttrs = new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();

        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(audioAttrs, true /* gestionar AudioFocus automáticamente */)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();

        player.addListener(this);
    }

    private void registerNoisyReceiver() {
        if (!becomingNoisyRegistered) {
            try {
                registerReceiver(becomingNoisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
                becomingNoisyRegistered = true;
            } catch (Exception ignored) {}
        }
    }

    private void unregisterNoisyReceiver() {
        if (becomingNoisyRegistered) {
            try {
                unregisterReceiver(becomingNoisyReceiver);
            } catch (Exception ignored) {}
            becomingNoisyRegistered = false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String cmd = intent.getStringExtra(EXTRA_CMD);
        if (cmd == null) return START_STICKY;

        switch (cmd) {
            case "play_url":
                isBridgeMode = false;
                String url = intent.getStringExtra(EXTRA_URL);
                String title = intent.getStringExtra(EXTRA_TITLE);
                String artist = intent.getStringExtra(EXTRA_ARTIST);
                if (title != null && !title.isEmpty()) currentTitle = title;
                currentArtist = (artist != null) ? artist : "";
                startPlayback(url);
                break;

            case "bridge_play":
                isBridgeMode = true;
                if (player != null) player.pause();
                acquireLocks();
                String bTitle = intent.getStringExtra(EXTRA_TITLE);
                String bArtist = intent.getStringExtra(EXTRA_ARTIST);
                if (bTitle != null && !bTitle.isEmpty()) currentTitle = bTitle;
                currentArtist = (bArtist != null) ? bArtist : "";
                updateMetadata();
                updatePlaybackState(true);
                updateNotification(true);
                break;

            case "play":
                handlePlayCommand();
                break;

            case "pause":
                handlePauseCommand();
                break;

            case "stop":
                stopPlayback();
                stopSelf();
                break;

            case "seek":
                int sec = intent.getIntExtra(EXTRA_SEEK, 0);
                if (player != null) {
                    player.seekTo(sec * 1000L);
                }
                break;

            case "preload":
                String pUrl = intent.getStringExtra(EXTRA_URL);
                if (pUrl != null && bufferManager != null) {
                    bufferManager.startBuffering(pUrl);
                }
                break;
        }

        return START_STICKY;
    }

    private void handlePlayCommand() {
        acquireLocks();
        if (player != null && !isBridgeMode) {
            player.play();
            updatePlaybackState(true);
            updateNotification(true);
        } else if (isBridgeMode) {
            updatePlaybackState(true);
            updateNotification(true);
            notifyJs("btn_play");
        }
    }

    private void handlePauseCommand() {
        if (player != null && !isBridgeMode) {
            player.pause();
            updatePlaybackState(false);
            updateNotification(false);
            notifyJs("paused");
        } else if (isBridgeMode) {
            updatePlaybackState(false);
            updateNotification(false);
            notifyJs("btn_pause");
        }
    }

    private void startPlayback(String url) {
        if (url == null || url.isEmpty()) return;
        currentUrl = url;
        isBridgeMode = false;
        acquireLocks();
        updateMetadata();

        try {
            String playSource = url;

            // Comprobar si ya existe en la caché local
            if (bufferManager != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                File cached = bufferManager.getCompletedBufferFile(url);
                if (cached != null) {
                    playSource = Uri.fromFile(cached).toString();
                } else {
                    bufferManager.startBuffering(url);
                }
            }

            MediaItem item = MediaItem.fromUri(Uri.parse(playSource));
            player.setMediaItem(item);
            player.prepare();
            player.play();
            updatePlaybackState(true);
            updateNotification(true);
        } catch (Exception e) {
            notifyJs("player_error:" + e.getMessage());
        }
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_READY) {
            updatePlaybackState(player.isPlaying());
            updateNotification(player.isPlaying());
            if (player.isPlaying()) {
                notifyJs("playing");
            }
        } else if (state == Player.STATE_ENDED) {
            updatePlaybackState(false);
            updateNotification(false);
            notifyJs("ended");
        } else if (state == Player.STATE_BUFFERING) {
            notifyJs("buffering");
        }
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        updatePlaybackState(isPlaying);
        updateNotification(isPlaying);
        if (isPlaying) {
            notifyJs("playing");
        } else {
            notifyJs("paused");
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        // En caso de fallo o stream expirado (HTTP 403), notificar inmediatamente a JS para alternar fuente
        notifyJs("stream_expired:" + (error != null ? error.errorCodeName : "unknown"));
    }

    public int getPlayerPosition() {
        try {
            if (player != null) return (int) player.getCurrentPosition();
        } catch (Exception ignored) {}
        return -1;
    }

    public int getPlayerDuration() {
        try {
            if (player != null) {
                long d = player.getDuration();
                if (d > 0 && d != C.TIME_UNSET) return (int) d;
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private void stopPlayback() {
        isBridgeMode = false;
        if (bufferManager != null) bufferManager.cancelBuffering();
        if (player != null) {
            try {
                player.stop();
                player.clearMediaItems();
            } catch (Exception ignored) {}
        }
        releaseLocks();
        if (mediaSession != null) mediaSession.setActive(false);
    }

    private void acquireLocks() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mixcasete:wakelock");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            try { wakeLock.acquire(); } catch (Exception ignored) {}
        }

        if (wifiLock == null) {
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null) {
                    int mode = (Build.VERSION.SDK_INT >= 29)
                            ? WifiManager.WIFI_MODE_FULL
                            : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                    wifiLock = wm.createWifiLock(mode, "mixcasete:wifilock");
                    wifiLock.setReferenceCounted(false);
                }
            } catch (Exception ignored) {}
        }
        if (wifiLock != null && !wifiLock.isHeld()) {
            try { wifiLock.acquire(); } catch (Exception ignored) {}
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            try { wifiLock.release(); } catch (Exception ignored) {}
        }
    }

    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "MixCaseteMediaSession");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                handlePlayCommand();
                notifyJs("btn_play");
            }

            @Override
            public void onPause() {
                handlePauseCommand();
                notifyJs("btn_pause");
            }

            @Override
            public void onSkipToNext() {
                notifyJs("btn_next");
            }

            @Override
            public void onSkipToPrevious() {
                notifyJs("btn_prev");
            }

            @Override
            public void onSeekTo(long pos) {
                if (player != null) player.seekTo(pos);
            }

            @Override
            public void onStop() {
                stopPlayback();
                stopSelf();
                notifyJs("btn_stop");
            }
        });

        mediaSession.setActive(true);
    }

    private void updateMetadata() {
        if (mediaSession == null) return;
        MediaMetadata md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist.isEmpty() ? "Mix.Casete" : currentArtist)
                .build();
        mediaSession.setMetadata(md);
    }

    private void updatePlaybackState(boolean playing) {
        if (mediaSession == null) return;
        long pos = 0;
        try {
            if (player != null) pos = player.getCurrentPosition();
        } catch (Exception ignored) {}

        PlaybackState state = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        pos, playing ? 1.0f : 0.0f)
                .build();

        mediaSession.setPlaybackState(state);
        mediaSession.setActive(true);
    }

    private Notification buildNotification(String title, boolean playing) {
        Intent playPause = new Intent(this, PlaybackService.class)
                .putExtra(EXTRA_CMD, playing ? "pause" : "play");
        Intent next = new Intent(this, MainActivity.class).setAction("ACTION_NEXT");
        Intent prev = new Intent(this, MainActivity.class).setAction("ACTION_PREV");
        Intent stop = new Intent(this, PlaybackService.class).putExtra(EXTRA_CMD, "stop");

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pPlayPause = PendingIntent.getService(this, 1, playPause, flags);
        PendingIntent pStop = PendingIntent.getService(this, 2, stop, flags);

        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pOpenApp = PendingIntent.getActivity(this, 0, openApp, flags);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);

        b.setContentTitle(title)
                .setContentText(playing ? "▶ Reproduciendo (Walkman)" : "❚❚ En pausa")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pOpenApp)
                .setOngoing(playing)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(playing
                        ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pausar" : "Reproducir", pPlayPause)
                .addAction(android.R.drawable.ic_delete, "Detener", pStop);

        if (mediaSession != null) {
            Notification.MediaStyle style = new Notification.MediaStyle();
            style.setMediaSession(mediaSession.getSessionToken());
            style.setShowActionsInCompactView(0, 1);
            b.setStyle(style);
        }

        return b.build();
    }

    private void updateNotification(boolean playing) {
        try {
            Notification notif = buildNotification(currentTitle, playing);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(1, notif);
        } catch (Exception ignored) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Reproductor de Música Walkman",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Controles de reproducción para Mix.Casete");
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void notifyJs(String event) {
        if (MainActivity.self != null && MainActivity.self.get() != null) {
            MainActivity.self.get().onPlayerEvent(event);
        }
    }

    @Override
    public void onDestroy() {
        if (sInstance == this) sInstance = null;
        unregisterNoisyReceiver();
        stopPlayback();
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    /**
     * Gestor de buffer y almacenamiento en caché para streams de audio.
     */
    public static class AudioBufferManager {
        private static final long MAX_CACHE_BYTES = 80 * 1024 * 1024L; // 80 MB
        private final File bufferDir;
        private Thread currentBufferThread;
        private volatile boolean cancelCurrentBuffer = false;

        public AudioBufferManager(Context context) {
            bufferDir = new File(context.getCacheDir(), "audio_buffer");
            if (!bufferDir.exists()) bufferDir.mkdirs();
        }

        public synchronized File getCompletedBufferFile(String url) {
            if (url == null) return null;
            String key = hashKey(url);
            File completed = new File(bufferDir, key + ".m4a");
            if (completed.exists() && completed.length() > 65536) {
                completed.setLastModified(System.currentTimeMillis());
                return completed;
            }
            return null;
        }

        public synchronized void startBuffering(String url) {
            if (url == null || !url.startsWith("http")) return;
            cancelBuffering();

            cancelCurrentBuffer = false;
            final String key = hashKey(url);
            final File target = new File(bufferDir, key + ".m4a");
            if (target.exists() && target.length() > 65536) return;

            currentBufferThread = new Thread(() -> {
                HttpURLConnection conn = null;
                InputStream is = null;
                FileOutputStream fos = null;
                File temp = new File(bufferDir, key + ".part");
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(12000);
                    conn.setReadTimeout(40000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; Pixel 4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                    int code = conn.getResponseCode();
                    if (code == 200 || code == 206) {
                        is = conn.getInputStream();
                        fos = new FileOutputStream(temp);
                        byte[] buf = new byte[32768];
                        int n;
                        while (!cancelCurrentBuffer && (n = is.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                        }
                        fos.flush();
                        fos.close();
                        fos = null;
                        if (!cancelCurrentBuffer && temp.length() > 65536) {
                            if (target.exists()) target.delete();
                            temp.renameTo(target);
                            pruneCacheIfNeeded();
                        } else {
                            temp.delete();
                        }
                    }
                } catch (Exception ignored) {
                    temp.delete();
                } finally {
                    try { if (fos != null) fos.close(); } catch (Exception ignored) {}
                    try { if (is != null) is.close(); } catch (Exception ignored) {}
                    if (conn != null) conn.disconnect();
                }
            });
            currentBufferThread.start();
        }

        public synchronized void cancelBuffering() {
            cancelCurrentBuffer = true;
            if (currentBufferThread != null && currentBufferThread.isAlive()) {
                currentBufferThread.interrupt();
            }
            currentBufferThread = null;
        }

        private void pruneCacheIfNeeded() {
            try {
                File[] files = bufferDir.listFiles((d, name) -> name.endsWith(".m4a"));
                if (files == null || files.length == 0) return;
                long total = 0;
                for (File f : files) total += f.length();
                if (total > MAX_CACHE_BYTES) {
                    Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                    for (File f : files) {
                        if (total <= MAX_CACHE_BYTES * 0.7) break;
                        long len = f.length();
                        if (f.delete()) total -= len;
                    }
                }
            } catch (Exception ignored) {}
        }

        private String hashKey(String url) {
            return "mc_" + Integer.toHexString(url.hashCode());
        }
    }
}
