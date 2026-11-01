package com.nfsmw.androidevolved;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashMap;
import org.json.JSONObject;

/**
 * On-screen controls. The layout follows what the game is showing (GameFlow state read from the game):
 *   RACE    driving controls, in one of three schemes: touch buttons, edge steering or gyro steering
 *   PAUSE   menu controls over the race (after the pause button)
 *   MAP     the world map: big back button, compact arrows
 *   MENU    front end (main menu, car lot, dealership, safehouse...): arrows, accept, back
 *   LOADING nothing but the settings button, so loading screens stay clear
 * Everything hugs the screen edges so the HUD (minimap, speedometer, race position) stays visible.
 * One surface tracks independent fingers, so steering, pedals and buttons can be held together.
 */
final class RacingControlsView extends View {
    interface Keys { void set(int scan, boolean down); }
    /** Touches outside every control drive the game's mouse: x/y as fractions of the view, phase 0 down, 1 move, 2 up. */
    interface Pointer { void touch(float x, float y, int phase); }

    static final String BUTTONS = "buttons", EDGE = "edge", GYRO = "gyro";
    private enum Ctx { RACE, PAUSE, MAP, MENU, LOADING }
    private static final int MODE = -1, TEXT = -2, EDIT = -5;
    private static final int ROUND = 0, PEDAL = 1, ZONE = 2;
    private static final int OVERLAY_NONE = 0, OVERLAY_PAUSE = 1, OVERLAY_MAP = 2;
    private static final int ACCENT = Color.rgb(255, 176, 0);
    private static final int GAS_COLOR = 0xFF1E7A4A, BRAKE_COLOR = 0xFF8A2A2A;

    private final Keys keys;
    private Pointer pointer;
    private int pointerId = -1;
    private float pointerX, pointerY;
    private boolean pointerVisible;
    private final Runnable textInput, configureGyro, configureFrameLimit, schemeChanged;
    private String scheme;
    private boolean autoGas;
    private float opacity;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final ArrayList<Control> controls = new ArrayList<>();
    private final HashMap<Integer, Control> fingers = new HashMap<>();
    private Ctx ctx = Ctx.LOADING;
    private int gameState, overlay = OVERLAY_NONE;
    private boolean forceMenu, raceStarted, gasAuto;
    private boolean editing, hiddenByGamepad, gamepadConnected;
    private Control selected;
    private int dragPointer = -1;
    private float dragX, dragY;
    private final SharedPreferences layouts;
    private float scale = 1;

    private static final class Control {
        final RectF area;
        final String icon;
        final int scan, shape, color;
        final RectF original;
        String id;
        int held;
        Control(float x, float y, float w, float h, int shape, String icon, int scan, int color) {
            area = new RectF(x, y, x + w, y + h); original = new RectF(area);
            this.shape = shape; this.icon = icon; this.scan = scan; this.color = color;
        }
    }

