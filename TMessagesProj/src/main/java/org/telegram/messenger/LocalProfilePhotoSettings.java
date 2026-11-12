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
     * Changes one local visibility switch. Hiding one photo automatically
     * reveals the other, so at least one photo always remains visible. Do not
     * rely on UserFull photo metadata here because it may not have been loaded yet.
     */
    public static synchronized boolean setHidden(Context context, int account, boolean privatePhoto, boolean hidden) {
        SharedPreferences preferences = getPreferences(context, account);
        boolean otherHidden = preferences.getBoolean(privatePhoto ? HIDE_PUBLIC : HIDE_PRIVATE, false);
        if (hidden && otherHidden) {
            return false;
        }
        String key = privatePhoto ? HIDE_PRIVATE : HIDE_PUBLIC;
        String otherKey = privatePhoto ? HIDE_PUBLIC : HIDE_PRIVATE;
        SharedPreferences.Editor editor = preferences.edit().putBoolean(key, hidden);
        if (hidden) {
            editor.putBoolean(otherKey, false);
        }
        return editor.commit();
    }
}
