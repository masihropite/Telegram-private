package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Device-local visibility switches for the signed-in account's private and
 * public profile photos. No Telegram profile data or privacy settings are
 * changed on the server.
 *
 * Preferences are keyed by the stable Telegram user id, not by the account
 * slot, so removing/reordering accounts cannot transfer settings to another user.
 */
public final class LocalProfilePhotoSettings {
    private static final String PREFS = "telegramz_local_profile_photo_visibility";
    private static final String HIDE_PUBLIC = "hide_public_photo";
    private static final String HIDE_PRIVATE = "hide_private_photo";
    private static final String INITIALIZED = "user_id_preferences_initialized";

    private LocalProfilePhotoSettings() {
    }

    private static SharedPreferences getPreferences(Context context, int account) {
        Context appContext = context.getApplicationContext();
        long userId = UserConfig.getInstance(account).getClientUserId();
        if (userId <= 0) {
            // Before account data is available, retain slot-scoped access only
            // as a temporary fallback. Once the user id is known, migrate below.
            return appContext.getSharedPreferences(PREFS + "_pending_" + account, Context.MODE_PRIVATE);
        }

        SharedPreferences preferences = appContext.getSharedPreferences(PREFS + "_" + userId, Context.MODE_PRIVATE);
        if (!preferences.getBoolean(INITIALIZED, false)) {
            SharedPreferences legacy = appContext.getSharedPreferences(PREFS + "_" + account, Context.MODE_PRIVATE);
            preferences.edit()
                    .putBoolean(HIDE_PUBLIC, legacy.getBoolean(HIDE_PUBLIC, false))
                    .putBoolean(HIDE_PRIVATE, legacy.getBoolean(HIDE_PRIVATE, false))
                    .putBoolean(INITIALIZED, true)
                    .commit();
        }
        return preferences;
    }

    public static boolean isPublicHidden(Context context, int account) {
        return getPreferences(context, account).getBoolean(HIDE_PUBLIC, false);
    }

    public static boolean isPrivateHidden(Context context, int account) {
        return getPreferences(context, account).getBoolean(HIDE_PRIVATE, false);
    }

    /**
     * Changes only the requested local visibility switch. Both switches may
     * never be enabled together; at least one profile photo remains visible.
     */
    public static synchronized boolean setHidden(Context context, int account, boolean privatePhoto, boolean hidden) {
        SharedPreferences preferences = getPreferences(context, account);
        String key = privatePhoto ? HIDE_PRIVATE : HIDE_PUBLIC;
        String otherKey = privatePhoto ? HIDE_PUBLIC : HIDE_PRIVATE;
        if (hidden && preferences.getBoolean(otherKey, false)) {
            return false;
        }
        return preferences.edit().putBoolean(key, hidden).commit();
    }
}
