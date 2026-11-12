package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Device-local visibility switches for the current account's public/private
 * profile photos. These settings never modify Telegram server profile data.
 */
public final class LocalProfilePhotoSettings {
    private static final String PREFS = "telegramz_local_profile_photo_visibility";
    private static final String HIDE_PUBLIC = "hide_public_photo";
    private static final String HIDE_PRIVATE = "hide_private_photo";

    private LocalProfilePhotoSettings() {
    }

    private static SharedPreferences getPreferences(Context context, int account) {
        return context.getApplicationContext().getSharedPreferences(PREFS + "_" + account, Context.MODE_PRIVATE);
    }

    public static boolean isPublicHidden(Context context, int account) {
        return getPreferences(context, account).getBoolean(HIDE_PUBLIC, false);
    }

    public static boolean isPrivateHidden(Context context, int account) {
        return getPreferences(context, account).getBoolean(HIDE_PRIVATE, false);
    }

    /**
     * Changes only the requested photo's local visibility switch.
     * Public and private photo visibility are independent.
     */
    public static synchronized boolean setHidden(Context context, int account, boolean privatePhoto, boolean hidden) {
        String key = privatePhoto ? HIDE_PRIVATE : HIDE_PUBLIC;
        return getPreferences(context, account).edit().putBoolean(key, hidden).commit();
    }
}
