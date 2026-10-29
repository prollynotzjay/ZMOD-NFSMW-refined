package com.nfsmw.androidevolved;

import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import java.util.Locale;

/**
 * Mali support in the launcher. Reads the GPU name once (a 1x1 off-screen OpenGL ES context, which every
 * Android phone has) and, the first time the launcher runs on a Mali GPU, applies settings that suit it:
 *
 *  - Mali has no BC (DXT) texture compression, so textures are always converted on the CPU there.
 *  - Custom Vulkan drivers (libadrenotools / Turnip) are Adreno only, so the launcher hides them on Mali.
 *  - Resolution and frame cap follow the GPU generation: Valhall / 5th-gen Mali (G57, G68, G610, G710, G720,
 *    G615, G925, Immortalis) get 720p / 60 FPS; older Bifrost and Midgard (G52, G72, G76, T8xx) get 480p / 30 FPS.
 *
 * The player can change every option afterwards; the profile is applied once per GPU, never on top of the
 * player's own choices. "Mali profile" in the launcher applies it again on demand.
 */
final class MaliProfile {
    enum Tier { NOT_MALI, MALI_OLD, MALI_MODERN }

    private static final String PREFS = "nfsmw_mali";
    private static String cachedRenderer;

    private MaliProfile() {}

    /** The OpenGL ES renderer string, e.g. "Mali-G720-Immortalis MC12" or "Adreno (TM) 740". */
    static synchronized String renderer(Context context) {
        if (cachedRenderer != null) return cachedRenderer;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String fingerprint = android.os.Build.FINGERPRINT;
        if (fingerprint.equals(prefs.getString("fingerprint", ""))) {
            cachedRenderer = prefs.getString("renderer", "");
            return cachedRenderer;
        }
        cachedRenderer = queryRenderer();
        prefs.edit().putString("fingerprint", fingerprint).putString("renderer", cachedRenderer).apply();
        return cachedRenderer;
    }

    static Tier tier(Context context) {
        String gpu = renderer(context).toLowerCase(Locale.ROOT);
        if (!gpu.contains("mali") && !gpu.contains("immortalis")) return Tier.NOT_MALI;
        // Midgard (T6xx-T8xx) and Bifrost (G31, G51, G52, G71, G72, G76).
        if (gpu.matches(".*mali-t\\d.*") || gpu.matches(".*mali-g(31|51|52|71|72|76)\\b.*")) return Tier.MALI_OLD;
        return Tier.MALI_MODERN;
    }

    static boolean isMali(Context context) {
        return tier(context) != Tier.NOT_MALI;
    }

    /** Applies the profile the first time the launcher sees this Mali GPU. True if it changed anything. */
    static boolean applyIfNew(Context context) {
        if (!isMali(context)) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String gpu = renderer(context);
        if (gpu.equals(prefs.getString("applied_for", null))) return false;
        apply(context);
        return true;
    }

    /** Writes the Mali settings (also used by the launcher's "Mali profile" button). */
    static void apply(Context context) {
        Tier tier = tier(context);
        if (tier == Tier.NOT_MALI) return;
        boolean modern = tier == Tier.MALI_MODERN;
        GameOptions.set(context, GameOptions.RESOLUTION, modern ? "720" : "480");
        GameOptions.set(context, GameOptions.FPS, modern ? "60" : "30");
        GameOptions.set(context, GameOptions.TEXTURES, "cpu");
        GameOptions.set(context, GameOptions.LATENCY, "2");
        GpuDrivers.select(context, "");  // the phone's own Mali driver
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("applied_for", renderer(context)).apply();
    }

    static String describe(Context context) {
        switch (tier(context)) {
            case MALI_MODERN: return "720p · 60 FPS · CPU textures";
            case MALI_OLD: return "480p · 30 FPS · CPU textures (older Mali)";
            default: return "Not a Mali GPU";
        }
    }

    private static String queryRenderer() {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) return "";
        EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        try {
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return "";
            int[] attributes = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return "";
            eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[] {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (eglContext == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE) return "";
            if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) return "";
            String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
            return renderer == null ? "" : renderer;
        } catch (RuntimeException error) {
            return "";
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext);
            EGL14.eglTerminate(display);
        }
    }
}
