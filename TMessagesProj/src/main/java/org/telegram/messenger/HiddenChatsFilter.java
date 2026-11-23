package org.telegram.messenger;

import android.util.SparseArray;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Small helper used by UI lists to hide contacts and chats that are hidden locally.
 * It only reads the one-way filter tokens of {@link HiddenChatsVault}; it never needs
 * the vault to be unlocked. Unknown ids are treated as hidden until their background
 * check finishes (fail closed), exactly like the main chat list.
 */
public final class HiddenChatsFilter {

    private static final SparseArray<HiddenChatsVault> VAULTS = new SparseArray<>();

    private HiddenChatsFilter() {
    }

    private static synchronized HiddenChatsVault vault(int account) {
        HiddenChatsVault vault = VAULTS.get(account);
        if (vault == null) {
            vault = new HiddenChatsVault(ApplicationLoader.applicationContext, account);
            VAULTS.put(account, vault);
        }
        return vault;
    }

    /** True when the dialog (user id, or negative chat id) is hidden or not resolved yet. */
    public static boolean isHidden(int account, long dialogId) {
        if (dialogId == 0) {
            return false;
        }
        return vault(account).isDialogHiddenForFiltering(dialogId);
    }

    /** True only when the dialog is already resolved and hidden (never hides unresolved strangers). */
    public static boolean isKnownHidden(int account, long dialogId) {
        if (dialogId == 0) {
            return false;
        }
        HiddenChatsVault vault = vault(account);
        return vault.isDialogFilterCached(dialogId) && vault.isDialogHiddenForFiltering(dialogId);
    }

    public static boolean isKnown(int account, long dialogId) {
        return dialogId == 0 || vault(account).isDialogFilterCached(dialogId);
    }

    /**
     * Resolves, in the background, every id whose state is not known yet. The first
     * ids are resolved first so the top of a list shows up quickly. onReady runs on
     * the UI thread after the last id is resolved (and once after the first batch).
     * Returns true when something had to be resolved.
     */
    public static boolean prepare(int account, Collection<Long> ids, Runnable onReady) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        HiddenChatsVault vault = vault(account);
        ArrayList<Long> unknown = new ArrayList<>();
        for (Long id : ids) {
            if (id != null && id != 0 && !vault.isDialogFilterCached(id)) {
                unknown.add(id);
            }
        }
        if (unknown.isEmpty()) {
            return false;
        }
        final int headSize = Math.min(30, unknown.size());
        ArrayList<Long> head = new ArrayList<>(unknown.subList(0, headSize));
        if (headSize == unknown.size()) {
            vault.prepareDialogFilterCache(head, onReady);
        } else {
            ArrayList<Long> tail = new ArrayList<>(unknown.subList(headSize, unknown.size()));
            vault.prepareDialogFilterCache(head, onReady);
            vault.prepareDialogFilterCache(tail, onReady);
        }
        return true;
    }
}
