package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.TLRPC;

/**
 * Device-local visibility overrides for the current account's profile photos.
 * These flags affect only this TelegramZ installation; no Telegram API request
 * is made and the photos shown to other users are not changed.
 */
public final class LocalProfilePhotoVisibility {

    private static final String PREFS_NAME = "telegramz_local_profile_visibility";
    private static final String HIDE_PRIVATE = "hide_private_photo";
    private static final String HIDE_PUBLIC = "hide_public_photo";

    private LocalProfilePhotoVisibility() {
    }

    private static SharedPreferences preferences(int account) {
        Context context = ApplicationLoader.applicationContext;
        return context.getSharedPreferences(PREFS_NAME + "_" + account, Context.MODE_PRIVATE);
    }

    public static boolean isPrivateHidden(int account) {
        return preferences(account).getBoolean(HIDE_PRIVATE, false);
    }

    public static boolean isPublicHidden(int account) {
        return preferences(account).getBoolean(HIDE_PUBLIC, false);
    }

    public static boolean setPrivateHidden(int account, boolean hidden) {
        return setHidden(account, HIDE_PRIVATE, HIDE_PUBLIC, hidden);
    }

    public static boolean setPublicHidden(int account, boolean hidden) {
        return setHidden(account, HIDE_PUBLIC, HIDE_PRIVATE, hidden);
    }

    private static boolean setHidden(int account, String key, String otherKey, boolean hidden) {
        SharedPreferences prefs = preferences(account);
        // Keep at least one of the two profile-photo categories visible locally.
        if (hidden && prefs.getBoolean(otherKey, false)) {
            return false;
        }
        prefs.edit().putBoolean(key, hidden).apply();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.mainUserInfoChanged);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        return true;
    }

    public static boolean shouldHidePublicPhoto(int account, TLRPC.Photo photo) {
        if (photo == null || !isPublicHidden(account)) {
            return false;
        }
        long selfId = UserConfig.getInstance(account).getClientUserId();
        TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(selfId);
        return full != null && full.fallback_photo != null && full.fallback_photo.id == photo.id;
    }

    /**
     * Returns true only for the current account's own avatar. Other users'
     * photos are never affected by these local preferences.
     */
    public static boolean shouldHideUserPhoto(int account, TLRPC.User user) {
        if (user == null || user.photo == null
                || user.id != UserConfig.getInstance(account).getClientUserId()) {
            return false;
        }

        TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(user.id);
        if (full != null) {
            if (full.fallback_photo != null && full.fallback_photo.id == user.photo.photo_id) {
                return isPublicHidden(account);
            }
            if (full.profile_photo != null && full.profile_photo.id == user.photo.photo_id) {
                return isPrivateHidden(account);
            }
            if (full.personal_photo != null && full.personal_photo.id == user.photo.photo_id) {
                return isPrivateHidden(account);
            }
        }
        return isPrivateHidden(account);
    }
}
