package com.mixcasete.app;

import android.annotation.SuppressLint;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Presentación en pantalla externa (Smart TV, monitor secundario, HDMI, Wireless Display).
 * Muestra ÚNICAMENTE el video en pantalla completa con letterbox negro y object-fit: contain.
 * Sin controles, sin títulos, sin cursor, sin overlays ni interfaz de casete.
 */
public class TvPresentation extends Presentation {

    private WebView tvWv;
    private FrameLayout rootContainer;
    private String currentVideoId = null;
    private int pendingStartSeconds = 0;
    private boolean isPlaying = false;

    public TvPresentation(Context outerContext, Display display) {
        super(outerContext, display);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Mantener la pantalla externa encendida y en pantalla completa
        if (getWindow() != null) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            );
        }

        rootContainer = new FrameLayout(getContext());
        rootContainer.setBackgroundColor(Color.BLACK);
        rootContainer.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        tvWv = new WebView(getContext());
        tvWv.setBackgroundColor(Color.BLACK);
        tvWv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        WebSettings s = tvWv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        tvWv.setWebChromeClient(new WebChromeClient());
        tvWv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (currentVideoId != null) {
                    renderPlayerHtml(currentVideoId, pendingStartSeconds);
                }
            }
        });

        rootContainer.addView(tvWv);
        setContentView(rootContainer);

        if (currentVideoId != null) {
            loadTvVideo(currentVideoId, pendingStartSeconds);
        } else {
            showBlankScreen();
        }
    }

    public void loadTvVideo(String videoId, int startSeconds) {
        this.currentVideoId = videoId;
        this.pendingStartSeconds = Math.max(0, startSeconds);
        this.isPlaying = true;

        if (tvWv != null) {
            renderPlayerHtml(videoId, pendingStartSeconds);
        }
    }

    private void renderPlayerHtml(String videoId, int startSec) {
        if (tvWv == null) return;
        String html = "<!DOCTYPE html>"
                + "<html>"
                + "<head>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>"
                + "<style>"
                + "* { margin:0; padding:0; box-sizing:border-box; cursor:none !important; }"
                + "html, body { width:100vw; height:100vh; background:#000; overflow:hidden; display:flex; align-items:center; justify-content:center; }"
                + "#ytIframe { width:100vw; height:100vh; max-width:100vw; max-height:100vh; border:none; outline:none; background:#000; object-fit:contain; }"
                + "</style>"
                + "</head>"
                + "<body>"
                + "<div id='playerContainer' style='width:100%;height:100%;display:flex;align-items:center;justify-content:center;background:#000;'>"
                + "<iframe id='ytIframe' "
                + "src='https://www.youtube-nocookie.com/embed/" + videoId + "?autoplay=1&controls=0&playsinline=1&rel=0&modestbranding=1&iv_load_policy=3&disablekb=1&fs=0&showinfo=0&autohide=1&enablejsapi=1&origin=https://mixcasete.app&start=" + startSec + "' "
                + "allow='autoplay; encrypted-media' "
                + "allowfullscreen></iframe>"
                + "</div>"
                + "<script>"
                + "window.ytPlayer = null;"
                + "window.addEventListener('message', function(e){});"
                + "function execCmd(action, arg){"
                + "  var ifr = document.getElementById('ytIframe');"
                + "  if(ifr && ifr.contentWindow){"
                + "    ifr.contentWindow.postMessage(JSON.stringify({event:'command', func:action, args: arg ? [arg] : []}), '*');"
                + "  }"
                + "}"
                + "</script>"
                + "</body>"
                + "</html>";

        tvWv.loadDataWithBaseURL("https://mixcasete.app", html, "text/html", "UTF-8", null);
    }

    public void showBlankScreen() {
        this.currentVideoId = null;
        if (tvWv != null) {
            String blankHtml = "<!DOCTYPE html><html><body style='margin:0;padding:0;background:#000;width:100vw;height:100vh;'></body></html>";
            tvWv.loadDataWithBaseURL("about:blank", blankHtml, "text/html", "UTF-8", null);
        }
    }

    public void pauseVideo() {
        this.isPlaying = false;
        if (tvWv != null) {
            tvWv.evaluateJavascript("if(window.execCmd) execCmd('pauseVideo');", null);
        }
    }

    public void resumeVideo() {
        this.isPlaying = true;
        if (tvWv != null) {
            tvWv.evaluateJavascript("if(window.execCmd) execCmd('playVideo');", null);
        }
    }

    public void seekVideo(int sec) {
        if (tvWv != null) {
            tvWv.evaluateJavascript("if(window.execCmd) execCmd('seekTo', " + sec + ");", null);
        }
    }

    /**
     * Sincronización y corrección de deriva entre el celular y la TV.
     */
    public void syncDrift(int phoneCurrentSec) {
        if (!isPlaying || tvWv == null) return;
        // Evaluar tiempo actual en la TV y si la deriva supera 2.5s, sincronizar
        tvWv.evaluateJavascript("(function(){"
                + "  var v = document.querySelector('video');"
                + "  if(v && Math.abs(v.currentTime - " + phoneCurrentSec + ") > 2.5){"
                + "    v.currentTime = " + phoneCurrentSec + ";"
                + "  }"
                + "})()", null);
    }

    @Override
    public void dismiss() {
        if (tvWv != null) {
            try {
                tvWv.loadUrl("about:blank");
                tvWv.destroy();
            } catch (Exception ignored) {}
            tvWv = null;
        }
        super.dismiss();
    }
}
