package com.retrosearch.play;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

public class MainActivity extends Activity implements InputManager.InputDeviceListener {
    static final String CATALOG = "file:///android_asset/index.html";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final InputRouter inputs = new InputRouter((button, down) ->
        evaluate("window.SnesTV && SnesTV.button(" + button + "," + down + ")"));
    private FrameLayout root;
    private LinearLayout playerPanel, toolbar;
    WebView catalog, player;
    private TextView status;
    private Button playButton, remoteButton;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private InputManager inputManager;
    boolean gameMode;
    private boolean alternateRemote, bridgeReady, pendingPlay, paused;
    private long playDeadline;
    private int navigation, navDirection;
    private String playerScript = "";
    private String searchScript = "";
    private String playerTemplate = "";
    private String playerUserAgent = "";
    interface PageSource { String load(String url, String userAgent) throws IOException; }
    volatile PageSource pageSource = MainActivity::downloadGamePage;
    WebView searchWeb;
    interface SearchLoader { void load(WebView view, String url); }
    SearchLoader searchLoader = WebView::loadUrl;
    private int searchSerial;
    private Runnable searchTimeout;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        playerScript = readAsset("player.js");
        searchScript = readAsset("search.js");
        playerTemplate = readAsset("game.html");
        playerUserAgent = WebSettings.getDefaultUserAgent(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xff0d1526);
        setContentView(root);
        catalog = makeWebView(true);
        catalog.addJavascriptInterface(new CatalogBridge(), "SnesApp");
        root.addView(catalog, new FrameLayout.LayoutParams(-1, -1));
        catalog.loadUrl(CATALOG);
        catalog.requestFocus();
        inputManager = (InputManager) getSystemService(INPUT_SERVICE);
        inputManager.registerInputDeviceListener(this, handler);
    }

    private String readAsset(String name) {
        try (InputStream stream = getAssets().open(name)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int n;
            while ((n = stream.read(buffer)) != -1) bytes.write(buffer, 0, n);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new IllegalStateException("Missing app asset", e); }
    }

    static boolean allowedPlayerUrl(String value) {
        if (value == null) return false;
        try {
        Uri u = Uri.parse(value);
        String host = u.getHost();
        return "https".equalsIgnoreCase(u.getScheme()) &&
            ("sneslive.com".equalsIgnoreCase(host) || "www.sneslive.com".equalsIgnoreCase(host)) &&
            u.getUserInfo() == null && (u.getPort() == -1 || u.getPort() == 443);
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private WebView makeWebView(boolean local) {
        WebView web = new WebView(this);
        web.setBackgroundColor(0xff0d1526);
        web.setFocusable(true); web.setFocusableInTouchMode(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false); s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        if (local) {
            s.setBlockNetworkLoads(true);
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    return !CATALOG.equals(r.getUrl().toString());
                }
                @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                    return !CATALOG.equals(url);
                }
                @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                    if (r.getUrl().toString().startsWith("file:///android_asset/")) return null;
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }
            });
        } else {
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
            web.setWebViewClient(new PlayerClient());
        }
        return web;
    }

    private final class CatalogBridge {
        @JavascriptInterface public void play(String slug) {
            if (slug == null || !slug.matches("[a-z0-9-]{1,100}")) return;
            runOnUiThread(() -> openPlayer("https://sneslive.com/" + slug + "/"));
        }
        @JavascriptInterface public void searchAll(String query, int page, int requestId) {
            if (query == null || query.trim().isEmpty() || query.length() > 200 || page < 1 || page > 100) return;
            runOnUiThread(() -> startSearch(query.trim(), page, requestId));
        }
        @JavascriptInterface public void cancelSearch() { runOnUiThread(() -> stopSearch()); }
        @JavascriptInterface public void help() { runOnUiThread(() -> showHelp()); }
    }

    void stopSearch() {
        searchSerial++;
        if (searchTimeout != null) handler.removeCallbacks(searchTimeout);
        searchTimeout = null;
        if (searchWeb != null) {
            searchWeb.stopLoading();
            root.removeView(searchWeb); searchWeb.destroy(); searchWeb = null;
        }
    }

    void startSearch(String query, int page, int requestId) {
        stopSearch();
        final int serial = searchSerial;
        searchWeb = makeWebView(false);
        searchWeb.setFocusable(false);
        searchWeb.setVisibility(View.INVISIBLE);
        searchWeb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        root.addView(searchWeb, new FrameLayout.LayoutParams(1,1));
        searchWeb.setWebViewClient(new WebViewClient() {
            private boolean searchUrl(String value) {
                if (!allowedPlayerUrl(value)) return false;
                Uri url = Uri.parse(value);
                return query.equals(url.getQueryParameter("s")) &&
                    ("/".equals(url.getPath()) || url.getPath().matches("/page/[0-9]+/?"));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return r.isForMainFrame() && !searchUrl(r.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return !searchUrl(url); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                // Search uses only the main document's links. Do not load ads, scripts, or media.
                return r.isForMainFrame() && searchUrl(r.getUrl().toString()) ? null : blockedResource();
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (view != searchWeb || serial != searchSerial) return;
                if (!searchUrl(url)) { searchFailed(serial, query, page, requestId); return; }
                view.evaluateJavascript(searchScript, answer -> {
                    if (serial != searchSerial || view != searchWeb) return;
                    try {
                        if (answer == null || answer.length() > 65000) throw new JSONException("Search response too large");
                        JSONObject result = new JSONObject(answer);
                        if (!result.optBoolean("valid")) throw new JSONException("Search results unavailable");
                        JSONArray raw = result.getJSONArray("games"), safe = new JSONArray();
                        for (int i=0; i<Math.min(raw.length(),60); i++) {
                            JSONArray row = raw.optJSONArray(i);
                            if (row == null || row.length() < 2) continue;
                            String title = row.optString(0,""), slug = row.optString(1,"");
                            if (title.isEmpty() || title.length()>160 || !slug.matches("[a-z0-9-]{1,100}")) continue;
                            safe.put(new JSONArray().put(title).put(slug).put("Online game"));
                        }
                        deliverSearch(serial, query, page, requestId, safe, result.optBoolean("more"), "");
                    } catch (JSONException invalid) { searchFailed(serial, query, page, requestId); }
                });
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) searchFailed(serial, query, page, requestId);
            }
            @Override public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse e) {
                if (r.isForMainFrame()) searchFailed(serial, query, page, requestId);
            }
        });
        searchTimeout = () -> searchFailed(serial, query, page, requestId);
        handler.postDelayed(searchTimeout, 30000);
        try {
            String path = page == 1 ? "/" : "/page/" + page + "/";
            searchLoader.load(searchWeb, "https://sneslive.com" + path + "?s=" + URLEncoder.encode(query,"UTF-8"));
        } catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    private void searchFailed(int serial, String query, int page, int requestId) {
        deliverSearch(serial, query, page, requestId, new JSONArray(), false,
            "Online search is unavailable. Check internet and try again, or choose a featured game.");
    }
    private void deliverSearch(int serial, String query, int page, int requestId, JSONArray rows, boolean more, String error) {
        if (serial != searchSerial) return;
        stopSearch();
        catalog.evaluateJavascript("window.SnesCatalog && SnesCatalog.receiveSearch(" + requestId + "," +
            JSONObject.quote(query) + "," + page + "," + rows + "," + more + "," + JSONObject.quote(error) + ")", null);
    }

    static WebResourceResponse blockedResource() {
        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
    }

    static String downloadGamePage(String initialUrl, String userAgent) throws IOException {
        String url = initialUrl;
        for (int redirect = 0; redirect < 4; redirect++) {
            if (!AdFreePlayer.gamePage(url)) throw new IOException("Invalid game page");
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept", "text/html");
            try {
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Invalid redirect");
                    url = new URL(new URL(url), location).toExternalForm();
                    continue;
                }
                if (code != 200) throw new IOException("Game page unavailable");
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                try (InputStream stream = connection.getInputStream()) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = stream.read(buffer)) != -1) {
                        if (bytes.size() + n > 2 * 1024 * 1024 || Thread.currentThread().isInterrupted())
                            throw new IOException("Game page too large or canceled");
                        bytes.write(buffer, 0, n);
                    }
                }
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many redirects");
    }

    class PlayerClient extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (!request.isForMainFrame()) return AdFreePlayer.allowedAsset(url) ? null : blockedResource();
            String document;
            try {
                if (!"GET".equals(request.getMethod()) || !AdFreePlayer.gamePage(url)) throw new IOException("Invalid page");
                document = AdFreePlayer.document(pageSource.load(url, playerUserAgent), url, playerTemplate);
            } catch (IOException | IllegalArgumentException unavailable) { document = AdFreePlayer.errorDocument(); }
            return new WebResourceResponse("text/html", "UTF-8",
                new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
        }
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            return blockNavigation(request.getUrl().toString());
        }
        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return blockNavigation(url); }
        private boolean blockNavigation(String url) {
            if (AdFreePlayer.gamePage(url)) return false;
            Toast.makeText(MainActivity.this, "Use Games to choose a game.", Toast.LENGTH_SHORT).show();
            return true;
        }
        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
            navigation++; bridgeReady = false; setGameMode(false);
            handler.removeCallbacks(pollPlayer);
            setStatus("Loading game…");
        }
        @Override public void onPageFinished(WebView view, String url) {
            if (view != player || !allowedPlayerUrl(url)) return;
            int version = navigation;
            view.evaluateJavascript(playerScript, ignored -> {
                if (view != player || version != navigation) return;
                bridgeReady = true;
                handler.removeCallbacks(pollPlayer);
                handler.post(pollPlayer);
            });
        }
        @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (r.isForMainFrame()) fail("Page unavailable. Check internet, then Reload.");
        }
        @Override public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse e) {
            if (r.isForMainFrame()) fail("Game unavailable. Choose Reload or Games.");
        }
    }

    private void fail(String message) {
        pendingPlay = false; setGameMode(false); bridgeReady = false;
        setStatus(message);
        handler.removeCallbacks(pollPlayer);
    }

    void openPlayer(String url) {
        if (!AdFreePlayer.gamePage(url)) return;
        stopSearch();
        if (playerPanel != null) { player.loadUrl(url); requestPlay(); return; }
        playerPanel = new LinearLayout(this);
        playerPanel.setOrientation(LinearLayout.VERTICAL);
        toolbar = new LinearLayout(this);
        toolbar.setPadding(dp(20), dp(4), dp(20), dp(4));
        toolbar.setBackgroundColor(0xff192b44);
        addButton("Games", this::showCatalog);
        playButton = addButton("Start / Focus", this::requestPlay);
        addButton("Reload", () -> { setGameMode(false); player.reload(); requestPlay(); });
        remoteButton = addButton("Remote: face", () -> {
            inputs.releaseAll(); alternateRemote = !alternateRemote;
            remoteButton.setText(alternateRemote ? "Remote: extra" : "Remote: face");
            Toast.makeText(this, alternateRemote ? "Center X · Rewind L · Forward R · Play Select" :
                "Center B · Rewind Y · Forward A · Play Start", Toast.LENGTH_LONG).show();
        });
        addButton("Controls", this::showHelp);
        playerPanel.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(60)));
        status = new TextView(this);
        status.setTextColor(0xffb7d2ea); status.setTextSize(13);
        status.setPadding(dp(24), dp(5), dp(24), dp(5));
        playerPanel.addView(status, new LinearLayout.LayoutParams(-1, -2));
        player = makeWebView(false);
        player.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) { callback.onCustomViewHidden(); return; }
                fullscreenView = view; fullscreenCallback = callback;
                playerPanel.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
            }
            @Override public void onHideCustomView() { closeFullscreen(); }
        });
        playerPanel.addView(player, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(playerPanel, new FrameLayout.LayoutParams(-1, -1));
        catalog.setVisibility(View.GONE);
        player.requestFocus();
        requestPlay(); player.loadUrl(url);
    }

    private Button addButton(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label); b.setTextSize(12); b.setAllCaps(false);
        b.setTextColor(Color.WHITE); b.setFocusable(true);
        android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, new android.graphics.drawable.ColorDrawable(0xff277d7c));
        background.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0xff253b57));
        b.setBackground(background);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -1, 1);
        layout.setMargins(dp(3), 0, dp(3), 0);
        toolbar.addView(b, layout);
        return b;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void setStatus(String value) { if (status != null) status.setText(value); }
    private void evaluate(String js) { if (player != null && bridgeReady) player.evaluateJavascript(js, null); }

    private void requestPlay() {
        pendingPlay = true; playDeadline = SystemClock.uptimeMillis() + 120000;
        if (bridgeReady && !paused) { handler.removeCallbacks(pollPlayer); handler.post(pollPlayer); }
    }
    private final Runnable pollPlayer = new Runnable() {
        @Override public void run() {
            if (player == null || paused || !bridgeReady) return;
            final int version = navigation;
            WebView current = player;
            String script = pendingPlay ? "SnesTV.start()" : "(SnesTV.ready() ? 'ready' : 'waiting')";
            current.evaluateJavascript("window.SnesTV ? " + script + " : 'waiting'", answer -> {
                if (current != player || version != navigation || paused) return;
                if ("\"ready\"".equals(answer)) {
                    if (pendingPlay) { pendingPlay = false; setGameMode(true); }
                    else if (!gameMode) setStatus("Player ready. Select Start / Focus for game controls.");
                } else if ("\"failed\"".equals(answer)) {
                    fail("The online emulator failed to start. Choose Reload or Games to try another title.");
                    return;
                } else if (pendingPlay && SystemClock.uptimeMillis() > playDeadline) {
                    pendingPlay = false;
                    setStatus("Player did not become ready. Choose Reload or Games to try another title.");
                    setGameMode(false);
                } else if (pendingPlay) setStatus("Starting game… Menu opens app controls.");
                if (player != null) handler.postDelayed(this, 1000);
            });
        }
    };

    void setGameMode(boolean active) {
        inputs.releaseAll(); stopNavigation();
        if (active) pendingPlay = false;
        gameMode = active;
        evaluate("window.SnesTV && SnesTV.active(" + active + ")");
        if (toolbar != null) toolbar.setVisibility(active ? View.GONE : View.VISIBLE);
        if (status != null) status.setVisibility(active ? View.GONE : View.VISIBLE);
        if (active && player != null) player.requestFocus();
    }
    private void showToolbar() {
        pendingPlay = false;
        closeFullscreen(); setGameMode(false);
        if (playButton != null) playButton.requestFocus();
    }
    private void showHelp() {
        if (player != null) showToolbar();
        new AlertDialog.Builder(this).setTitle("Controls")
            .setMessage("Browse: D-pad / left stick to move; A / Cross / Center to select.\n\n" +
                "Gamepad → SNES\nA / Cross → B\nB / Circle → A\nX / Square → Y\nY / Triangle → X\nLB / L1 → L; RB / R1 → R\nStart / Options → Start; View / Share → Select\n\n" +
                "Fire remote: D-pad moves; Center → B; Rewind → Y; Forward → A; Play/Pause → Start.\n" +
                "Menu → app controls. Switch Remote to extra for Center → X; Rewind → L; Forward → R; Play → Select.\n\n" +
                "Back opens app controls, then returns to Games. After a pause, select Start / Focus to resume. " +
                "Full gameplay requires a gamepad for simultaneous inputs. Home remains a system button.")
            .setPositiveButton("OK", null).show();
    }
    private void closeFullscreen() {
        if (fullscreenView == null) return;
        root.removeView(fullscreenView); fullscreenView = null;
        if (playerPanel != null) playerPanel.setVisibility(View.VISIBLE);
        WebChromeClient.CustomViewCallback cb = fullscreenCallback; fullscreenCallback = null;
        if (cb != null) cb.onCustomViewHidden();
    }
    void showCatalog() {
        setGameMode(false); closeFullscreen(); pendingPlay = false;
        handler.removeCallbacks(pollPlayer); navigation++;
        if (player != null) {
            player.stopLoading();
            ((ViewGroup) player.getParent()).removeView(player);
            player.destroy(); player = null;
        }
        bridgeReady = false;
        if (playerPanel != null) root.removeView(playerPanel);
        playerPanel = null; toolbar = null; status = null; playButton = null;
        catalog.setVisibility(View.VISIBLE); catalog.requestFocus();
        catalog.evaluateJavascript("window.SnesCatalog && SnesCatalog.restoreFocus()", null);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode(); boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP)
            return super.dispatchKeyEvent(event);
        if (key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (down && event.getRepeatCount() == 0) { if (player != null) showToolbar(); else showHelp(); }
            return true;
        }
        if (gameMode && player != null) {
            int button = ControllerMapper.button(key, alternateRemote);
            if (button >= 0) {
                inputs.set(event.getDeviceId() + ":key:" + key, button, down);
                return true;
            }
        } else {
            if (key == KeyEvent.KEYCODE_BUTTON_A) {
                KeyEvent center = new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(),
                    KeyEvent.KEYCODE_DPAD_CENTER, event.getRepeatCount(), 0, event.getDeviceId(), 0, 0, InputDevice.SOURCE_DPAD);
                return super.dispatchKeyEvent(center);
            }
            if (key == KeyEvent.KEYCODE_BUTTON_B) {
                if (!down) onBackPressed();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK ||
            event.getAction() != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(event);
        InputDevice device = event.getDevice();
        InputDevice.MotionRange range = device == null ? null : device.getMotionRange(MotionEvent.AXIS_X, event.getSource());
        int mask = ControllerMapper.directions(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y),
            event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y), range == null ? 0 : range.getFlat());
        if (gameMode) {
            for (int button = 4; button <= 7; button++)
                inputs.set(event.getDeviceId() + ":axis", button, (mask & (1 << button)) != 0);
        } else {
            int direction = (mask & 16) != 0 ? KeyEvent.KEYCODE_DPAD_UP : (mask & 32) != 0 ? KeyEvent.KEYCODE_DPAD_DOWN :
                (mask & 64) != 0 ? KeyEvent.KEYCODE_DPAD_LEFT : (mask & 128) != 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : 0;
            if (direction != navDirection) {
                stopNavigation(); navDirection = direction;
                if (direction != 0) { navigate(direction); handler.postDelayed(repeatNavigation, 350); }
            }
        }
        return true;
    }
    private void navigate(int key) {
        if (player == null) {
            String arrow = key == 19 ? "ArrowUp" : key == 20 ? "ArrowDown" : key == 21 ? "ArrowLeft" : "ArrowRight";
            catalog.evaluateJavascript("window.SnesCatalog && SnesCatalog.move('" + arrow + "')", null);
        } else if (player.hasFocus()) {
            long now = SystemClock.uptimeMillis();
            player.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0));
            player.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0));
        } else {
            View focus = getCurrentFocus();
            if (focus == null) { if (playButton != null) playButton.requestFocus(); return; }
            int direction = key == 19 ? View.FOCUS_UP : key == 20 ? View.FOCUS_DOWN : key == 21 ? View.FOCUS_LEFT : View.FOCUS_RIGHT;
            View next = focus.focusSearch(direction);
            if (next != null) next.requestFocus();
        }
    }
    private final Runnable repeatNavigation = new Runnable() {
        @Override public void run() { if (navDirection != 0) { navigate(navDirection); handler.postDelayed(this, 150); } }
    };
    private void stopNavigation() { navDirection = 0; handler.removeCallbacks(repeatNavigation); }
    @Override public void onInputDeviceRemoved(int id) { inputs.releaseDevice(id); stopNavigation(); }
    @Override public void onInputDeviceChanged(int id) { inputs.releaseDevice(id); }
    @Override public void onInputDeviceAdded(int id) {}

    @Override public void onBackPressed() {
        if (fullscreenView != null || gameMode) showToolbar();
        else if (player != null) showCatalog();
        else super.onBackPressed();
    }
    @Override protected void onPause() {
        paused = true; pendingPlay = false;
        if (searchWeb != null) {
            stopSearch();
            catalog.evaluateJavascript("window.SnesCatalog && SnesCatalog.cancelPending()", null);
        }
        inputs.releaseAll(); stopNavigation(); handler.removeCallbacks(pollPlayer);
        evaluate("window.SnesTV && SnesTV.suspend()"); setGameMode(false);
        if (player != null) player.onPause();
        catalog.onPause(); catalog.pauseTimers();
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume(); paused = false;
        if (catalog != null) { catalog.resumeTimers(); catalog.onResume(); }
        if (player != null) { player.onResume(); if (playButton != null) playButton.requestFocus(); }
        if (bridgeReady) handler.post(pollPlayer);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) { inputs.releaseAll(); stopNavigation(); evaluate("window.SnesTV && SnesTV.release()"); }
    }
    @Override protected void onDestroy() {
        stopSearch();
        handler.removeCallbacksAndMessages(null); inputs.releaseAll();
        if (inputManager != null) inputManager.unregisterInputDeviceListener(this);
        closeFullscreen();
        if (player != null) { ((ViewGroup) player.getParent()).removeView(player); player.destroy(); }
        if (catalog != null) { root.removeView(catalog); catalog.destroy(); }
        super.onDestroy();
    }
}
