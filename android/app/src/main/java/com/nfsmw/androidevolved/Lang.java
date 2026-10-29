package com.nfsmw.androidevolved;

import android.content.Context;
import java.util.Locale;

/**
 * NFSMW Refined is English only. The launcher and touch-control texts are written in English in the
 * source; t() only fills the %s / %d fields. The context argument is kept so callers stay unchanged.
 */
final class Lang {
    private Lang() {}

    /** The text with its %s / %d fields filled. */
    static String t(Context context, String text, Object... args) {
        return args.length == 0 ? text : String.format(Locale.ROOT, text, args);
    }

    static String[] t(Context context, String[] texts) {
        return texts.clone();
    }
}
