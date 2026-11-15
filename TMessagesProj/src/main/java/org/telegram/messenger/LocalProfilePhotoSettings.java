package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.TLRPC;

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
     * A hide request is accepted only when the opposite photo type exists and
     * remains visible. This prevents a profile from losing every visible photo.
     */
    public static synchronized boolean setHidden(Context context, int account, boolean privatePhoto, boolean hidden) {
        SharedPreferences preferences = getPreferences(context, account);
        String key = privatePhoto ? HIDE_PRIVATE : HIDE_PUBLIC;
        if (hidden) {
            boolean otherHidden = preferences.getBoolean(privatePhoto ? HIDE_PUBLIC : HIDE_PRIVATE, false);
            if (otherHidden || !hasVisibleAlternative(context, account, !privatePhoto)) {
                return false;
            }
        }
        return preferences.edit().putBoolean(key, hidden).commit();
    }

    private static boolean hasVisibleAlternative(Context context, int account, boolean privatePhoto) {
        TLRPC.User user = MessagesController.getInstance(account)
                .getUser(UserConfig.getInstance(account).getClientUserId());
        if (user == null) {
            return false;
        }
        TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(user.id);
        if (privatePhoto) {
            boolean currentPersonal = user.photo != null && user.photo.personal && user.photo.photo_big != null;
            boolean alternatePersonal = full != null && full.personal_photo != null
                    && full.personal_photo.sizes != null && !full.personal_photo.sizes.isEmpty();
            return currentPersonal || alternatePersonal;
        }
        boolean currentPublic = user.photo != null && !user.photo.personal && user.photo.photo_big != null;
        boolean fallbackPublic = full != null && UserObject.hasFallbackPhoto(full)
                && full.fallback_photo.sizes != null && !full.fallback_photo.sizes.isEmpty();
        return currentPublic || fallbackPublic;
    }
}
