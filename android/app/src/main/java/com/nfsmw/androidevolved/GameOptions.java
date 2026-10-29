package com.nfsmw.androidevolved;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Launcher options. GameActivity reads them when the game process starts and hands them to the native
 * runtime before the guest boots; changing one takes effect on the next PLAY.
 */
final class GameOptions {
    static final class Option {
        final String key, title, defaultValue;
        final String[] values, labels;
        Option(String key, String title, String defaultValue, String[] values, String[] labels) {
            this.key = key; this.title = title; this.defaultValue = defaultValue; this.values = values; this.labels = labels;
        }
        String label(String value) {
            for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return labels[i];
            return value;
        }
    }

    static final String RESOLUTION = "resolution", FPS = "fps", WIDESCREEN = "widescreen", LATENCY = "latency",
            TEXTURES = "textures", MINIMAP = "minimap", LANGUAGE = "language", GAMEPAD = "gamepad";

    static final Option[] ALL = {
            new Option(RESOLUTION, "Internal resolution", "75",
                    new String[] {"100", "75", "50", "720", "480", "360"},
                    new String[] {"Native · best quality", "75% · balanced", "50% · best performance",
                            "720p · fitted to the screen", "480p · performance", "360p · best performance"}),
            new Option(FPS, "FPS limit", "120",
                    new String[] {"30", "60", "90", "120", "0"},
                    new String[] {"30 FPS · saves battery", "60 FPS", "90 FPS", "120 FPS", "Unlimited"}),
            new Option(WIDESCREEN, "Widescreen", "on",
                    new String[] {"on", "off"},
                    new String[] {"Proportional HUD", "Original stretched HUD"}),
            new Option(MINIMAP, "Minimap", "top",
                    new String[] {"top", "bottom"},
                    new String[] {"Top left", "Bottom left (original)"}),
            new Option(LATENCY, "CPU/GPU sync", "2",
                    new String[] {"2", "1"},
                    new String[] {"Performance · CPU and GPU in parallel", "Low latency · fewer FPS"}),
            new Option(TEXTURES, "Compressed textures", "auto",
                    new String[] {"auto", "cpu"},
                    new String[] {"Automatic · GPU if supported", "Convert on CPU · compatibility"}),
            new Option(GAMEPAD, "Bluetooth/USB gamepad", "keys",
                    new String[] {"keys", "directinput"},
                    new String[] {"As keyboard · compatible", "DirectInput joystick · analog, experimental"}),
    };

    private static final String PREFS = "nfsmw_game";

    private GameOptions() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Option find(String key) {
        for (Option o : ALL) if (o.key.equals(key)) return o;
        throw new IllegalArgumentException(key);
    }

    static String get(Context context, String key) {
        Option o = find(key);
        String value = prefs(context).getString(key, o.defaultValue);
        for (String allowed : o.values) if (allowed.equals(value)) return value;
        return o.defaultValue;
    }

    static void set(Context context, String key, String value) {
        prefs(context).edit().putString(key, value).apply();
    }

    /** NFSMW Refined is English only: the game always loads LANGUAGES/English.bin. */
    static final String ENGLISH = "English";

    static String language(Context context) {
        return ENGLISH;
    }

    /** Render size for the selected resolution on a display of width × height (landscape). */
    static int[] renderSize(Context context, int width, int height) {
        String value = get(context, RESOLUTION);
        if (value.equals("720") || value.equals("480") || value.equals("360")) {
            int renderHeight = Integer.parseInt(value);
            return new int[] {Math.round((width * (float) renderHeight / height) / 2f) * 2, renderHeight};
        }
        float scale = Integer.parseInt(value) / 100f;
        return new int[] {Math.round(width * scale / 2f) * 2, Math.round(height * scale / 2f) * 2};
    }
}
