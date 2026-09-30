package com.mixcasete.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    public static WeakReference<MainActivity> self;
    private static final int REQUEST_CODE_PICK_AUDIO = 701;
    private static final int REQUEST_CODE_PERMISSIONS = 501;

    private WebView wv;
    private FrameLayout rootLayout;
    private TvPresentation tvPresentation = null;
    private DisplayManager displayManager = null;
    private String currentPlayingId = null;
    private Dialog loginDialog = null;

    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            checkPresentationDisplays();
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            checkPresentationDisplays();
        }

        @Override
        public void onDisplayChanged(int displayId) {
            checkPresentationDisplays();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        self = new WeakReference<>(this);

        initNewPipe();
        applyImmersiveMode();

        try {
            PlaybackService.stop(this);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancelAll();
        } catch (Exception ignored) {}

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(0xFF141210);

        wv = new WebView(this);
        configWebSettings(wv.getSettings());
        wv.setWebViewClient(new WebViewClient());
        wv.setWebChromeClient(new WebChromeClient());
        wv.addJavascriptInterface(new Bridge(), "Android");
        rootLayout.addView(wv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(rootLayout);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        requestAppPermissions();

        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager != null) {
            displayManager.registerDisplayListener(displayListener, null);
            checkPresentationDisplays();
        }

        wv.loadUrl("file:///android_asset/index.html");
    }

    private void applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyImmersiveMode();
        if (wv != null) {
            String orientation = (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) ? "landscape" : "portrait";
            wv.evaluateJavascript("window.onOrientationChanged && window.onOrientationChanged('" + orientation + "')", null);
        }
    }

    private void initNewPipe() {
        try {
            NewPipe.init(new Downloader() {
                @Override
                public Response execute(Request request) throws Exception {
                    URL url = new URL(request.url());
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod(request.httpMethod());
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("User-Agent", UA);

                    for (Map.Entry<String, List<String>> pair : request.headers().entrySet()) {
                        for (String val : pair.getValue()) {
                            conn.setRequestProperty(pair.getKey(), val);
                        }
                    }

                    int code = conn.getResponseCode();
                    InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
                    byte[] data = new byte[0];
                    if (is != null) {
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                        data = bos.toByteArray();
                        is.close();
                    }
                    return new Response(code, conn.getResponseMessage(), conn.getHeaderFields(), new String(data, StandardCharsets.UTF_8), null);
                }
            });
        } catch (Exception ignored) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configWebSettings(WebSettings s) {
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString(UA);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
    }

    private void requestAppPermissions() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(android.Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (Build.VERSION.SDK_INT <= 28) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if (!perms.isEmpty()) {
            requestPermissions(perms.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
        }
    }

    private void checkPresentationDisplays() {
        runOnUiThread(() -> {
            try {
                if (displayManager == null) return;
                Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
                if (displays != null && displays.length > 0) {
                    Display display = displays[0];
                    if (tvPresentation == null || tvPresentation.getDisplay().getDisplayId() != display.getDisplayId()) {
                        if (tvPresentation != null) {
                            try { tvPresentation.dismiss(); } catch (Exception ignored) {}
                        }
                        tvPresentation = new TvPresentation(MainActivity.this, display);
                        try {
                            tvPresentation.show();
                            // Mantener pantalla encendida mientras se proyecta
                            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                            // El video y audio salen por la TV; pausar reproductor nativo del móvil para no duplicar audio
                            try {
                                Intent si = new Intent(MainActivity.this, PlaybackService.class);
                                si.putExtra(PlaybackService.EXTRA_CMD, "pause");
                                PlaybackService.start(MainActivity.this, si);
                            } catch (Exception ignored) {}

                            if (currentPlayingId != null) {
                                int curSec = 0;
                                PlaybackService ps = PlaybackService.getInstance();
                                if (ps != null) {
                                    int posMs = ps.getPlayerPosition();
                                    if (posMs > 0) curSec = posMs / 1000;
                                }
                                tvPresentation.loadTvVideo(currentPlayingId, curSec);
                            }

                            if (wv != null) {
                                wv.evaluateJavascript("window.onTvConnected && window.onTvConnected(true)", null);
                            }
                        } catch (Exception ignored) {}
                    }
                } else {
                    if (tvPresentation != null) {
                        try { tvPresentation.dismiss(); } catch (Exception ignored) {}
                        tvPresentation = null;
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                        // Al desconectar de la TV, reactivar el audio directamente en el teléfono
                        try {
                            Intent si = new Intent(MainActivity.this, PlaybackService.class);
                            si.putExtra(PlaybackService.EXTRA_CMD, "play");
                            PlaybackService.start(MainActivity.this, si);
                        } catch (Exception ignored) {}

                        if (wv != null) {
                            wv.evaluateJavascript("window.onTvConnected && window.onTvConnected(false)", null);
                        }
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    public void onPlayerEvent(String event) {
        runOnUiThread(() -> {
            if (wv != null) {
                wv.evaluateJavascript("window.onNativePlayerEvent && window.onNativePlayerEvent('" + event + "')", null);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_AUDIO && resultCode == RESULT_OK && data != null) {
            handleLocalAudioResult(data);
        }
    }

    private void handleLocalAudioResult(Intent data) {
        try {
            JSONArray arr = new JSONArray();
            if (data.getClipData() != null) {
                ClipData clip = data.getClipData();
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri uri = clip.getItemAt(i).getUri();
                    JSONObject track = resolveAudioUriMeta(uri);
                    if (track != null) arr.put(track);
                }
            } else if (data.getData() != null) {
                Uri uri = data.getData();
                JSONObject track = resolveAudioUriMeta(uri);
                if (track != null) arr.put(track);
            }

            if (wv != null && arr.length() > 0) {
                wv.evaluateJavascript("window.onLocalAudioLoaded && window.onLocalAudioLoaded(" + arr.toString() + ")", null);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error al cargar archivos locales: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private JSONObject resolveAudioUriMeta(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
            }

            String name = "Pista Local";
            long size = 0;
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) name = cursor.getString(nameIdx);
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx);
                }
                cursor.close();
            }

            JSONObject obj = new JSONObject();
            obj.put("id", "local_" + Math.abs(uri.hashCode()));
            obj.put("title", name.replaceAll("\\.[a-zA-Z0-9]+$", ""));
            obj.put("author", "Archivo del Dispositivo");
            obj.put("url", uri.toString());
            obj.put("isLocal", true);
            obj.put("size", size);
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(displayListener); } catch (Exception ignored) {}
        }
        if (tvPresentation != null) {
            try { tvPresentation.dismiss(); } catch (Exception ignored) {}
            tvPresentation = null;
        }
        if (loginDialog != null && loginDialog.isShowing()) {
            try { loginDialog.dismiss(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    /* ============================================================
       PUENTE JAVASCRIPT ("Android")
       ============================================================ */
    public class Bridge {

        @JavascriptInterface
        public boolean isNative() {
            return true;
        }

        @JavascriptInterface
        public void nativePlay(final String url, final String title, final String artist) {
            try {
                Intent si = new Intent(MainActivity.this, PlaybackService.class);
                si.putExtra(PlaybackService.EXTRA_CMD, "play_url");
                si.putExtra(PlaybackService.EXTRA_URL, url);
                si.putExtra(PlaybackService.EXTRA_TITLE, title != null ? title : "Mix.Casete");
                si.putExtra(PlaybackService.EXTRA_ARTIST, artist != null ? artist : "");
                PlaybackService.start(MainActivity.this, si);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void nativePause() {
            try {
                Intent si = new Intent(MainActivity.this, PlaybackService.class);
                si.putExtra(PlaybackService.EXTRA_CMD, "pause");
                PlaybackService.start(MainActivity.this, si);
            } catch (Exception ignored) {}
            if (tvPresentation != null) {
                runOnUiThread(() -> tvPresentation.pauseVideo());
            }
        }

        @JavascriptInterface
        public void nativeResume() {
            try {
                Intent si = new Intent(MainActivity.this, PlaybackService.class);
                si.putExtra(PlaybackService.EXTRA_CMD, "play");
                PlaybackService.start(MainActivity.this, si);
            } catch (Exception ignored) {}
            if (tvPresentation != null) {
                runOnUiThread(() -> tvPresentation.resumeVideo());
            }
        }

        @JavascriptInterface
        public void nativeStop() {
            try {
                Intent si = new Intent(MainActivity.this, PlaybackService.class);
                si.putExtra(PlaybackService.EXTRA_CMD, "stop");
                PlaybackService.start(MainActivity.this, si);
            } catch (Exception ignored) {}
            if (tvPresentation != null) {
                runOnUiThread(() -> tvPresentation.showBlankScreen());
            }
        }

        @JavascriptInterface
        public void nativeSeek(int sec) {
            try {
                Intent si = new Intent(MainActivity.this, PlaybackService.class);
                si.putExtra(PlaybackService.EXTRA_SEEK, sec);
                si.putExtra(PlaybackService.EXTRA_CMD, "seek");
                PlaybackService.start(MainActivity.this, si);
            } catch (Exception ignored) {}
            if (tvPresentation != null) {
                runOnUiThread(() -> tvPresentation.seekVideo(sec));
            }
        }

        @JavascriptInterface
        public int getNativePositionMs() {
            PlaybackService ps = PlaybackService.getInstance();
            return (ps != null) ? ps.getPlayerPosition() : -1;
        }

        @JavascriptInterface
        public int getNativeDurationMs() {
            PlaybackService ps = PlaybackService.getInstance();
            return (ps != null) ? ps.getPlayerDuration() : -1;
        }

        @JavascriptInterface
        public void showVideoOverlay(final String id, final int startSec) {
            runOnUiThread(() -> {
                currentPlayingId = id;
                checkPresentationDisplays();
                if (tvPresentation != null) {
                    tvPresentation.loadTvVideo(id, startSec);
                    try {
                        Intent si = new Intent(MainActivity.this, PlaybackService.class);
                        si.putExtra(PlaybackService.EXTRA_CMD, "pause");
                        PlaybackService.start(MainActivity.this, si);
                    } catch (Exception ignored) {}
                    return;
                }
                // Si no hay pantalla secundaria física/Presentation conectada, abrir el selector del sistema Cast
                try {
                    startActivity(new Intent("android.settings.CAST_SETTINGS"));
                } catch (Exception ignored) {
                    Toast.makeText(MainActivity.this, "Conecta una pantalla externa o activa Transmitir en Ajustes", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void hideVideoOverlay() {
            runOnUiThread(() -> {
                if (tvPresentation != null) {
                    tvPresentation.showBlankScreen();
                }
            });
        }

        @JavascriptInterface
        public void syncTvDrift(final int currentSec) {
            if (tvPresentation != null) {
                runOnUiThread(() -> tvPresentation.syncDrift(currentSec));
            }
        }

        @JavascriptInterface
        public boolean isTvConnected() {
            return (tvPresentation != null);
        }

        @JavascriptInterface
        public void openDocumentPicker() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("audio/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(intent, REQUEST_CODE_PICK_AUDIO);
                } catch (Exception e) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.setType("audio/*");
                        startActivityForResult(intent, REQUEST_CODE_PICK_AUDIO);
                    } catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void openLoginModal() {
            runOnUiThread(() -> {
                try {
                    if (loginDialog != null && loginDialog.isShowing()) return;
                    loginDialog = new Dialog(MainActivity.this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);

                    LinearLayout layout = new LinearLayout(MainActivity.this);
                    layout.setOrientation(LinearLayout.VERTICAL);
                    layout.setBackgroundColor(0xFF141210);

                    RelativeLayout topBar = new RelativeLayout(MainActivity.this);
                    topBar.setBackgroundColor(0xFF241f19);
                    topBar.setPadding(24, 24, 24, 24);

                    TextView title = new TextView(MainActivity.this);
                    title.setText("Iniciar sesión en YouTube (Opcional)");
                    title.setTextColor(0xFFFFFFFF);
                    title.setTextSize(15);
                    RelativeLayout.LayoutParams lpTitle = new RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
                    lpTitle.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
                    lpTitle.addRule(RelativeLayout.CENTER_VERTICAL);
                    topBar.addView(title, lpTitle);

                    TextView closeBtn = new TextView(MainActivity.this);
                    closeBtn.setText("✕ CERRAR");
                    closeBtn.setTextColor(0xFFF5A623);
                    closeBtn.setTextSize(14);
                    closeBtn.setPadding(16, 8, 16, 8);
                    RelativeLayout.LayoutParams lpClose = new RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
                    lpClose.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
                    lpClose.addRule(RelativeLayout.CENTER_VERTICAL);
                    closeBtn.setOnClickListener(v -> {
                        CookieManager.getInstance().flush();
                        loginDialog.dismiss();
                        if (wv != null) {
                            wv.evaluateJavascript("window.onLoginComplete && window.onLoginComplete()", null);
                        }
                    });
                    topBar.addView(closeBtn, lpClose);
                    layout.addView(topBar);

                    WebView loginWv = new WebView(MainActivity.this);
                    WebSettings s = loginWv.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    s.setDatabaseEnabled(true);
                    s.setUserAgentString(UA);

                    CookieManager cm = CookieManager.getInstance();
                    cm.setAcceptCookie(true);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        cm.setAcceptThirdPartyCookies(loginWv, true);
                    }

                    loginWv.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String url) {
                            cm.flush();
                        }
                    });

                    loginWv.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fm.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue");

                    layout.addView(loginWv, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

                    loginDialog.setContentView(layout);
                    loginDialog.show();
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void fetchNewPipeStream(final String youtubeId) {
            new Thread(() -> {
                try {
                    StreamInfo streamInfo = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + youtubeId);
                    List<AudioStream> audioStreams = streamInfo.getAudioStreams();
                    String bestAudioUrl = null;
                    if (audioStreams != null && !audioStreams.isEmpty()) {
                        bestAudioUrl = audioStreams.get(0).getContent();
                    }
                    final String urlResult = bestAudioUrl;
                    runOnUiThread(() -> {
                        if (wv != null) {
                            if (urlResult != null && !urlResult.isEmpty()) {
                                wv.evaluateJavascript("window.onNewPipeStreamLoaded && window.onNewPipeStreamLoaded('" + youtubeId + "', '" + urlResult + "')", null);
                            } else {
                                wv.evaluateJavascript("window.onNewPipeStreamError && window.onNewPipeStreamError('" + youtubeId + "', 'No audio streams found')", null);
                            }
                        }
                    });
                } catch (Exception e) {
                    final String err = e.getMessage() != null ? e.getMessage().replace("'", "\\'") : "Extraction error";
                    runOnUiThread(() -> {
                        if (wv != null) {
                            wv.evaluateJavascript("window.onNewPipeStreamError && window.onNewPipeStreamError('" + youtubeId + "', '" + err + "')", null);
                        }
                    });
                }
            }).start();
        }

        @JavascriptInterface
        public void setOrientation(final String mode) {
            runOnUiThread(() -> {
                try {
                    if ("landscape".equalsIgnoreCase(mode)) {
                        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    } else if ("portrait".equalsIgnoreCase(mode)) {
                        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
                    } else {
                        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    }
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void setKeepScreenOn(final boolean keepOn) {
            runOnUiThread(() -> {
                try {
                    if (keepOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void logDebug(final String msg) {
            runOnUiThread(() -> {
                if (wv != null) {
                    wv.evaluateJavascript("window.addDebugLog && window.addDebugLog('" + msg.replace("'", "\\'") + "')", null);
                }
            });
        }

        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(() -> {
                try {
                    PlaybackService.stop(MainActivity.this);
                } catch (Exception ignored) {}
                finish();
            });
        }
    }
}
