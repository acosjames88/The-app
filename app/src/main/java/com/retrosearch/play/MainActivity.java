package com.retrosearch.play;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Native TV catalog and SNES player. There is no browser component. */
public class MainActivity extends Activity implements InputManager.InputDeviceListener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService downloads = Executors.newFixedThreadPool(2);
    GameSession session;
    private final InputRouter inputs = new InputRouter((button, down) -> { if (session != null) session.button(button, down); });
    private FrameLayout root;
    private LinearLayout home;
    private GridLayout grid;
    private EditText search;
    private TextView status;
    private SurfaceView gameView;
    private InputManager inputManager;
    private RomRepository repository;
    private Future<?> pending;
    private int request, onlinePage = 1;
    private String onlineQuery = "";
    private List<GameCatalog.Game> online;
    private boolean loading, alternateRemote, menuShowing, foreground, destroyed;
    private int navDirection;
    private long navTime;
    private AlertDialog menu;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        repository = new RomRepository(new File(getFilesDir(), "games"));
        inputManager = (InputManager) getSystemService(INPUT_SERVICE);
        inputManager.registerInputDeviceListener(this, handler);
        root = new FrameLayout(this); root.setBackgroundColor(0xff0d1526); setContentView(root);
        buildHome(); immersive();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    private TextView text(String value, int size) {
        TextView t = new TextView(this); t.setText(value); t.setTextColor(Color.WHITE); t.setTextSize(size); return t;
    }
    private Button button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setTextColor(Color.WHITE); b.setAllCaps(false);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, new android.graphics.drawable.ColorDrawable(0xff287e7c));
        bg.addState(new int[]{android.R.attr.state_pressed}, new android.graphics.drawable.ColorDrawable(0xff287e7c));
        bg.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0xff20344f));
        b.setBackground(bg); b.setOnClickListener(v -> action.run()); b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setTextSize(15); return b;
    }
    private void buildHome() {
        root.removeAllViews();
        home = new LinearLayout(this); home.setOrientation(LinearLayout.VERTICAL); home.setPadding(dp(30), dp(20), dp(30), dp(14));
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));
        home.addView(text("SNESLive TV  •  Select & play", 26));
        TextView caption = text("Full-screen games · No ads · Downloaded games work offline", 14);
        caption.setTextColor(0xff9db5cc); home.addView(caption);
        LinearLayout row = new LinearLayout(this);
        search = new EditText(this); search.setSingleLine(true); search.setTextColor(Color.WHITE); search.setHintTextColor(0xffb8c5d2);
        search.setHint("Search games"); search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        row.addView(search, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button find = button("Search all", () -> searchOnline(true));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(130), dp(48)); p.setMargins(dp(12), 0, 0, 0); row.addView(find, p);
        home.addView(row);
        LinearLayout actions = new LinearLayout(this);
        addAction(actions, "Featured", () -> { cancelRequest(); online = null; search.setText(""); renderGames(); });
        addAction(actions, "Import game", this::importGame);
        addAction(actions, "Next results", () -> searchOnline(false));
        addAction(actions, "Controls / About", this::about);
        home.addView(actions);
        status = text("Choose a game. Star Catcher is ready to play offline.", 14); status.setPadding(0, dp(10), 0, dp(10)); home.addView(status);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        grid = new GridLayout(this); grid.setColumnCount(3); scroll.addView(grid);
        home.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int f) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                cancelRequest(); online = null; renderGames();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        search.setOnEditorActionListener((v, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { searchOnline(true); return true; } return false;
        });
        renderGames(); grid.post(() -> { if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus(); });
    }
    private void addAction(LinearLayout row, String label, Runnable task) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1); p.setMargins(dp(2), dp(4), dp(2), 0);
        row.addView(button(label, task), p);
    }
    private void renderGames() {
        grid.removeAllViews();
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<GameCatalog.Game> list = online == null ? new ArrayList<>(GameCatalog.games()) : online;
        if (online == null) {
            File[] imports = new File(getFilesDir(), "games").listFiles((dir, name) -> name.matches("import-[a-f0-9]{64}\\.sfc"));
            if (imports != null) for (File imported : imports) {
                String key = imported.getName().substring(0, imported.getName().length() - 4);
                String title = getPreferences(MODE_PRIVATE).getString(key, "Imported game " + key.substring(7, 15));
                list.add(new GameCatalog.Game(title, key));
            }
        }
        int count = 0;
        for (GameCatalog.Game game : list) {
            if (online == null && !game.title.toLowerCase(Locale.ROOT).contains(q)) continue;
            boolean offline = game.slug.equals("star-catcher") || repository.file(game.slug).isFile();
            Button card = button(game.title + (offline ? "\nReady offline" : "\nDownload & play"), () -> openGame(game));
            card.setGravity(Gravity.CENTER); card.setMinHeight(dp(70));
            GridLayout.LayoutParams p = new GridLayout.LayoutParams(GridLayout.spec(count / 3), GridLayout.spec(count % 3, 1f));
            p.width = 0; p.height = dp(78); p.setMargins(dp(4), dp(4), dp(4), dp(4)); grid.addView(card, p); count++;
        }
        status.setText(count == 0 ? "No matches. Use Search all or import your game file." :
            count + " games" + (online == null ? " · Select a game to play" : " · Online results, page " + onlinePage));
    }
    private void searchOnline(boolean first) {
        String q = search.getText().toString().trim();
        if (q.isEmpty() || q.length() > 200) { status.setText("Enter a game title first."); search.requestFocus(); return; }
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(), 0);
        int page = first || !q.equals(onlineQuery) ? 1 : onlinePage + 1;
        cancelRequest(); int serial = request;
        status.setText("Searching games…");
        pending = downloads.submit(() -> {
            try {
                List<GameCatalog.Game> result = repository.search(q, page);
                handler.post(() -> {
                    if (serial != request || destroyed) return;
                    onlineQuery = q; onlinePage = page; online = result; renderGames();
                    if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
                });
            } catch (Exception e) { handler.post(() -> { if (serial == request && !destroyed) status.setText("Search is unavailable. Featured and saved games are available."); }); }
        });
    }
    void openGame(GameCatalog.Game game) {
        cancelRequest(); int serial = request;
        showLoading("Opening " + game.title + "…");
        pending = downloads.submit(() -> {
            try {
                byte[] data;
                if (game.slug.equals("star-catcher")) {
                    try (InputStream in = getAssets().open("star-catcher.sfc")) { data = RomRepository.read(in, RomRepository.MAX_ROM); }
                } else data = repository.game(game.slug);
                byte[] rom = data;
                handler.post(() -> { if (serial == request && !destroyed) startGame(rom, game.slug); });
            } catch (Exception e) { handler.post(() -> { if (serial == request && !destroyed) loadFailed(e.getMessage()); }); }
        });
    }
    private void showLoading(String message) {
        loading = true; root.removeAllViews();
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setGravity(Gravity.CENTER);
        panel.addView(text(message, 23)); panel.addView(button("Games", this::showGames)); root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
    }
    private void loadFailed(String reason) {
        loading = false; buildHome();
        new AlertDialog.Builder(this).setTitle("Game could not start")
            .setMessage((reason == null ? "The game file is unavailable." : reason) + "\n\nTry again, choose a saved game, or import your SNES game file.")
            .setPositiveButton("Games", null).setNeutralButton("Import game", (d, w) -> importGame()).show();
    }
    void startGame(byte[] rom, String key) {
        loading = false; root.removeAllViews();
        gameView = new SurfaceView(this); gameView.setKeepScreenOn(true); gameView.setFocusable(true); gameView.setFocusableInTouchMode(true);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        root.addView(gameView, layout);
        root.post(() -> {
            if (gameView == null) return;
            int width = root.getWidth(), height = root.getHeight();
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) gameView.getLayoutParams();
            p.width = Math.min(width, height * 4 / 3); p.height = Math.min(height, width * 3 / 4); gameView.setLayoutParams(p);
        });
        gameView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { NativeCore.surface(h.getSurface()); }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int height) { NativeCore.surface(h.getSurface()); }
            @Override public void surfaceDestroyed(SurfaceHolder h) { NativeCore.surface(null); }
        });
        File saves = new File(getFilesDir(), "saves"); saves.mkdirs();
        session = new GameSession(rom, new File(saves, key + ".srm").getAbsolutePath(), new GameSession.Listener() {
            @Override public void ready() { handler.post(() -> { if (session != null) { session.pause(!foreground); immersive(); } }); }
            @Override public void failed(String message) { handler.post(() -> { if (!destroyed && session != null) { stopGame(); loadFailed(message); } }); }
        });
        gameView.requestFocus(); immersive();
    }
    private void cancelRequest() { request++; if (pending != null) { pending.cancel(true); pending = null; } loading = false; }
    private void stopGame() {
        inputs.releaseAll();
        if (session != null) { session.stop(); session = null; }
        gameView = null;
    }
    void showGames() {
        cancelRequest(); stopGame(); menuShowing = false;
        if (menu != null) { menu.dismiss(); menu = null; }
        buildHome(); immersive();
    }
    private void importGame() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(intent, 31); }
        catch (android.content.ActivityNotFoundException unavailable) {
            new AlertDialog.Builder(this).setTitle("Import game").setMessage("A file picker is needed to choose a .sfc, .smc, or .zip game from USB or Downloads. Install a file picker on this Fire TV, or choose a game from the catalog.")
                .setPositiveButton("OK", null).show();
        }
    }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != 31 || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData(); cancelRequest(); int serial = request; showLoading("Opening your game…");
        pending = downloads.submit(() -> {
            try {
                byte[] rom;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new java.io.IOException("The game file could not be read.");
                    rom = RomRepository.unpack(RomRepository.read(in, RomRepository.MAX_ROM + 1024 * 1024));
                }
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(rom);
                StringBuilder name = new StringBuilder("import-"); for (byte b : digest) name.append(String.format(Locale.ROOT, "%02x", b & 255));
                File cached = repository.file(name.toString());
                File temp = new File(cached.getParentFile(), name + ".partial");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) { out.write(rom); out.getFD().sync(); }
                if (!temp.renameTo(cached)) throw new java.io.IOException("The game could not be saved.");
                String title = "Imported game " + name.substring(7, 15);
                try (android.database.Cursor cursor = getContentResolver().query(uri,
                        new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        String display = cursor.getString(0);
                        if (display != null && !display.trim().isEmpty()) title = display.replaceFirst("(?i)\\.(sfc|smc|zip)$", "");
                    }
                } catch (Exception unavailableName) { /* The game remains usable without a provider label. */ }
                getPreferences(MODE_PRIVATE).edit().putString(name.toString(), title).apply();
                handler.post(() -> { if (serial == request && !destroyed) startGame(rom, name.toString()); });
            } catch (Exception e) { handler.post(() -> { if (serial == request && !destroyed) loadFailed(e.getMessage()); }); }
        });
    }
    private void about() {
        new AlertDialog.Builder(this).setTitle("SNESLive TV 2.0 · Native player")
            .setMessage("Xbox / PlayStation:\nA / Cross = B; B / Circle = A\nX / Square = Y; Y / Triangle = X\nL1 / R1 = L / R; Options = Start; Share = Select\n\nFire remote:\nD-pad moves; Center = B; Rewind = Y; Fast Forward = A; Play/Pause = Start.\nMenu switches the remote button bank for X, L, R, Select.\n\nBack or Menu pauses the game. A gamepad supports simultaneous buttons.\n\nStar Catcher: collect the yellow stars with the D-pad. B plays a tone.\n\nBundled Snes9x 2005 core. Snes9x is copyright its authors; noncommercial distribution. Full notices are included in the APK; source and build instructions are in the project repository.")
            .setPositiveButton("OK", null).setNeutralButton("Licenses", (d, w) -> licenses()).show();
    }
    private void licenses() {
        try (InputStream in = getAssets().open("SNES9X-LICENSE.txt")) {
            String value = new String(RomRepository.read(in, 65536), java.nio.charset.StandardCharsets.UTF_8);
            TextView content = text(value, 13); content.setTextIsSelectable(true); content.setPadding(dp(16), dp(16), dp(16), dp(16));
            ScrollView scroll = new ScrollView(this); scroll.addView(content);
            new AlertDialog.Builder(this).setTitle("Snes9x notices").setView(scroll).setPositiveButton("OK", null).show();
        } catch (Exception e) { new AlertDialog.Builder(this).setMessage("Source: github.com/libretro/snes9x2005").setPositiveButton("OK", null).show(); }
    }
    private void showMenu() {
        if (session == null || menuShowing) return;
        menuShowing = true; inputs.releaseAll(); session.pause(true);
        menu = new AlertDialog.Builder(this).setTitle("Game paused").setItems(new String[]{"Resume game",
            alternateRemote ? "Remote buttons: X / L / R / Select" : "Remote buttons: B / Y / A / Start", "Games"},
            (dialog, index) -> {
                if (index == 2) { showGames(); return; }
                if (index == 1) alternateRemote = !alternateRemote;
            }).create();
        menu.setOnDismissListener(d -> { menuShowing = false; if (session != null) { session.pause(!foreground); immersive(); } });
        menu.show();
    }
    @Override public void onBackPressed() {
        if (loading) { showGames(); return; }
        if (session != null) { showMenu(); return; }
        super.onBackPressed();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int key = e.getKeyCode(); boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (down && e.getRepeatCount() == 0) { if (session != null) showMenu(); else about(); } return true;
        }
        if (session != null && !menuShowing) {
            int button = ControllerMapper.button(key, alternateRemote);
            if (button >= 0) { inputs.set(e.getDeviceId() + ":key:" + key, button, down); return true; }
        } else if (session == null) {
            if (key == KeyEvent.KEYCODE_BUTTON_A) return super.dispatchKeyEvent(new KeyEvent(e.getDownTime(), e.getEventTime(),
                e.getAction(), KeyEvent.KEYCODE_DPAD_CENTER, e.getRepeatCount(), 0, e.getDeviceId(), 0, 0, InputDevice.SOURCE_DPAD));
            if (key == KeyEvent.KEYCODE_BUTTON_B) { if (!down) onBackPressed(); return true; }
        }
        return super.dispatchKeyEvent(e);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK || e.getAction() != MotionEvent.ACTION_MOVE)
            return super.dispatchGenericMotionEvent(e);
        InputDevice device = e.getDevice();
        InputDevice.MotionRange range = device == null ? null : device.getMotionRange(MotionEvent.AXIS_X, e.getSource());
        int mask = ControllerMapper.directions(e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y),
            e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y), range == null ? .25f : range.getFlat());
        if (session != null && !menuShowing) {
            for (int i = 4; i < 8; i++) inputs.set(e.getDeviceId() + ":axis", i, (mask & (1 << i)) != 0);
        } else if (session == null) {
            int key = (mask & 16) != 0 ? 19 : (mask & 32) != 0 ? 20 : (mask & 64) != 0 ? 21 : (mask & 128) != 0 ? 22 : 0;
            long now = SystemClock.uptimeMillis();
            if (key != 0 && (key != navDirection || now - navTime > 180)) {
                navTime = now; super.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key)); super.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
            }
            navDirection = key;
        }
        return true;
    }
    @Override protected void onResume() { super.onResume(); foreground = true; if (session != null && !menuShowing) session.pause(false); immersive(); }
    @Override protected void onPause() { foreground = false; inputs.releaseAll(); if (session != null) session.pause(true); super.onPause(); }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) { inputs.releaseAll(); if (session != null) session.pause(true); }
        else { if (session != null && !menuShowing && foreground) session.pause(false); immersive(); }
    }
    @Override public void onInputDeviceAdded(int id) {}
    @Override public void onInputDeviceChanged(int id) { inputs.releaseDevice(id); }
    @Override public void onInputDeviceRemoved(int id) { inputs.releaseDevice(id); }
    @Override protected void onDestroy() {
        destroyed = true; cancelRequest(); stopGame(); downloads.shutdownNow(); handler.removeCallbacksAndMessages(null);
        inputManager.unregisterInputDeviceListener(this); super.onDestroy();
    }
}

