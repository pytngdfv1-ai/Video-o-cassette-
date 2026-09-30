package com.mixcasete.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.KeyEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * Servicio de reproducción en primer plano.
 *
 * Usa android.media.session.MediaSession (nativo de Android, sin dependencias
 * extra) para que:
 *  - El sistema reconozca esto como reproducción de música "legítima" en curso
 *    (mejora cómo Doze/el ahorro de batería de fabricantes trata al servicio,
 *    sin tener que pedir permisos especiales al usuario).
 *  - Aparezcan controles en la PANTALLA DE BLOQUEO y en auriculares/Bluetooth.
 *  - Los botones físicos de reproducir/pausar (auriculares, etc.) funcionen.
 */
public class PlaybackService extends Service implements MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {

    public static final String CHANNEL = "mixcasete_play";
    public static final String EXTRA_CMD = "cmd";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ARTIST = "artist";
    public static final String EXTRA_SEEK = "seek";

    private PowerManager.WakeLock wl;
    private WifiManager.WifiLock wifiLock;
    private boolean isBridgeMode = false;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private MediaPlayer player;
    private MediaSession mediaSession;
    private String currentTitle = "Mix.Casete";
    private String currentArtist = "";
    private boolean prepared = false;
    private AudioBufferManager bufferManager;

    private static volatile PlaybackService sInstance;

    public static PlaybackService getInstance() {
        return sInstance;
    }

    public int getPlayerPosition() {
        try {
            if (player != null && prepared) return player.getCurrentPosition();
        } catch (Exception ignored) {}
        return -1;
    }

    public int getPlayerDuration() {
        try {
            if (player != null && prepared) return player.getDuration();
        } catch (Exception ignored) {}
        return -1;
    }

