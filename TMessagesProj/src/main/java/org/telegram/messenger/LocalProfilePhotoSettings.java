package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.TLRPC;

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
     * Changes one local visibility switch. At least one of the two profile
     * photo types must remain visible.
     */
    public static synchronized boolean setHidden(Context context, int account, boolean privatePhoto, boolean hidden) {
        SharedPreferences preferences = getPreferences(context, account);
        boolean otherHidden = preferences.getBoolean(privatePhoto ? HIDE_PUBLIC : HIDE_PRIVATE, false);
        if (hidden && (otherHidden || !hasPhoto(context, account, !privatePhoto))) {
            return false;
        }
        String key = privatePhoto ? HIDE_PRIVATE : HIDE_PUBLIC;
        return preferences.edit().putBoolean(key, hidden).commit();
    }

    private static boolean hasPhoto(Context context, int account, boolean privatePhoto) {
        TLRPC.User user = MessagesController.getInstance(account).getUser(UserConfig.getInstance(account).getClientUserId());
        if (user == null) return false;
        TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(user.id);
        if (privatePhoto) {
            return user.photo != null && user.photo.personal || full != null && full.personal_photo != null;
        }
        return user.photo != null && !user.photo.personal || full != null && UserObject.hasFallbackPhoto(full);
    }
}
