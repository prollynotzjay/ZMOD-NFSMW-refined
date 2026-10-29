package com.nfsmw.androidevolved;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NFSMW Refined launcher, in prollynotzjay's GTA IV-style launcher layout with a Most Wanted look:
 * a night-street backdrop, the title and a big menu on the left (PLAY, GAME FILES, GRAPHICS & CONTROLS,
 * DRIVER, ABOUT), one panel at a time on dark glass on the right, and a controller hint bar at the bottom.
 * The selected menu item turns Most Wanted amber; red / blue pursuit lights are the secondary accents.
 */
public final class LauncherActivity extends Activity {
    private static final int REQUEST_DRIVER_ZIP = 45, REQUEST_DRIVER_PROBE = 46;
    private static final String REPO = "https://github.com/prollynotzjay/ZMOD-NFSMW-refined";
    private static final String ORIGINAL_REPO = "https://github.com/codepdbh/nfsmwpc-recompiled";

    // Most Wanted palette.
    private static final int AMBER = 0xFFFFB000, AMBER_TINT = 0x33FFB000, AMBER_DARK = 0xFF1A1100;
    private static final int PURSUIT_RED = 0xFFE5262E, PURSUIT_BLUE = 0xFF2A63FF;
    private static final int TEXT = 0xFFF2F2F2, MUTED = 0xFF9EA3AB, MENU_GREY = 0xFFCDCDCD;
    private static final int OK = 0xFF7CD992, BAD = 0xFFFF6B6B, WARN = 0xFFFFC857;
    /** Every dp() and text size goes through it, like the GTA IV launcher. */
    private static final float UI_SCALE = 0.82f;

    private static final int PANEL_PLAY = 0, PANEL_FILES = 1, PANEL_GRAPHICS = 2, PANEL_DRIVER = 3, PANEL_ABOUT = 4;
    private static final String[] PANEL_NAMES = {"PLAY", "GAME FILES", "GRAPHICS & CONTROLS", "DRIVER", "ABOUT"};
    private final View[] panels = new View[PANEL_NAMES.length];
    private final TextView[] navItems = new TextView[PANEL_NAMES.length];
    private int panel = PANEL_PLAY;