    public static void start(android.content.Context c, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(android.content.Context c) {
        c.stopService(new Intent(c, PlaybackService.class));
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        bufferManager = new AudioBufferManager(this);
        crearCanal();
        setupMediaSession();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, buildNotif("Mix.Casete", false),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(1, buildNotif("Mix.Casete", false));
        }
    }

    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "MixCaseteSession");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { doCmd("play"); }
            @Override public void onPause() { doCmd("pause"); }
            @Override public void onStop() { doCmd("stop"); }
            @Override public void onSkipToNext() { notifyJs("btn_next"); }
            @Override public void onSkipToPrevious() { notifyJs("btn_prev"); }
            @Override public void onSeekTo(long pos) {
                if (player != null && prepared) player.seekTo((int) pos);
            }
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                Object evObj = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (evObj instanceof KeyEvent) {
                    KeyEvent ev = (KeyEvent) evObj;
                    if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                        int code = ev.getKeyCode();
                        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                            if (player != null && player.isPlaying()) doCmd("pause"); else doCmd("play");
                            return true;
                        } else if (code == KeyEvent.KEYCODE_MEDIA_PLAY) { doCmd("play"); return true; }
                        else if (code == KeyEvent.KEYCODE_MEDIA_PAUSE) { doCmd("pause"); return true; }
                        else if (code == KeyEvent.KEYCODE_MEDIA_STOP) { doCmd("stop"); return true; }
                        else if (code == KeyEvent.KEYCODE_MEDIA_NEXT
                                || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                            notifyJs(code == KeyEvent.KEYCODE_MEDIA_NEXT ? "btn_next" : "btn_prev");
                            return true;
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }
        });
        mediaSession.setActive(true);
    }

    /** Ejecuta el mismo camino que un comando llegado por Intent, para que
     *  los botones de auriculares/pantalla de bloqueo hagan lo mismo que los
     *  botones dentro de la app. */
    private void doCmd(String cmd) {
        Intent i = new Intent(this, PlaybackService.class);
        i.putExtra(EXTRA_CMD, cmd);
        onStartCommand(i, 0, 0);
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
                if (title != null) currentTitle = title;
                currentArtist = artist != null ? artist : "";
                startPlayback(url);
                break;
            case "bridge_play":
                isBridgeMode = true;
                releasePlayer();
                acquireLocks();
                requestAudioFocus();
                String bTitle = intent.getStringExtra(EXTRA_TITLE);
                String bArtist = intent.getStringExtra(EXTRA_ARTIST);
                if (bTitle != null && !bTitle.isEmpty()) currentTitle = bTitle;
                currentArtist = bArtist != null ? bArtist : "";
                updateMetadata();
                updatePlaybackState(true);
                updateNotif(true);
                break;
            case "play":
                acquireLocks();
                requestAudioFocus();
                if (player != null && prepared) {
                    player.start();
                    updatePlaybackState(true);
                    updateNotif(true);
                    notifyJs("playing");
                } else if (isBridgeMode) {
                    updatePlaybackState(true);
                    updateNotif(true);
                    notifyJs("btn_play");
                }
                break;
            case "pause":
                if (player != null && prepared) {
                    player.pause();
                    updatePlaybackState(false);
                    updateNotif(false);
                    notifyJs("paused");
                } else if (isBridgeMode) {
                    updatePlaybackState(false);
                    updateNotif(false);
                    notifyJs("btn_pause");
                }
                break;
            case "stop":
                stopPlayback();
                stopSelf();
                break;
            case "seek":
                int sec = intent.getIntExtra(EXTRA_SEEK, 0);
                if (player != null && prepared) {
                    player.seekTo(sec * 1000);
                }
                break;
        }
        return START_STICKY;
    }

    private void startPlayback(String url) {
        isBridgeMode = false;
        releasePlayer();
        requestAudioFocus();
        acquireLocks();
        updateMetadata();

        try {
            // Buffer y caché gestionado para reproducción sin entrecortes y consumo de memoria controlado
            String playSource = url;
            if (url.startsWith("http://") || url.startsWith("https://")) {
                if (bufferManager != null) {
                    java.io.File cached = bufferManager.getCompletedBufferFile(url);
                    if (cached != null) {
                        playSource = cached.getAbsolutePath();
                    } else {
                        bufferManager.startBuffering(url);
                    }
                }
            }

            player = new MediaPlayer();
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());

            if (playSource.startsWith("http://") || playSource.startsWith("https://")) {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 11; Pixel 4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                // NOTA: No enviar Referer a googlevideo porque activa limitación de tasa (rate-limit / throttling)
                player.setDataSource(this, Uri.parse(playSource), headers);
            } else if (playSource.startsWith("file://")) {
                player.setDataSource(this, Uri.parse(playSource));
            } else {
                player.setDataSource(playSource);
            }

            player.setOnBufferingUpdateListener((mp, percent) -> {
                // Progreso de buffer nativo
            });
            player.setOnInfoListener((mp, what, extra) -> true);
            player.setOnPreparedListener(this);
            player.setOnCompletionListener(this);
            player.setOnErrorListener(this);
            player.prepareAsync();
        } catch (Exception e) {
            handleDecodeError(1, -1);
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        prepared = true;
        mp.start();
        updatePlaybackState(true);
        updateNotif(true);
        notifyJs("playing");
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        updatePlaybackState(false);
        notifyJs("ended");
        updateNotif(false);
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        handleDecodeError(what, extra);
        return true;
    }

    private void handleDecodeError(int what, int extra) {
        String cause = "Error de decodificación";
        if (extra == MediaPlayer.MEDIA_ERROR_IO) {
            cause = "Fallo de red o conexión";
        } else if (extra == MediaPlayer.MEDIA_ERROR_MALFORMED) {
            cause = "Stream corrupto o malformado";
        } else if (extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED) {
            cause = "Códec de audio no compatible";
        } else if (extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT) {
            cause = "Tiempo de espera agotado";
        } else if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) {
            cause = "Servidor multimedia desconectado";
        }

        final String toastMsg = "⚠ Problema al decodificar audio: " + cause + ". Pasando a la siguiente pista...";
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                android.widget.Toast.makeText(getApplicationContext(), toastMsg, android.widget.Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {}
        });

        notifyJs("error_decode:" + extra);
        updateNotif(false);
    }

    private void stopPlayback() {
        isBridgeMode = false;
        if (bufferManager != null) {
            bufferManager.cancelBuffering();
        }
        releasePlayer();
        releaseLocks();
        releaseAudioFocus();
        if (mediaSession != null) mediaSession.setActive(false);
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
                player.release();
            } catch (Exception e) {}
            player = null;
            prepared = false;
        }
    }

    private void requestAudioFocus() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focus -> {
                        if (focus == AudioManager.AUDIOFOCUS_LOSS && player != null && player.isPlaying()) {
                            player.pause();
                            updatePlaybackState(false);
                            updateNotif(false);
                            notifyJs("paused");
                        }
                    })
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void releaseAudioFocus() {
        if (audioManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } else {
                audioManager.abandonAudioFocus(null);
            }
        }
    }

    private void acquireLocks() {
        acquireWakeLock();
        acquireWifiLock();
    }

    private void releaseLocks() {
        releaseWakeLock();
        releaseWifiLock();
    }

    private void acquireWakeLock() {
        if (wl == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mixcasete:play");
                wl.setReferenceCounted(false);
            }
        }
        if (wl != null && !wl.isHeld()) {
            try {
                wl.acquire();
            } catch (Exception ignored) {}
        }
    }

    private void releaseWakeLock() {
        if (wl != null && wl.isHeld()) {
            try { wl.release(); } catch (Exception ignored) {}
        }
    }

    private void acquireWifiLock() {
        if (wifiLock == null) {
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null) {
                    int mode = (Build.VERSION.SDK_INT >= 29)
                            ? WifiManager.WIFI_MODE_FULL
                            : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                    wifiLock = wm.createWifiLock(mode, "mixcasete:wifi");
                    wifiLock.setReferenceCounted(false);
                }
            } catch (Exception ignored) {}
        }
        if (wifiLock != null && !wifiLock.isHeld()) {
            try { wifiLock.acquire(); } catch (Exception ignored) {}
        }
    }

    private void releaseWifiLock() {
        if (wifiLock != null && wifiLock.isHeld()) {
            try { wifiLock.release(); } catch (Exception ignored) {}
        }
    }

    /** Metadata (título/artista) que ve el sistema: pantalla de bloqueo,
     *  reloj/auto, auriculares con pantalla, etc. */
    private void updateMetadata() {
        if (mediaSession == null) return;
        MediaMetadata md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST,
                        currentArtist == null || currentArtist.isEmpty() ? "Mix.Casete" : currentArtist)
                .build();
        mediaSession.setMetadata(md);
    }

    private void updatePlaybackState(boolean playing) {
        if (mediaSession == null) return;
        long pos = 0;
        try { if (player != null && prepared) pos = player.getCurrentPosition(); } catch (Exception e) {}
        PlaybackState st = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        pos, playing ? 1f : 0f)
                .build();
        mediaSession.setPlaybackState(st);
        mediaSession.setActive(true);
    }

    private Notification buildNotif(String title, boolean playing) {
        Intent pause = new Intent(this, PlaybackService.class).putExtra(EXTRA_CMD, playing ? "pause" : "play");
        Intent stop = new Intent(this, PlaybackService.class).putExtra(EXTRA_CMD, "stop");
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pP = PendingIntent.getService(this, 1, pause, fl);
        PendingIntent pS = PendingIntent.getService(this, 3, stop, fl);

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(this, 0, open, fl);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText(playing ? "▶ Reproduciendo" : "❚❚ En pausa")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pOpen)
                .setOngoing(playing)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(playing
                        ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pausa" : "Seguir", pP)
                .addAction(android.R.drawable.ic_delete, "Parar", pS);

        if (mediaSession != null) {
            Notification.MediaStyle style = new Notification.MediaStyle();
            style.setMediaSession(mediaSession.getSessionToken());
            style.setShowActionsInCompactView(0, 1);
            b.setStyle(style);
        }
        return b.build();
    }

    private void updateNotif(boolean playing) {
        try {
            Notification notif = buildNotif(currentTitle, playing);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(1, notif);
            }
        } catch (Exception e) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(1, buildNotif(currentTitle, playing));
            } catch (Exception ignored) {}
        }
    }

    private void crearCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Reproducción", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Audio de Mix.Casete");
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
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
        stopPlayback();
        if (mediaSession != null) mediaSession.release();
        super.onDestroy();
    }

    /**
     * Gestor de buffer y almacenamiento en caché para streams de audio.
     * Descarga de forma progresiva en segundo plano con control de memoria (límite LRU 60 MB),
     * previniendo problemas de falta de memoria (OOM) y entrecortes en el audio.
     */
    public static class AudioBufferManager {
        private static final long MAX_CACHE_BYTES = 60 * 1024 * 1024L; // 60 MB máximo de buffer en disco
        private final java.io.File bufferDir;
        private Thread currentBufferThread;
        private volatile boolean cancelCurrentBuffer = false;

        public AudioBufferManager(android.content.Context context) {
            bufferDir = new java.io.File(context.getCacheDir(), "audio_buffer");
            if (!bufferDir.exists()) bufferDir.mkdirs();
        }

        public synchronized java.io.File getCompletedBufferFile(String url) {
            if (url == null) return null;
            String key = hashKey(url);
            java.io.File completed = new java.io.File(bufferDir, key + ".m4a");
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
            final java.io.File target = new java.io.File(bufferDir, key + ".m4a");
            if (target.exists() && target.length() > 65536) return;

            currentBufferThread = new Thread(() -> {
                java.net.HttpURLConnection conn = null;
                java.io.InputStream is = null;
                java.io.FileOutputStream fos = null;
                java.io.File temp = new java.io.File(bufferDir, key + ".part");
                try {
                    conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(35000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; Pixel 4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                    int code = conn.getResponseCode();
                    if (code == 200 || code == 206) {
                        is = conn.getInputStream();
                        fos = new java.io.FileOutputStream(temp);
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
                java.io.File[] files = bufferDir.listFiles((d, name) -> name.endsWith(".m4a"));
                if (files == null || files.length == 0) return;
                long total = 0;
                for (java.io.File f : files) total += f.length();
                if (total > MAX_CACHE_BYTES) {
                    java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                    for (java.io.File f : files) {
                        if (total <= MAX_CACHE_BYTES * 0.7) break;
                        long len = f.length();
                        if (f.delete()) total -= len;
                    }
                }
            } catch (Exception ignored) {}
        }

        private String hashKey(String url) {
            return "st_" + Integer.toHexString(url.hashCode());
        }
    }
}
