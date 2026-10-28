package com.retrosearch.play;

import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.net.URLEncoder;

public class MainActivity extends Activity {
    private FrameLayout root;
    private LinearLayout playerPanel;
    private WebView catalog;
    private WebView player;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(12, 15, 28));
        setContentView(root);

        catalog = webview();
        // The privileged bridge exists ONLY in the app's trusted, local catalog.
        catalog.addJavascriptInterface(new CatalogBridge(), "SnesApp");
        root.addView(catalog, new FrameLayout.LayoutParams(-1, -1));
        catalog.loadUrl("file:///android_asset/index.html");
    }

    private WebView webview() {
        WebView web = new WebView(this);
        web.setBackgroundColor(Color.rgb(12, 15, 28));
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !(scheme.equals("https") || scheme.equals("http") || scheme.equals("file"));
            }
        });
        return web;
    }

    private class CatalogBridge {
        @JavascriptInterface public void play(String slug) {
            if (slug == null || !slug.matches("[a-z0-9-]{1,100}")) return;
            runOnUiThread(() -> openPlayer("https://sneslive.com/" + slug + "/"));
        }
        @JavascriptInterface public void searchAll(String query) {
            runOnUiThread(() -> {
                try {
                    String q = URLEncoder.encode(query == null ? "" : query, "UTF-8");
                    openPlayer("https://sneslive.com/?s=" + q);
                } catch (Exception ignored) { openPlayer("https://sneslive.com/"); }
            });
        }
    }

    private Button toolbarButton(String label, Runnable onTap) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(54,65,104)));
        b.setOnClickListener(v -> onTap.run());
        return b;
    }

    private void openPlayer(String url) {
        if (!url.startsWith("https://sneslive.com/")) return;
        if (playerPanel != null) {
            if (player != null) player.loadUrl(url);
            return;
        }
        playerPanel = new LinearLayout(this);
        playerPanel.setOrientation(LinearLayout.VERTICAL);
        playerPanel.setBackgroundColor(Color.rgb(12,15,28));
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(6, 4, 6, 4);
        toolbar.setBackgroundColor(Color.rgb(25,29,49));
        toolbar.addView(toolbarButton("‹ Games", this::showCatalog));
        toolbar.addView(toolbarButton("↻ Reload", () -> { if (player != null) player.reload(); }));
        TextView title = new TextView(this);
        title.setText("SNES Search & Play");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setPadding(16, 15, 0, 0);
        toolbar.addView(title);
        playerPanel.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));

        player = webview();
        // No JavaScript bridge is exposed to external game pages.
        player.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) { callback.onCustomViewHidden(); return; }
                fullscreenView = view;
                fullscreenCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
            }
            @Override public void onHideCustomView() { closeFullscreen(); }
        });
        playerPanel.addView(player, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(playerPanel, new FrameLayout.LayoutParams(-1,-1));
        catalog.setVisibility(View.GONE);
        player.requestFocus();
        player.loadUrl(url);
    }

    private void closeFullscreen() {
        if (fullscreenView == null) return;
        root.removeView(fullscreenView);
        fullscreenView = null;
        if (fullscreenCallback != null) {
            fullscreenCallback.onCustomViewHidden();
            fullscreenCallback = null;
        }
    }

    private void showCatalog() {
        closeFullscreen();
        if (player != null) {
            player.stopLoading();
            player.loadUrl("about:blank");
            player.destroy();
            player = null;
        }
        if (playerPanel != null) {
            root.removeView(playerPanel);
            playerPanel = null;
        }
        catalog.setVisibility(View.VISIBLE);
        catalog.requestFocus();
    }

    @Override public void onBackPressed() {
        if (fullscreenView != null) closeFullscreen();
        else if (playerPanel != null) {
            if (player != null && player.canGoBack()) player.goBack();
            else showCatalog();
        } else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (player != null) player.destroy();
        if (catalog != null) catalog.destroy();
        super.onDestroy();
    }
}