    private LinearLayout checksList, filesList, optionsList, driverList, aboutList;
    private TextView status, filesStatus;
    private View play, access;
    private Typeface fontBold, fontBlack, fontTag;
    private boolean playAfterProbe, checkingUpdates, resumed;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        loadFonts();
        hideSystemBars();
        setContentView(buildScreen());
        // Mali support: tuned settings the first time the launcher runs on a Mali GPU.
        worker.execute(() -> {
            boolean applied = MaliProfile.applyIfNew(this);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                refresh();
                if (applied) message("Mali GPU detected", "Settings tuned for " + MaliProfile.renderer(this)
                        + ":\n" + MaliProfile.describe(this) + "\n\nYou can change them in GRAPHICS & CONTROLS.");
            });
        });
        checkUpdates(false);
        String updated = ReleaseUpdates.pendingWhatsNew(this);
        if (updated != null) showWhatsNew(updated);
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) hideSystemBars();
    }

    private void loadFonts() {
        try {
            fontBold = new Typeface.Builder(getAssets(), "fonts/Nunito.ttf").setFontVariationSettings("'wght' 700").build();
            fontBlack = new Typeface.Builder(getAssets(), "fonts/Nunito.ttf").setFontVariationSettings("'wght' 950").build();
        } catch (RuntimeException error) {
            fontBold = Typeface.DEFAULT_BOLD;
            fontBlack = Typeface.DEFAULT_BOLD;
        }
        try { fontTag = Typeface.createFromAsset(getAssets(), "fonts/RockSalt-Regular.ttf"); }
        catch (RuntimeException error) { fontTag = Typeface.create("casual", Typeface.ITALIC); }
    }

    /** First launch after an update: the installed version's release notes from GitHub. */
    private void showWhatsNew(String version) {
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(tr("Updated to version %s!", version))
                .setMessage(tr("Loading what's new…"))
                .setPositiveButton(tr("OK"), (d, which) -> ReleaseUpdates.whatsNewSeen(this))
                .setNeutralButton(tr("View on GitHub"), (d, which) -> {
                    ReleaseUpdates.whatsNewSeen(this);
                    openUrl(Uri.parse(REPO + "/releases/tag/v" + Uri.encode(version)));
                })
                .setOnCancelListener(d -> ReleaseUpdates.whatsNewSeen(this))
                .show();
        ReleaseUpdates.releaseNotes(this, version, notes -> {
            if (!dialog.isShowing()) return;
            // In the app, skip the download/install preamble of the release page.
            int start = notes == null ? -1 : notes.indexOf("What's new");
            if (start > 0) notes = notes.substring(start);
            dialog.setMessage(notes != null ? notes
                    : tr("Couldn't load what's new (offline or not published yet). You can read it on GitHub."));
        });
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
        String crash = Diagnostics.unreportedCrash(this);
        if (crash != null) {
            Diagnostics.acknowledgeCrashes(this);
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("The game closed")
                    .setMessage(tr("%s.\n\nDo you want to send the full log to GitHub so it can be fixed?", crash))
                    .setPositiveButton("Report on GitHub", (dialog, which) -> prepareReport(crash))
                    .setNegativeButton("Not now", null).show();
        }
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    /** Controller: A selects (Android's own focus handling), B goes back to PLAY, START plays. */
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_START && play != null && play.isEnabled()) { play(); return true; }
        if ((keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK) && panel != PANEL_PLAY) {
            showPanel(PANEL_PLAY);
            navItems[PANEL_PLAY].requestFocus();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- Screen ------------------------------------------------------------------------------------------

    private View buildScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF08090C);
        // Rockport at night behind the menu, darker on the left where the menu is.
        ImageView backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        try (InputStream in = getAssets().open("launcher/background.jpg")) {
            backdrop.setImageBitmap(BitmapFactory.decodeStream(in));
        } catch (java.io.IOException ignored) {
        }
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xF208090C, 0xC808090C, 0x6008090C}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        // Pursuit light bar: a thin red / blue line across the top.
        LinearLayout lightBar = new LinearLayout(this);
        View red = new View(this), blue = new View(this);
        red.setBackgroundColor(PURSUIT_RED);
        blue.setBackgroundColor(PURSUIT_BLUE);
        lightBar.addView(red, new LinearLayout.LayoutParams(0, -1, 1f));
        lightBar.addView(blue, new LinearLayout.LayoutParams(0, -1, 1f));
        root.addView(lightBar, new FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP));

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(26), dp(16), dp(26), dp(8));
        LinearLayout body = new LinearLayout(this);

        // Left: title, version and the menu.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        ImageView banner = new ImageView(this);
        banner.setImageResource(R.drawable.launcher_banner);
        banner.setAdjustViewBounds(true);
        banner.setScaleType(ImageView.ScaleType.FIT_START);
        banner.setMaxHeight(getResources().getDisplayMetrics().heightPixels / 5);
        left.addView(banner, new LinearLayout.LayoutParams(-1, -2));
        left.addView(text(versionLine(), 12, MUTED, fontBold), withTop(dp(6)));
        for (int i = 0; i < PANEL_NAMES.length; ++i) {
            final int index = i;
            TextView item = text(PANEL_NAMES[i], 21, MENU_GREY, fontBlack);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(16), 0, dp(12), 0);
            item.setSingleLine(true);
            item.setFocusable(true);
            item.setClickable(true);
            item.setOnClickListener(v -> showPanel(index));
            navItems[i] = item;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(44));
            params.topMargin = dp(index == 0 ? 14 : 2);
            left.addView(item, params);
        }
        ScrollView leftScroll = new ScrollView(this);
        leftScroll.addView(left);
        body.addView(leftScroll, new LinearLayout.LayoutParams(0, -1, 0.85f));

        // Right: one panel at a time, on dark glass.
        FrameLayout host = new FrameLayout(this);
        GradientDrawable glass = rounded(0xD90B0C10, dp(16));
        glass.setStroke(dp(1), 0x26FFFFFF);
        host.setBackground(glass);
        View[] contents = {buildPlayPanel(), buildFilesPanel(), buildGraphicsPanel(), buildDriverPanel(), buildAboutPanel()};
        for (int i = 0; i < contents.length; ++i) {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            scroll.addView(contents[i]);
            panels[i] = scroll;
            host.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        }
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, -1, 1.25f);
        rightParams.leftMargin = dp(24);
        body.addView(host, rightParams);

        screen.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        screen.addView(buildHintBar(), withTop(dp(6)));
        root.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        showPanel(PANEL_PLAY);
        return root;
    }

    /** Shows one menu panel: the selected item turns amber, like GTA IV's orange. */
    private void showPanel(int index) {
        panel = index;
        for (int i = 0; i < panels.length; ++i) {
            if (panels[i] != null) panels[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            TextView item = navItems[i];
            if (item == null) continue;
            boolean selected = i == index;
            item.setTextColor(selected ? AMBER : MENU_GREY);
            item.setText(selected ? PANEL_NAMES[i] + "  \u203A" : PANEL_NAMES[i]);
            item.setBackground(selected ? menuSelectedBackground() : focusBackground(Color.TRANSPARENT));
        }
    }

    private View buildPlayPanel() {
        LinearLayout panel = panel("READY TO RACE");
        status = text("", 15, TEXT, fontBold);
        panel.addView(status, withTop(dp(8)));
        checksList = vertical();
        panel.addView(checksList, withTop(dp(8)));

        TextView playButton = text("PLAY", 32, AMBER_DARK, fontBlack);
        playButton.setGravity(Gravity.CENTER);
        playButton.setLetterSpacing(.12f);
        playButton.setBackground(focusBackground(AMBER));
        playButton.setFocusable(true);
        playButton.setClickable(true);
        playButton.setContentDescription("Play");
        playButton.setOnClickListener(v -> play());
        play = playButton;
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(-1, dp(78));
        playParams.topMargin = dp(14);
        panel.addView(playButton, playParams);

        access = button("ALLOW ACCESS TO INTERNAL STORAGE/NFSMW", false, this::requestAccess);
        panel.addView(access, sized(dp(54), dp(12)));
        panel.addView(text("Tip: in game, open the touch controls editor to move, resize and save your layouts.",
                13, MUTED, fontBold), withTop(dp(14)));
        return panel;
    }

    private View buildFilesPanel() {
        LinearLayout panel = panel("GAME FILES");
        filesStatus = text("", 14, TEXT, fontBold);
        panel.addView(filesStatus, withTop(dp(8)));
        filesList = vertical();
        panel.addView(filesList, withTop(dp(8)));
        panel.addView(text("NFSMW Refined needs your own copy of Need for Speed: Most Wanted (2005) for PC: "
                + "Speed.exe and its GLOBAL, TRACKS, CARS, FRONTEND and LANGUAGES folders, copied straight into "
                + "internal storage/nfsmw (not inside another folder). The game runs in English, so "
                + "LANGUAGES/English.bin must be there. No game files are included or downloaded.",
                13, MUTED, fontBold), withTop(dp(12)));
        return panel;
    }

    private View buildGraphicsPanel() {
        LinearLayout panel = panel("GRAPHICS & CONTROLS");
        panel.addView(text("Applied the next time you press PLAY.", 12, MUTED, fontBold), withTop(dp(2)));
        optionsList = vertical();
        panel.addView(optionsList, withTop(dp(8)));
        return panel;
    }

    private View buildDriverPanel() {
        LinearLayout panel = panel("DRIVER");
        driverList = vertical();
        panel.addView(driverList, withTop(dp(8)));
        return panel;
    }

    private View buildAboutPanel() {
        LinearLayout panel = panel("ABOUT");
        aboutList = vertical();
        panel.addView(aboutList, withTop(dp(8)));
        return panel;
    }

    /** GTA IV's hint bar: MOVE, A SELECT, B BACK, with the credits on the right. */
    private View buildHintBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        bar.addView(text("\u25C0 \u25B6  MOVE", 15, TEXT, fontBlack));
        bar.addView(hintButton("A", 0xFF46A03C, "SELECT"));
        bar.addView(hintButton("B", 0xFFC8322D, "BACK"));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView credits = text("Port by codepdbh  \u00B7  Refined by prollynotzjay", 13, MUTED, fontBold);
        credits.setFocusable(true);
        credits.setClickable(true);
        credits.setPadding(dp(10), dp(6), dp(10), dp(6));
        credits.setBackground(focusBackground(Color.TRANSPARENT));
        credits.setOnClickListener(v -> showPanel(PANEL_ABOUT));
        bar.addView(credits);
        return bar;
    }

    private View hintButton(String letter, int color, String label) {
        LinearLayout group = new LinearLayout(this);
        group.setGravity(Gravity.CENTER_VERTICAL);
        group.setPadding(dp(22), 0, 0, 0);
        TextView circle = text(letter, 13, TEXT, fontBlack);
        circle.setGravity(Gravity.CENTER);
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(color);
        circle.setBackground(oval);
        group.addView(circle, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView name = text(label, 15, TEXT, fontBlack);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-2, -2);
        nameParams.leftMargin = dp(8);
        group.addView(name, nameParams);
        return group;
    }

    private String versionLine() {
        String soc = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : null;
        return "v" + ReleaseUpdates.installed(this) + "  \u00B7  " + Build.MODEL
                + (soc != null && !soc.isEmpty() && !"unknown".equals(soc) ? "  \u00B7  " + soc : "");
    }

    // ---- State -------------------------------------------------------------------------------------------

    private void refresh() {
        if (checksList == null) return;
        boolean allowed = allowed();
        File root = gameRoot();
        boolean exe = allowed && new File(root, "Speed.exe").isFile();
        StringBuilder missing = new StringBuilder();
        for (String folder : new String[] {"GLOBAL", "TRACKS", "CARS", "FRONTEND", "LANGUAGES"})
            if (!(allowed && new File(root, folder).isDirectory())) missing.append(missing.length() == 0 ? "" : ", ").append(folder);
        boolean english = allowed && new File(root, "LANGUAGES/" + GameOptions.ENGLISH + ".bin").isFile();
        boolean ready = exe && english;
        if (allowed && !root.isDirectory()) root.mkdirs();  // Show the player where the files go.
        String nested = allowed && !exe ? nestedGameFolder(root) : null;
        String message = !allowed ? "Allow file access so the launcher can read internal storage/nfsmw."
                : ready ? "Game found in internal storage/nfsmw."
                : nested != null ? tr("The files are inside \"%s\": move its contents directly into internal storage/nfsmw.", nested)
                : exe && !english ? "LANGUAGES/English.bin is missing. NFSMW Refined runs the game in English: "
                        + "copy the English language file from your PC install."
                : tr("Copy your game files (Speed.exe and its folders) into:\n%s", root.getAbsolutePath());
        status.setText(message);
        filesStatus.setText(allowed ? root.getAbsolutePath() : message);
        for (LinearLayout list : new LinearLayout[] {checksList, filesList}) {
            list.removeAllViews();
            addCheck(list, "Speed.exe", exe);
            addCheck(list, missing.length() == 0 ? "Game folders" : tr("Missing folders: %s", missing), missing.length() == 0);
            addCheck(list, "LANGUAGES/English.bin", english);
        }
        play.setEnabled(ready);
        play.setAlpha(ready ? 1f : .45f);
        access.setVisibility(allowed ? View.GONE : View.VISIBLE);
        refreshOptions();
    }

    private void refreshOptions() {
        if (optionsList == null) return;
        optionsList.removeAllViews();
        for (GameOptions.Option option : GameOptions.ALL)
            optionsList.addView(optionRow(option.title, option.label(GameOptions.get(this, option.key)), () -> chooseOption(option)));
        if (MaliProfile.isMali(this)) {
            optionsList.addView(optionRow("Mali profile", MaliProfile.describe(this), () -> {
                MaliProfile.apply(this);
                refreshOptions();
                message("Mali profile", "Settings tuned again for " + MaliProfile.renderer(this) + ".");
            }));
        }

        driverList.removeAllViews();
        GpuDrivers.Driver driver = GpuDrivers.selected(this);
        String gpu = MaliProfile.renderer(this);
        driverList.addView(text("GPU: " + (gpu.isEmpty() ? "unknown" : gpu), 14, TEXT, fontBold));
        if (MaliProfile.isMali(this)) {
            driverList.addView(text("Mali GPUs use the phone's own Vulkan driver. Custom driver packages "
                    + "(Turnip / AdrenoTools) only work on Adreno, so they are hidden here.", 13, MUTED, fontBold), withTop(dp(6)));
        } else if (android.os.Process.is64Bit()) {
            // Custom drivers (libadrenotools) exist only in the 64-bit build.
            driverList.addView(optionRow("Vulkan driver", driver == null ? "System" : driver.name, this::chooseDriver));
        }
        driverList.addView(optionRow("Test driver", "GPU and Vulkan features", () -> probeDriver(false)));

        aboutList.removeAllViews();
        aboutList.addView(text(versionLine(), 14, TEXT, fontBold));
        TextView tag = text("Refined", 20, AMBER, fontTag);
        aboutList.addView(tag, withTop(dp(4)));
        aboutList.addView(text("Need for Speed: Most Wanted (2005, PC) running natively on Android through static "
                + "recompilation: no emulator. English only and offline, as close to the original game as possible.",
                13, MUTED, fontBold), withTop(dp(6)));
        aboutList.addView(text("Original recompilation and Android port: codepdbh\n"
                + "NFSMW Refined (English, offline, Mali, launcher): prollynotzjay\n"
                + "pcrecomp: sp00nznet  \u00B7  HUD scaling after ThirteenAG's WidescreenFix",
                13, TEXT, fontBold), withTop(dp(10)));
        aboutList.addView(optionRow("prollynotzjay on GitHub", "NFSMW Refined", () -> openUrl(Uri.parse(REPO))));
        aboutList.addView(optionRow("codepdbh on GitHub", "Original project", () -> openUrl(Uri.parse(ORIGINAL_REPO))));
        aboutList.addView(optionRow("Check for updates",
                checkingUpdates ? "Checking GitHub…" : tr("Version %s", ReleaseUpdates.installed(this)), () -> checkUpdates(true)));
        aboutList.addView(optionRow("Report a problem", "Full log to GitHub", () -> prepareReport(null)));
        aboutList.addView(text("Need for Speed is a trademark of Electronic Arts. This project is not affiliated "
                + "with EA and includes no game files.", 11, MUTED, fontBold), withTop(dp(12)));
    }

    /** A copy one folder too deep (nfsmw/<game folder>/Speed.exe), a common mistake when copying. */
    private static String nestedGameFolder(File root) {
        File[] children = root.listFiles();
        if (children == null) return null;
        for (File child : children)
            if (child.isDirectory() && (new File(child, "Speed.exe").isFile() || new File(child, "speed.exe").isFile()))
                return "nfsmw/" + child.getName();
        return null;
    }

    private String tr(String text, Object... args) { return Lang.t(this, text, args); }

    private void chooseOption(GameOptions.Option option) {
        String current = GameOptions.get(this, option.key);
        int checked = 0;
        for (int i = 0; i < option.values.length; i++) if (option.values[i].equals(current)) checked = i;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(tr(option.title))
                .setSingleChoiceItems(Lang.t(this, option.labels), checked, (dialog, which) -> {
                    GameOptions.set(this, option.key, option.values[which]);
                    dialog.dismiss(); refreshOptions();
                }).setNegativeButton(tr("Cancel"), null).show();
    }

    private void chooseDriver() {
        List<GpuDrivers.Driver> drivers = GpuDrivers.list(this);
        String[] names = new String[drivers.size() + 1];
        names[0] = tr("System driver");
        int selected = 0;
        for (int i = 0; i < drivers.size(); ++i) {
            names[i + 1] = drivers.get(i).name;
            if (drivers.get(i).id.equals(GpuDrivers.selectedId(this))) selected = i + 1;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(tr("Vulkan driver"))
                .setSingleChoiceItems(names, selected, (dialog, which) -> {
                    GpuDrivers.select(this, which == 0 ? "" : drivers.get(which - 1).id);
                    refreshOptions(); dialog.dismiss();
                })
                .setPositiveButton(tr("Import ZIP"), (dialog, which) -> startActivityForResult(
                        new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),
                        REQUEST_DRIVER_ZIP))
                .setNeutralButton(tr("Delete selected"), (dialog, which) -> {
                    GpuDrivers.Driver driver = GpuDrivers.selected(this);
                    if (driver == null) return;
                    try { GpuDrivers.remove(this, driver); refreshOptions(); }
                    catch (java.io.IOException error) { message(tr("Vulkan driver"), error.getMessage()); }
                })
                .setNegativeButton(tr("Back"), null).show();
    }

    private void probeDriver(boolean thenPlay) {
        playAfterProbe = thenPlay;
        play.setEnabled(false);
        startActivityForResult(new Intent(this, GpuProbeActivity.class), REQUEST_DRIVER_PROBE);
    }

    private void showDriverReport(String report) {
        play.setEnabled(true);
        try {
            org.json.JSONObject gpu = new org.json.JSONObject(report);
            boolean failed = gpu.optBoolean("driverLoadFailed") || gpu.has("probeError");
            if (playAfterProbe && !failed && gpu.optBoolean("compatible")) { playAfterProbe = false; launch(); return; }
            // The player pressed PLAY: a driver that loads but lacks a feature the
            // probe checks may still run the game (issue #1107), so offer it.
            boolean wantedToPlay = playAfterProbe && !failed;
            playAfterProbe = false;
            String text = failed ? tr("The driver could not be used: %s", gpu.optString("probeError", tr("Vulkan failed to start.")))
                    : "GPU: " + gpu.optString("gpu") + "\nVulkan: " + gpu.optString("vulkan")
                      + "\nDriver: " + gpu.optString("driverName", "—") + " " + gpu.optString("driverInfo", "")
                      + "\n" + tr("BC textures: ") + (gpu.optBoolean("bcTextures") ? tr("on the GPU") : tr("converted on the CPU"))
                      + (gpu.optString("vertexFormatsMissing").isEmpty() ? ""
                              : "\n" + tr("Unsupported vertex types: ") + gpu.optString("vertexFormatsMissing"))
                      + "\n" + (gpu.optBoolean("compatible") ? tr("Compatible with the game.")
                              : tr("Missing features: ") + gpu.optJSONArray("missing"));
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(tr("Vulkan driver test")).setMessage(text).setPositiveButton(tr("OK"), null)
                    .setNeutralButton(tr("Use system driver"), (dialog, which) -> { GpuDrivers.select(this, ""); refreshOptions(); })
                    .setNegativeButton(wantedToPlay ? tr("Play anyway") : null, wantedToPlay ? (dialog, which) -> launch() : null)
                    .show();
        } catch (Exception error) { message(tr("Vulkan driver test"), tr("Invalid diagnostics: ") + error.getMessage()); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DRIVER_PROBE) {
            showDriverReport(resultCode == RESULT_OK && data != null ? data.getStringExtra("gpu_report")
                    : "{\"probeError\":\"" + tr("The test closed or was cancelled.") + "\",\"driverLoadFailed\":true}");
        } else if (requestCode == REQUEST_DRIVER_ZIP && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri zip = data.getData();
            status.setText(tr("Importing Vulkan driver…"));
            worker.execute(() -> {
                try {
                    GpuDrivers.Driver driver = GpuDrivers.importZip(this, zip);
                    main.post(() -> {
                        GpuDrivers.select(this, driver.id); refresh();
                        status.setText(tr("Driver imported: %s. Use Test driver to check it.", driver.name));
                    });
                } catch (Exception error) {
                    main.post(() -> { refresh(); message(tr("Vulkan driver"), tr("Could not import the driver: ") + error.getMessage()); });
                }
            });
        }
    }

    // ---- Updates and reports ---------------------------------------------------------------------------

    private void checkUpdates(boolean manual) {
        if (checkingUpdates) return;
        checkingUpdates = true;
        if (optionsList != null) refreshOptions();
        ReleaseUpdates.check(this, manual, result -> {
            checkingUpdates = false;
            if (isFinishing() || isDestroyed()) return;
            refreshOptions();
            if (result.error != null) { if (manual) message(tr("Updates"), result.error); return; }
            if (!ReleaseVersion.newer(result.tag, ReleaseUpdates.installed(this))) {
                if (manual) message(tr("Updates"), tr("You have version %s. There is no newer version on GitHub.", ReleaseUpdates.installed(this)));
                return;
            }
            android.content.SharedPreferences updates = ReleaseUpdates.prefs(this);
            if (!manual && result.tag.equals(updates.getString("notified_tag", ""))) return;
            updates.edit().putString("notified_tag", result.tag).apply();
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(tr("New version available: %s", result.tag))
                    .setMessage(tr("You can download it from GitHub or keep playing this version. Install the APK over the current one to keep your saves and settings."))
                    .setPositiveButton(tr("Update"), (dialog, which) -> openUrl(Uri.parse(result.url)))
                    .setNegativeButton(tr("Later"), null).show();
        });
    }

    /**
     * Builds the report ZIP, saves it to Download/NFSMW and opens a pre-filled GitHub issue. GitHub
     * cannot take an attachment through the link, so the issue already carries a technical summary
     * and the player is told where the ZIP is to attach it.
     */
    private void prepareReport(String crash) {
        status.setText(tr("Preparing the log…"));
        worker.execute(() -> {
            try {
                File report = Diagnostics.createReport(this);
                String location = Diagnostics.saveToDownloads(this, report);
                Uri issue = Diagnostics.issueUri(this, crash, location);
                main.post(() -> {
                    refresh();
                    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle(tr("Report on GitHub"))
                            .setMessage(tr("The full log was saved to:\n%s\n\nA GitHub issue will open with a technical summary already written. Describe what happened and, if you can, attach that ZIP (GitHub does not allow attaching it automatically).", location))
                            .setPositiveButton(tr("Open GitHub"), (dialog, which) -> openUrl(issue))
                            .setNegativeButton(tr("Cancel"), null).show();
                });
            } catch (Exception error) {
                main.post(() -> { refresh(); message(tr("Report a problem"), tr("Could not prepare the log: ") + error.getMessage()); });
            }
        });
    }

    private void openUrl(Uri uri) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (android.content.ActivityNotFoundException error) { message("GitHub", tr("No browser available to open %s", uri)); }
    }

    // ---- Launch ----------------------------------------------------------------------------------------

    private void play() {
        if (android.os.Process.is64Bit() && GpuDrivers.selected(this) != null) probeDriver(true);
        else launch();
    }

    private void launch() {
        // Each launch uses a fresh guest machine in the dedicated :game process.
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        List<android.app.ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes != null) for (android.app.ActivityManager.RunningAppProcessInfo process : processes)
            if (process.uid == android.os.Process.myUid() && process.processName.equals(getPackageName() + ":game"))
                android.os.Process.killProcess(process.pid);
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        int[] size = GameOptions.renderSize(this, Math.max(metrics.widthPixels, metrics.heightPixels),
                Math.min(metrics.widthPixels, metrics.heightPixels));
        String[] loader = GpuDrivers.loaderArguments(this);
        startActivity(new Intent(this, GameActivity.class)
                .putExtra("language", GameOptions.language(this))
                .putExtra("renderWidth", size[0]).putExtra("renderHeight", size[1])
                .putExtra("frameCap", Integer.parseInt(GameOptions.get(this, GameOptions.FPS)))
                .putExtra("widescreen", GameOptions.get(this, GameOptions.WIDESCREEN).equals("on"))
                .putExtra("minimapTop", GameOptions.get(this, GameOptions.MINIMAP).equals("top"))
                .putExtra("backBuffers", Integer.parseInt(GameOptions.get(this, GameOptions.LATENCY)))
                .putExtra("cpuTextures", GameOptions.get(this, GameOptions.TEXTURES).equals("cpu"))
                .putExtra("padJoystick", GameOptions.get(this, GameOptions.GAMEPAD).equals("directinput"))
                .putExtra("driverLoader", loader));
    }

    // ---- Storage ---------------------------------------------------------------------------------------

    private static File gameRoot() { return new File(Environment.getExternalStorageDirectory(), "nfsmw"); }

    private boolean allowed() {
        return Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager()
            : checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAccess() {
        if (Build.VERSION.SDK_INT >= 30)
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
        else requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        refresh();
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    // ---- Widgets ---------------------------------------------------------------------------------------

    private LinearLayout panel(String title) {
        LinearLayout panel = vertical();
        panel.setPadding(dp(22), dp(18), dp(22), dp(18));
        panel.addView(text(title, 24, TEXT, fontBlack));
        View underline = new View(this);
        underline.setBackgroundColor(AMBER);
        panel.addView(underline, new LinearLayout.LayoutParams(dp(48), dp(3)) {{ topMargin = dp(4); }});
        return panel;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void addCheck(LinearLayout list, String name, boolean ok) {
        TextView row = text((ok ? "\u2713  " : "\u2717  ") + name, 14, ok ? OK : BAD, fontBold);
        row.setPadding(0, dp(2), 0, dp(2));
        list.addView(row);
    }

    private View optionRow(String title, String value, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setBackground(focusBackground(0x14FFFFFF));
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnClickListener(v -> onClick.run());
        row.addView(text(title, 15, TEXT, fontBold), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView current = text(value + "  \u203A", 14, AMBER, fontBlack);
        current.setGravity(Gravity.END);
        row.addView(current, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView button(String label, boolean primary, Runnable onClick) {
        TextView button = text(label, 15, primary ? AMBER_DARK : TEXT, fontBlack);
        button.setGravity(Gravity.CENTER);
        button.setBackground(focusBackground(primary ? AMBER : 0x22FFFFFF));
        button.setFocusable(true);
        button.setClickable(true);
        button.setOnClickListener(v -> onClick.run());
        return button;
    }

    /** Fill, with a white outline while focused (controller) and a lighter fill while pressed. */
    private Drawable focusBackground(int fill) {
        GradientDrawable normal = rounded(fill, dp(12));
        GradientDrawable focused = rounded(fill == Color.TRANSPARENT ? 0x14FFFFFF : fill, dp(12));
        focused.setStroke(dp(2), 0xFFFFFFFF);
        GradientDrawable pressed = rounded(fill == Color.TRANSPARENT ? 0x33FFFFFF : fill, dp(12));
        pressed.setStroke(dp(3), AMBER);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_pressed}, pressed);
        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[] {}, normal);
        return states;
    }

    /** Amber tint with a bar on the left; an amber outline while focused. */
    private Drawable menuSelectedBackground() {
        StateListDrawable states = new StateListDrawable();
        for (boolean focused : new boolean[] {true, false}) {
            GradientDrawable fill = rounded(AMBER_TINT, dp(10));
            if (focused) fill.setStroke(dp(2), AMBER);
            LayerDrawable layers = new LayerDrawable(new Drawable[] {fill, rounded(AMBER, dp(2))});
            layers.setLayerGravity(1, Gravity.LEFT | Gravity.FILL_VERTICAL);
            layers.setLayerWidth(1, dp(5));
            states.addState(focused ? new int[] {android.R.attr.state_focused} : new int[] {}, layers);
        }
        return states;
    }

    private static GradientDrawable rounded(int fill, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private TextView text(String value, float sp, int color, Typeface typeface) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * UI_SCALE);
        view.setTextColor(color);
        if (typeface != null) view.setTypeface(typeface);
        return view;
    }

    private LinearLayout.LayoutParams withTop(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = top;
        return params;
    }

    private LinearLayout.LayoutParams sized(int height, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, height);
        params.topMargin = top;
        return params;
    }

    private void message(String title, String text) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setMessage(text).setPositiveButton("OK", null).show();
    }

    private int dp(int value) { return Math.round(value * UI_SCALE * getResources().getDisplayMetrics().density); }
}