    RacingControlsView(Context context, Keys keys, Runnable textInput, Runnable configureGyro,
                       Runnable configureFrameLimit, Runnable schemeChanged) {
        super(context);
        this.keys = keys; this.textInput = textInput; this.configureGyro = configureGyro;
        this.configureFrameLimit = configureFrameLimit; this.schemeChanged = schemeChanged;
        layouts = context.getSharedPreferences("touch_layouts_v1", Context.MODE_PRIVATE);
        scheme = readScheme(context);
        autoGas = layouts.getBoolean("autogas", true);
        opacity = layouts.getFloat("ui.opacity", .85f);
        setContentDescription(tr("Game controls"));
        setHapticFeedbackEnabled(true);
        line.setStyle(Paint.Style.STROKE); line.setStrokeCap(Paint.Cap.ROUND); line.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER); text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
    }

    /** The saved scheme; the launcher and the in-game options share it. */
    static String readScheme(Context context) {
        String value = context.getSharedPreferences("touch_layouts_v1", Context.MODE_PRIVATE).getString("scheme", BUTTONS);
        return EDGE.equals(value) || GYRO.equals(value) ? value : BUTTONS;
    }

    static String schemeName(String scheme) {
        return EDGE.equals(scheme) ? "Edge steering" : GYRO.equals(scheme) ? "Gyro steering" : "Touch buttons";
    }

    // ---- Context ----------------------------------------------------------------------------------------

    private static boolean loadingState(int state) {
        // GameFlow: 1 loading front end, 2 unloading front end, 4 loading region, 5 loading track,
        // 7 unloading track, 8 unloading region, 9 exit. 3 is the front end and 6 racing.
        return state == 0 || state == 1 || state == 2 || state == 4 || state == 5 || state == 7 || state == 8 || state == 9;
    }

    private Ctx computeCtx() {
        if (gameState == 6) return overlay == OVERLAY_PAUSE ? Ctx.PAUSE : overlay == OVERLAY_MAP ? Ctx.MAP : Ctx.RACE;
        if (forceMenu) return Ctx.MENU;
        return loadingState(gameState) ? Ctx.LOADING : Ctx.MENU;
    }

    /** The game's GameFlow state, polled a few times a second by GameActivity. */
    void setGameState(int state) {
        if (state == gameState) return;
        Log.i("NFSMW", "GameFlow state " + gameState + " -> " + state);
        if (state == 6 && gameState != 6) raceStarted = true;
        gameState = state; overlay = OVERLAY_NONE; forceMenu = false;
        if (!editing) applyContext();
    }

    private void applyContext() {
        Ctx next = computeCtx();
        if (next != ctx) { ctx = next; layoutControls(); } else updateAutoGas();
    }

    /** True once after a race starts: the gyro is recalibrated to the player's position. */
    boolean takeRaceStarted() { boolean value = raceStarted; raceStarted = false; return value; }

    boolean gyroActive() { return ctx == Ctx.RACE && !editing && !hiddenByGamepad && GYRO.equals(scheme); }

    String scheme() { return scheme; }

    private void toggleOverlay() {
        if (ctx == Ctx.LOADING || forceMenu) forceMenu = !forceMenu;
        else if (gameState == 6) overlay = overlay == OVERLAY_NONE ? OVERLAY_PAUSE : OVERLAY_NONE;
        applyContext();
    }

    // ---- Auto-accelerate ---------------------------------------------------------------------------------

    private boolean brakeHeld() {
        for (Control c : controls) if (c.scan == 0xd0 && c.held > 0) return true;
        return false;
    }

    /** Edge and gyro steering keep the accelerator down for you; braking lifts it. */
    private void updateAutoGas() {
        boolean want = ctx == Ctx.RACE && !editing && !hiddenByGamepad && !BUTTONS.equals(scheme) && autoGas && !brakeHeld();
        if (want != gasAuto) { gasAuto = want; keys.set(0xc8, want); }
    }

    // ---- Layout -----------------------------------------------------------------------------------------

    private String ctxKey() {
        switch (ctx) {
            case RACE: return "race." + scheme;
            case PAUSE: return "pause";
            case MAP: return "map";
            case MENU: return "menu";
            default: return "load";
        }
    }

    private void add(float x, float y, float size, String icon, int scan) { add(x, y, size, size, ROUND, icon, scan, 0); }

    private void add(float x, float y, float w, float h, int shape, String icon, int scan, int color) {
        Control c = new Control(x, y, w, h, shape, icon, scan, color);
        c.id = "v3." + ctxKey() + "." + icon;
        float factor = layouts.getFloat(c.id + ".size", 1);
        float cx = layouts.getFloat(c.id + ".x", c.area.centerX() / (getWidth() / scale)) * (getWidth() / scale);
        float cy = layouts.getFloat(c.id + ".y", c.area.centerY() / (getHeight() / scale)) * (getHeight() / scale);
        c.area.set(cx - w * factor / 2, cy - h * factor / 2, cx + w * factor / 2, cy + h * factor / 2);
        clamp(c); controls.add(c);
    }

    private void addZone(float x, float y, float w, float h, String icon, int scan) {
        controls.add(new Control(x, y, w, h, ZONE, icon, scan, 0));
    }

    private void clamp(Control c) {
        float w = getWidth() / scale, h = getHeight() / scale;
        float dx = c.area.left < 4 ? 4 - c.area.left : c.area.right > w - 4 ? w - 4 - c.area.right : 0;
        float dy = c.area.top < 4 ? 4 - c.area.top : c.area.bottom > h - 4 ? h - 4 - c.area.bottom : 0;
        c.area.offset(dx, dy);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { layoutControls(); }

    /** A physical gamepad is connected: get out of the way until the screen is touched. */
    void gamepadUsed() {
        if (!gamepadConnected || hiddenByGamepad || editing) return;
        releaseAll(); hiddenByGamepad = true; invalidate();
    }

    void setGamepadConnected(boolean connected) {
        if (gamepadConnected == connected) return;
        gamepadConnected = connected;
        hiddenByGamepad = connected && !editing;
        releaseAll();
    }

    private void layoutControls() {
        releaseFingers(); controls.clear(); selected = null; dragPointer = -1;
        if (getWidth() == 0) return;
        scale = Math.min(getResources().getDisplayMetrics().density, getWidth() / 820f);
        float w = getWidth() / scale, h = getHeight() / scale, m = 14;
        // Utility buttons, top right: settings, and the manual switch between driving and menu controls.
        add(w - m - 40, m - 2, 40, "settings", EDIT);
        if (ctx == Ctx.RACE || ctx == Ctx.PAUSE || ctx == Ctx.MAP || ctx == Ctx.LOADING || forceMenu)
            add(w - m - 88, m - 2, 40, ctx == Ctx.RACE ? "menu" : "wheel", MODE);
        switch (ctx) {
            case RACE: layoutRace(w, h, m); break;
            case PAUSE: layoutMenu(w, h, m, 54, false); break;
            case MAP: layoutMenu(w, h, m, 44, false); add(m, m - 2, 52, "back", 0x01); break;
            case MENU: layoutMenu(w, h, m, 54, true); break;
            default: break;
        }
        updateAutoGas();
        invalidate();
    }

    private void layoutMenu(float w, float h, float m, float pad, boolean keyboard) {
        float cx = m + pad * 1.5f, cy = h - m - pad * 1.5f;
        add(cx - pad / 2, cy - pad * 1.5f, pad, "up", 0xc8);
        add(cx - pad / 2, cy + pad * .5f, pad, "down", 0xd0);
        add(cx - pad * 1.5f, cy - pad / 2, pad, "left", 0xcb);
        add(cx + pad * .5f, cy - pad / 2, pad, "right", 0xcd);
        add(w - m - 78, h - m - 78, 78, "accept", 0x1c);
        if (ctx != Ctx.MAP) add(w - m - 78 - 66, h - m - 58, 58, "back", 0x01);   // Escape: back in menus
        if (keyboard) add(m, m - 2, 40, "keyboard", TEXT);
    }

    private void layoutRace(float w, float h, float m) {
        // Top left: pause, respot, map, messages. Small, so the minimap below them stays clear.
        add(m, m - 2, 40, "pause", 0x01);
        add(m + 46, m - 2, 40, "reset", 0x13);          // Respot: R
        add(m + 92, m - 2, 40, "map", 0x32);            // World map: M
        add(m + 138, m - 2, 40, "sms", 0x0f);           // Text messages: Tab
        boolean needGas = BUTTONS.equals(scheme) || !autoGas;
        if (BUTTONS.equals(scheme)) {
            add(m, h - m - 88, 88, "left", 0xcb);
            add(m + 96, h - m - 88, 88, "right", 0xcd);
            add(m, h - m - 156, 60, "handbrake", 0x39);   // Handbrake: Space
            add(m, h - m - 208, 44, "shiftUp", 0x2a);      // Left Shift
            add(m + 50, h - m - 208, 44, "shiftDown", 0x1d); // Left Control
            add(m + 100, h - m - 208, 44, "camera", 0x2e);
            // The pedals sit at the right edge; the speedometer is left of them.
            add(w - m - 80, h - m - 150, 80, 150, PEDAL, "gas", 0xc8, GAS_COLOR);
            add(w - m - 160, h - m - 112, 72, 112, PEDAL, "brake", 0xd0, BRAKE_COLOR);
            add(w - m - 66, h - m - 226, 66, "nitro", 0x38);   // Left Alt
            add(w - m - 128, h - m - 216, 54, "speedbreaker", 0x9d); // Right Control
        } else if (EDGE.equals(scheme)) {
            // Hold the left or right edge of the screen to steer; the buttons sit just inside the edges.
            float strip = Math.max(90, w * .15f);
            addZone(0, h * .2f, strip, h * .8f, "zoneLeft", 0xcb);
            addZone(w - strip, h * .2f, strip, h * .8f, "zoneRight", 0xcd);
            float right = w - strip - 8, top = h * .27f;
            add(right - 56, top, 56, "brake", 0xd0);
            add(right - 120, top, 56, "nitro", 0x38);
            add(right - 56, top + 62, 56, "handbrake", 0x39);
            add(right - 120, top + 62, 52, "speedbreaker", 0x9d);
            float left = strip + 8, row = h * .52f;
            add(left, row, 46, "shiftUp", 0x2a);
            add(left + 52, row, 46, "shiftDown", 0x1d);
            add(left + 104, row, 46, "camera", 0x2e);
            if (needGas) add(left, h - m - 66, 66, "gas", 0xc8);
        } else {
            // Gyro: the phone steers. Brake and nitro at the right edge, handbrake at the left.
            add(w - m - 84, h - m - 130, 84, 130, PEDAL, "brake", 0xd0, BRAKE_COLOR);
            add(w - m - 66, h - m - 206, 66, "nitro", 0x38);
            add(w - m - 128, h - m - 196, 54, "speedbreaker", 0x9d);
            add(m, h - m - 74, 74, "handbrake", 0x39);
            add(m, h - m - 130, 44, "shiftUp", 0x2a);
            add(m + 50, h - m - 130, 44, "shiftDown", 0x1d);
            add(m + 100, h - m - 130, 44, "camera", 0x2e);
            if (needGas) add(w - m - 84 - 82, h - m - 150, 76, 150, PEDAL, "gas", 0xc8, GAS_COLOR);
        }
    }

    // ---- Drawing ----------------------------------------------------------------------------------------

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (hiddenByGamepad && !editing) return;
        canvas.save(); canvas.scale(scale, scale);
        if (editing) canvas.drawColor(0x55000000);
        for (Control c : controls) if (c.shape == ZONE) drawZone(canvas, c);
        for (Control c : controls) if (c.shape != ZONE) drawControl(canvas, c);
        if (editing) {
            float w = getWidth() / scale;
            drawToolbarButton(canvas, new RectF(w / 2 - 250, 18, w / 2 - 150, 58), tr("RESET"));
            drawToolbarButton(canvas, new RectF(w / 2 - 142, 18, w / 2 - 102, 58), "−");
            drawToolbarButton(canvas, new RectF(w / 2 - 94, 18, w / 2 - 54, 58), "+");
            drawToolbarButton(canvas, new RectF(w / 2 - 46, 18, w / 2 + 58, 58), tr("SAVE"));
            drawToolbarButton(canvas, new RectF(w / 2 + 66, 18, w / 2 + 170, 58), tr("CANCEL"));
            drawToolbarButton(canvas, new RectF(w / 2 - 65, 70, w / 2 + 65, 106), tr("OPTIONS"));
        }
        canvas.restore();
        if (pointerVisible && ctx != Ctx.RACE && !editing) {
            path.reset(); path.moveTo(pointerX, pointerY);
            path.lineTo(pointerX + 8, pointerY + 28); path.lineTo(pointerX + 15, pointerY + 19);
            path.lineTo(pointerX + 27, pointerY + 18); path.close();
            fill.setStyle(Paint.Style.FILL); fill.setColor(Color.WHITE); canvas.drawPath(path, fill);
            line.setColor(Color.BLACK); line.setStrokeWidth(2); canvas.drawPath(path, line);
        }
    }

    /** Edge-steering strip: a faint glow from the screen edge with chevrons pointing the way it turns. */
    private void drawZone(Canvas canvas, Control c) {
        if (editing) return;
        boolean left = c.icon.equals("zoneLeft"), on = c.held > 0;
        int strong = on ? 0x55FFB000 : 0x22FFFFFF;
        fill.setStyle(Paint.Style.FILL);
        fill.setShader(new LinearGradient(left ? c.area.left : c.area.right, 0, left ? c.area.right : c.area.left, 0,
                strong, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(c.area, fill);
        fill.setShader(null);
        line.setColor(on ? 0xCCFFB000 : 0x55FFFFFF); line.setStrokeWidth(3);
        float cx = c.area.left + c.area.width() * (left ? .3f : .7f), cy = c.area.centerY();
        for (int i = -1; i <= 1; ++i)
            ControlIcons.chevronSide(canvas, path, line, cx + (left ? -1 : 1) * i * 12, cy, 18 - Math.abs(i) * 3, left);
    }

    private void drawControl(Canvas canvas, Control c) {
        boolean on = c.held > 0, picked = editing && c == selected;
        float cx = c.area.centerX(), cy = c.area.centerY(), r = Math.min(c.area.width(), c.area.height()) / 2;
        float alpha = on || editing ? 1f : opacity;
        int layer = canvas.saveLayerAlpha(c.area.left - 14, c.area.top - 14, c.area.right + 14, c.area.bottom + 14, (int) (alpha * 255));
        if (on) canvas.scale(.94f, .94f, cx, cy);
        int accent = ControlIcons.accent(c.icon);
        fill.setStyle(Paint.Style.FILL);
        if (c.shape == PEDAL) {
            float corner = c.area.width() * .3f;
            rect.set(c.area.left, c.area.top + 3, c.area.right, c.area.bottom + 3);
            fill.setColor(0x55000000); canvas.drawRoundRect(rect, corner, corner, fill);
            int base = (c.color & 0x00FFFFFF) | 0xE0000000;
            fill.setShader(new LinearGradient(0, c.area.top, 0, c.area.bottom,
                    on ? 0xF0FFC44D : lighten(base, .35f), on ? 0xF0FF8A00 : base, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(c.area, corner, corner, fill);
            fill.setShader(null);
            line.setStrokeWidth(2.2f); line.setColor(picked ? 0xFFFFCF69 : on ? 0xFFFFE2A8 : 0x99FFFFFF);
            canvas.drawRoundRect(c.area, corner, corner, line);
            // Grip ridges like a real pedal.
            line.setColor(on ? 0x88000000 : 0x44FFFFFF); line.setStrokeWidth(2.4f);
            float span = c.area.width() * .26f;
            for (int i = 0; i < 4; ++i) {
                float y = c.area.top + c.area.height() * (.55f + i * .1f);
                canvas.drawLine(cx - span, y, cx + span, y, line);
            }
            ControlIcons.draw(canvas, c.icon, cx, c.area.top + c.area.height() * .27f, c.area.width() * .5f, on, line, fill, path);
            canvas.restoreToCount(layer);
            return;
        }
        // Round button: soft shadow, glass face with a highlight, accent-tinted ring.
        fill.setColor(0x66000000); canvas.drawCircle(cx, cy + r * .07f, r, fill);
        fill.setShader(new RadialGradient(cx - r * .3f, cy - r * .45f, r * 1.5f,
                on ? new int[] {0xF0FFC44D, 0xF0FF8A00} : new int[] {0xCC323C4C, 0xD00A0F16}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, fill);
        fill.setShader(null);
        line.setStrokeWidth(Math.max(1.8f, r * .07f));
        line.setColor(picked ? 0xFFFFCF69 : on ? 0xFFFFE2A8 : (mix(accent, 0xFFFFFFFF, .45f) & 0x00FFFFFF) | 0xAA000000);
        canvas.drawCircle(cx, cy, r - line.getStrokeWidth() / 2, line);
        if (!on) {   // glass highlight along the top
            line.setColor(0x22FFFFFF); line.setStrokeWidth(Math.max(1.5f, r * .06f));
            canvas.drawArc(cx - r * .8f, cy - r * .8f, cx + r * .8f, cy + r * .8f, 200, 140, false, line);
        }
        ControlIcons.draw(canvas, c.icon, cx, cy, r, on, line, fill, path);
        canvas.restoreToCount(layer);
    }

    private static int lighten(int color, float amount) { return mix(color, 0xFFFFFFFF, amount); }

    private static int mix(int a, int b, float t) {
        return Color.argb((int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private void drawToolbarButton(Canvas canvas, RectF area, String label) {
        fill.setStyle(Paint.Style.FILL); fill.setColor(0xE014202C); canvas.drawRoundRect(area, 12, 12, fill);
        text.setColor(ACCENT); text.setTextSize(label.length() > 2 ? 12 : 24);
        canvas.drawText(label, area.centerX(), area.centerY() - (text.ascent() + text.descent()) / 2, text);
    }

    // ---- Editor -----------------------------------------------------------------------------------------

    private void resizeSelected(float change) {
        if (selected == null) return;
        float factor = Math.max(.55f, Math.min(2.5f, selected.area.width() / selected.original.width() + change));
        float cx = selected.area.centerX(), cy = selected.area.centerY();
        float w = selected.original.width() * factor, h = selected.original.height() * factor;
        selected.area.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2); clamp(selected);
    }

    private void saveLayout() {
        SharedPreferences.Editor save = layouts.edit();
        for (Control c : controls) {
            if (c.id == null) continue;
            save.putFloat(c.id + ".x", c.area.centerX() / (getWidth() / scale))
                    .putFloat(c.id + ".y", c.area.centerY() / (getHeight() / scale))
                    .putFloat(c.id + ".size", c.area.width() / c.original.width());
        }
        save.apply();
    }

    private void finishEditing() { editing = false; ctx = computeCtx(); layoutControls(); }

    private boolean editTouch(MotionEvent e) {
        int action = e.getActionMasked(), index = e.getActionIndex();
        float x = e.getX(index) / scale, y = e.getY(index) / scale, w = getWidth() / scale;
        if (action == MotionEvent.ACTION_DOWN) {
            if (x >= w / 2 - 65 && x <= w / 2 + 65 && y >= 70 && y <= 106) { showOptions(); return true; }
            if (y >= 18 && y <= 58) {
                boolean handled = true;
                if (x >= w / 2 - 250 && x <= w / 2 - 150) { for (Control c : controls) c.area.set(c.original); selected = null; }
                else if (x >= w / 2 - 142 && x <= w / 2 - 102) resizeSelected(-.1f);
                else if (x >= w / 2 - 94 && x <= w / 2 - 54) resizeSelected(.1f);
                else if (x >= w / 2 - 46 && x <= w / 2 + 58) { saveLayout(); finishEditing(); }
                else if (x >= w / 2 + 66 && x <= w / 2 + 170) finishEditing();
                else handled = false;
                if (handled) { invalidate(); return true; }
            }
            selected = hit(e.getX(index), e.getY(index));
            dragPointer = e.getPointerId(index); dragX = x; dragY = y;
        } else if (action == MotionEvent.ACTION_MOVE && selected != null && dragPointer >= 0) {
            int i = e.findPointerIndex(dragPointer);
            if (i >= 0) { x = e.getX(i) / scale; y = e.getY(i) / scale; selected.area.offset(x - dragX, y - dragY); clamp(selected); dragX = x; dragY = y; }
        } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP
                || (action == MotionEvent.ACTION_POINTER_UP && e.getPointerId(index) == dragPointer)) dragPointer = -1;
        invalidate(); return true;
    }

    private void showOptions() {
        String[] items = {
                tr("Control scheme: %s", schemeName(scheme)),
                tr("Gyro steering settings"),
                tr("Auto-accelerate (edge and gyro): %s", autoGas ? "ON" : "OFF"),
                tr("Control opacity: %d%%", Math.round(opacity * 100)),
                tr("FPS limit"),
                tr("Save layout with a name"),
                tr("Load custom layout")};
        new AlertDialog.Builder(getContext()).setTitle(tr("Control options"))
                .setItems(items, (dialog, which) -> {
                    if (which == 0) chooseScheme();
                    else if (which == 1) configureGyro.run();
                    else if (which == 2) {
                        autoGas = !autoGas; layouts.edit().putBoolean("autogas", autoGas).apply();
                        Toast.makeText(getContext(), tr("Auto-accelerate %s", autoGas ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
                        if (!editing) layoutControls();
                    }
                    else if (which == 3) chooseOpacity();
                    else if (which == 4) configureFrameLimit.run();
                    else if (which == 5) saveNamedLayout();
                    else loadNamedLayout();
                }).setNegativeButton(tr("Close"), null).show();
    }

    private void chooseScheme() {
        String[] ids = {BUTTONS, EDGE, GYRO};
        String[] names = {tr("Touch buttons · arrows and pedals"), tr("Edge steering · hold the left or right screen edge"),
                tr("Gyro steering · turn the phone like a wheel")};
        new AlertDialog.Builder(getContext()).setTitle(tr("Driving controls"))
                .setSingleChoiceItems(names, BUTTONS.equals(scheme) ? 0 : EDGE.equals(scheme) ? 1 : 2, (dialog, which) -> {
                    scheme = ids[which]; layouts.edit().putString("scheme", scheme).apply();
                    if (ctx == Ctx.RACE || editing) layoutControls();
                    schemeChanged.run(); dialog.dismiss();
                }).setNegativeButton(tr("Cancel"), null).show();
    }

    private void chooseOpacity() {
        float[] values = {.5f, .7f, .85f, 1f};
        String[] names = {"50%", "70%", "85%", "100%"};
        int current = 2;
        for (int i = 0; i < values.length; ++i) if (Math.abs(values[i] - opacity) < .01f) current = i;
        new AlertDialog.Builder(getContext()).setTitle(tr("Control opacity"))
                .setSingleChoiceItems(names, current, (dialog, which) -> {
                    opacity = values[which]; layouts.edit().putFloat("ui.opacity", opacity).apply();
                    invalidate(); dialog.dismiss();
                }).setNegativeButton(tr("Cancel"), null).show();
    }

    private void saveNamedLayout() {
        EditText name = new EditText(getContext()); name.setSingleLine(); name.setHint(tr("Layout name"));
        name.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(24)});
        AlertDialog dialog = new AlertDialog.Builder(getContext()).setTitle(tr("Save layout"))
                .setView(name).setPositiveButton(tr("Save"), null).setNegativeButton(tr("Cancel"), null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) { name.setError(tr("Enter a name")); return; }
            try {
                JSONObject data = new JSONObject(); data.put("scheme", scheme);
                for (Control c : controls) {
                    if (c.id == null) continue;
                    JSONObject item = new JSONObject();
                    item.put("x", c.area.centerX() / (getWidth() / scale)); item.put("y", c.area.centerY() / (getHeight() / scale));
                    item.put("size", c.area.width() / c.original.width()); data.put(c.id, item);
                }
                layouts.edit().putString("preset.v3." + value, data.toString()).apply();
                saveLayout(); Toast.makeText(getContext(), tr("Layout saved: %s", value), Toast.LENGTH_SHORT).show(); dialog.dismiss();
            } catch (org.json.JSONException exception) { name.setError(tr("The layout could not be saved")); }
        }));
        dialog.show();
    }

    private void loadNamedLayout() {
        String prefix = "preset.v3."; ArrayList<String> names = new ArrayList<>();
        for (String key : layouts.getAll().keySet()) if (key.startsWith(prefix)) names.add(key.substring(prefix.length()));
        java.util.Collections.sort(names);
        if (names.isEmpty()) { Toast.makeText(getContext(), tr("No layouts saved yet"), Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(getContext()).setTitle(tr("Load layout")).setItems(names.toArray(new String[0]), (dialog, which) -> {
            try {
                JSONObject data = new JSONObject(layouts.getString(prefix + names.get(which), "{}"));
                scheme = data.optString("scheme", scheme);
                SharedPreferences.Editor save = layouts.edit().putString("scheme", scheme);
                java.util.Iterator<String> ids = data.keys();
                while (ids.hasNext()) {
                    String id = ids.next(); if (id.equals("scheme")) continue;
                    JSONObject item = data.getJSONObject(id);
                    save.putFloat(id + ".x", (float) item.getDouble("x")).putFloat(id + ".y", (float) item.getDouble("y"))
                            .putFloat(id + ".size", (float) item.getDouble("size"));
                }
                save.apply(); layoutControls(); schemeChanged.run();
            } catch (org.json.JSONException exception) { Toast.makeText(getContext(), tr("The layout could not be loaded"), Toast.LENGTH_SHORT).show(); }
        }).setNegativeButton(tr("Cancel"), null).show();
    }

    // ---- Input ------------------------------------------------------------------------------------------

    /** The control under a point: buttons first (round ones use their circle, with a little slack), then edge zones. */
    private Control hit(float x, float y) {
        x /= scale; y /= scale; Control best = null; double bestDistance = Double.MAX_VALUE;
        for (Control c : controls) {
            if (c.shape == ZONE) continue;
            float cx = c.area.centerX(), cy = c.area.centerY();
            double d;
            if (c.shape == PEDAL) {
                if (Math.abs(x - cx) > c.area.width() * .56f || Math.abs(y - cy) > c.area.height() * .56f) continue;
                d = Math.hypot((x - cx) / c.area.width(), (y - cy) / c.area.height());
            } else {
                float r = Math.min(c.area.width(), c.area.height()) / 2;
                d = Math.hypot(x - cx, y - cy) / r; if (d > 1.12) continue;
            }
            if (d < bestDistance) { bestDistance = d; best = c; }
        }
        if (best != null || editing) return best;
        for (Control c : controls) if (c.shape == ZONE && c.area.contains(x, y)) return c;
        return null;
    }

    private void hold(Control c) {
        if (c == null || ++c.held != 1) return;
        if (c.scan >= 0) keys.set(c.scan, true);
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        updateAutoGas();
    }

    private void release(Control c) {
        if (c != null && --c.held == 0) {
            if (c.scan >= 0) keys.set(c.scan, false);
            updateAutoGas();
        }
    }

    void setPointer(Pointer pointer) { this.pointer = pointer; }

    private void pointerTouch(float x, float y, int phase) {
        if (pointer == null || getWidth() == 0 || getHeight() == 0) return;
        pointerX = x; pointerY = y; pointerVisible = true; invalidate(); pointer.touch(x / getWidth(), y / getHeight(), phase);
    }

    private void pointerRelease() { if (pointerId >= 0) { pointerId = -1; pointerTouch(pointerX, pointerY, 2); } }

    /** Releases fingers the event no longer carries (their ACTION_POINTER_UP never arrived). */
    private void releaseLiftedFingers(MotionEvent e) {
        java.util.Iterator<java.util.Map.Entry<Integer, Control>> it = fingers.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Integer, Control> finger = it.next();
            if (e.findPointerIndex(finger.getKey()) < 0) { release(finger.getValue()); it.remove(); }
        }
        if (pointerId >= 0 && e.findPointerIndex(pointerId) < 0) pointerRelease();
    }

    private void releaseFingers() {
        for (Control c : fingers.values()) release(c);
        fingers.clear(); pointerRelease(); invalidate();
    }

    /** Everything up, including the automatic accelerator (app paused, focus lost, pad connected). */
    void releaseAll() {
        releaseFingers();
        if (gasAuto) { gasAuto = false; keys.set(0xc8, false); }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (editing) return editTouch(e);
        int action = e.getActionMasked(), index = e.getActionIndex(), id = e.getPointerId(index);
        if (hiddenByGamepad) {
            if (action == MotionEvent.ACTION_DOWN) { hiddenByGamepad = false; updateAutoGas(); invalidate(); }
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) { releaseFingers(); return true; }
        // A new gesture means no finger is down: anything still held lost its ACTION_UP (edge gestures,
        // palm rejection, overlays) and would stay pressed, e.g. the left arrow steering by itself.
        if (action == MotionEvent.ACTION_DOWN) releaseFingers(); else releaseLiftedFingers(e);
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            Control c = hit(e.getX(index), e.getY(index));
            if (c == null && pointerId < 0) { pointerId = id; pointerTouch(e.getX(index), e.getY(index), 0); }
            release(fingers.put(id, c)); hold(c);
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < e.getPointerCount(); ++i) {
                int finger = e.getPointerId(i);
                if (finger == pointerId) { pointerTouch(e.getX(i), e.getY(i), 1); continue; }
                Control old = fingers.get(finger), next = hit(e.getX(i), e.getY(i));
                // Menu-style buttons do not slide; pedals, arrows and edge zones follow the thumb.
                if (old != null && old.scan < 0) continue;
                if (next != null && next.scan < 0) next = null;
                if (old != next) { release(old); fingers.put(finger, next); hold(next); }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            if (id == pointerId) { pointerId = -1; pointerTouch(e.getX(index), e.getY(index), 2); }
            Control c = fingers.remove(id); release(c);
            if (c != null && c.shape != ZONE && c == hit(e.getX(index), e.getY(index))) {
                if (c.scan == MODE) toggleOverlay();
                else if (c.scan == TEXT) { releaseFingers(); textInput.run(); }
                else if (c.scan == EDIT) { releaseAll(); editing = true; selected = null; }
                else if (c.scan == 0x01 && gameState == 6) { overlay = overlay == OVERLAY_NONE ? OVERLAY_PAUSE : OVERLAY_NONE; applyContext(); }
                else if (c.scan == 0x32 && gameState == 6 && ctx == Ctx.RACE) { overlay = OVERLAY_MAP; applyContext(); }
                performClick();
            }
            if (action == MotionEvent.ACTION_UP) releaseFingers();   // the last finger left
        }
        invalidate(); return true;
    }

    private String tr(String text, Object... args) { return Lang.t(getContext(), text, args); }

    @Override public boolean performClick() { super.performClick(); return true; }
}
