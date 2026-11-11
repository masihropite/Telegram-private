/*
 * Local encrypted storage for the optional hidden-chats feature.
 *
 * The vault stores only dialog IDs. It does not copy, move, or delete Telegram
 * messages. Call lock() when the app leaves the foreground and require unlock()
 * on every entry to the hidden area.
 */
package org.telegram.messenger;

import android.content.Context;
import android.os.Looper;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Device-local encrypted vault for dialog IDs selected by the user.
 *
 * Password policy: exactly six ASCII digits. Passwords are never persisted.
 * The derived AES key exists only in memory while the vault is unlocked.
 */
public final class HiddenChatsVault {

    private static final String PREFS_NAME = "hidden_chats_vault_v1";
    private static final String KEY_PAYLOAD = "encrypted_payload";
    private static final String KEY_FILTER_TOKENS = "hidden_dialog_filter_tokens";
    private static final String KEY_FAILED_ATTEMPTS = "failed_unlock_attempts";
    private static final String KEY_NEXT_UNLOCK_TIME = "next_unlock_allowed_at";
    private static final String KEYSTORE_ALIAS = "telegram_hidden_chats_filter_hmac_v1";
    private static final int FORMAT_VERSION = 1;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int KEY_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 210000;
    private static final int GCM_TAG_BITS = 128;
    private static final int MAX_DIALOGS = 100000;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile SecretKey cachedFilterKey;
    private static final ConcurrentHashMap<Integer, HiddenChatsVault> ACTIVE_VAULTS = new ConcurrentHashMap<Integer, HiddenChatsVault>();
    private static final String FILTER_TOKEN_FAILED = "__FILTER_TOKEN_FAILED__";
    private static final ConcurrentHashMap<Long, String> FILTER_TOKEN_CACHE = new ConcurrentHashMap<Long, String>();
    private static final Set<Long> FILTER_TOKEN_PENDING = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
    private static final ExecutorService FILTER_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "HiddenChatsFilter");
        thread.setDaemon(true);
        return thread;
    });

    private final SharedPreferences preferences;
    private final int account;
    private final Set<Long> hiddenDialogIds = new LinkedHashSet<Long>();
    private volatile Set<String> cachedFilterTokens;

    private byte[] salt;
    private byte[] unlockedKey;

    public HiddenChatsVault(Context context) {
        this(context, 0);
    }

    public HiddenChatsVault(Context context, int account) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        this.account = account;
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFS_NAME + "_" + account, Context.MODE_PRIVATE);
    }

    public void registerAsActiveVault() {
        ACTIVE_VAULTS.put(account, this);
    }

    public static HiddenChatsVault getActiveVault() {
        return getActiveVault(UserConfig.selectedAccount);
    }

    public static HiddenChatsVault getActiveVault(int account) {
        return ACTIVE_VAULTS.get(account);
    }

    public static void lockActiveVault() {
        for (HiddenChatsVault vault : ACTIVE_VAULTS.values()) {
            if (vault != null) {
                vault.lock();
            }
        }
    }

    public synchronized boolean isConfigured() {
        return preferences.contains(KEY_PAYLOAD);
    }

    public synchronized boolean isUnlocked() {
        return unlockedKey != null;
    }

    /**
     * Creates the vault with an empty hidden-chat list.
     * This must be called only during first-time setup.
     */
    public synchronized void create(char[] password) throws GeneralSecurityException {
        if (isConfigured()) {
            throw new IllegalStateException("Hidden chats vault is already configured");
        }
        validatePassword(password);
        salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        unlockedKey = deriveKey(password, salt);
        hiddenDialogIds.clear();
        persist();
    }

    /**
     * Unlocks the vault. An incorrect password results in a
     * GeneralSecurityException and leaves the vault locked.
     */
    public synchronized void unlock(char[] password) throws GeneralSecurityException {
        if (!isConfigured()) {
            throw new IllegalStateException("Hidden chats vault has not been configured");
        }
        long now = System.currentTimeMillis();
        long nextAllowedAt = preferences.getLong(KEY_NEXT_UNLOCK_TIME, 0L);
        if (now < nextAllowedAt) {
            long remainingSeconds = Math.max(1L, (nextAllowedAt - now + 999L) / 1000L);
            throw new GeneralSecurityException("Too many incorrect attempts. Try again in " + remainingSeconds + " seconds.");
        }
        validatePassword(password);

        byte[] payload;
        try {
            payload = Base64.decode(preferences.getString(KEY_PAYLOAD, ""), Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Hidden chats data is invalid", e);
        }

        if (payload.length < 1 + SALT_BYTES + IV_BYTES + 16) {
            throw new GeneralSecurityException("Hidden chats data is incomplete");
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int version = buffer.get() & 0xff;
        if (version != FORMAT_VERSION) {
            throw new GeneralSecurityException("Unsupported hidden chats data version");
        }

        byte[] storedSalt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        buffer.get(storedSalt);
        buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        byte[] candidateKey = deriveKey(password, storedSalt);
        try {
            byte[] plaintext = crypt(Cipher.DECRYPT_MODE, candidateKey, iv, ciphertext);
            Set<Long> restored = deserializeDialogIds(plaintext);
            clear(unlockedKey);
            clear(salt);
            unlockedKey = candidateKey;
            salt = storedSalt;
            hiddenDialogIds.clear();
            hiddenDialogIds.addAll(restored);
            preferences.edit().remove(KEY_FAILED_ATTEMPTS).remove(KEY_NEXT_UNLOCK_TIME).apply();
        } catch (GeneralSecurityException e) {
            clear(candidateKey);
            recordFailedUnlockAttempt();
            throw new GeneralSecurityException("Incorrect password or damaged hidden chats data", e);
        } catch (RuntimeException e) {
            clear(candidateKey);
            recordFailedUnlockAttempt();
            throw new GeneralSecurityException("Hidden chats data is invalid", e);
        }
    }

    private void recordFailedUnlockAttempt() {
        int failures = Math.min(20, preferences.getInt(KEY_FAILED_ATTEMPTS, 0) + 1);
        long delay = Math.min(15L * 60L * 1000L, 1000L << Math.min(failures - 1, 10));
        preferences.edit()
                .putInt(KEY_FAILED_ATTEMPTS, failures)
                .putLong(KEY_NEXT_UNLOCK_TIME, System.currentTimeMillis() + delay)
                .apply();
    }

    /** Locks the vault and clears decrypted IDs and key material from memory. */
    public synchronized void lock() {
        clear(unlockedKey);
        clear(salt);
        unlockedKey = null;
        salt = null;
        hiddenDialogIds.clear();
    }

    public synchronized List<Long> getHiddenDialogIds() {
        requireUnlocked();
        return Collections.unmodifiableList(new ArrayList<Long>(hiddenDialogIds));
    }

    public synchronized boolean isDialogHidden(long dialogId) {
        requireUnlocked();
        return hiddenDialogIds.contains(dialogId);
    }

    /**
     * Checks hidden state without unlocking. Only one-way HMAC tokens are
     * persisted, allowing the main chat list to remain filtered while locked.
     */
    /**
     * Precomputes dialog filter tokens away from the UI thread. The main dialog
     * list must never invoke Android Keystore operations while drawing/updating.
     *
     * Returns true only when every supplied dialog ID has a cached token.
     * Missing tokens are treated as hidden until the background preparation
     * completes, so a Keystore delay cannot briefly reveal a hidden dialog.
     */
    public boolean prepareFilterTokens(Collection<Long> dialogIds, Runnable callback) {
        if (dialogIds == null || dialogIds.isEmpty()) {
            return true;
        }
        ArrayList<Long> missing = new ArrayList<Long>();
        boolean ready = true;
        for (Long id : dialogIds) {
            if (id == null || id.longValue() == 0) {
                continue;
            }
            if (!FILTER_TOKEN_CACHE.containsKey(id)) {
                ready = false;
                if (FILTER_TOKEN_PENDING.add(id)) {
                    missing.add(id);
                }
            }
        }
        if (ready) {
            return true;
        }
        FILTER_EXECUTOR.execute(() -> {
            for (Long id : missing) {
                try {
                    FILTER_TOKEN_CACHE.put(id, dialogToken(id.longValue()));
                } catch (Exception e) {
                    // Fail closed: do not show a dialog if its privacy token
                    // cannot be verified.
                    FILTER_TOKEN_CACHE.put(id, FILTER_TOKEN_FAILED);
                    FileLog.e(e);
                } finally {
                    FILTER_TOKEN_PENDING.remove(id);
                }
            }
            if (callback != null) {
                AndroidUtilities.runOnUIThread(callback);
            }
        });
        return false;
    }

    public boolean isDialogHiddenForFiltering(long dialogId) {
        String token = FILTER_TOKEN_CACHE.get(dialogId);
        if (token == null && Looper.myLooper() != Looper.getMainLooper()) {
            // Search results are sometimes filtered on Telegram's search queue.
            // It is safe to do the first token calculation there, never on main.
            try {
                token = dialogToken(dialogId);
                FILTER_TOKEN_CACHE.put(dialogId, token);
            } catch (Exception e) {
                FILTER_TOKEN_CACHE.put(dialogId, FILTER_TOKEN_FAILED);
                FileLog.e(e);
            }
        }
        if (token == null || FILTER_TOKEN_FAILED.equals(token)) {
            return true;
        }
        Set<String> tokens = cachedFilterTokens;
        if (tokens == null) {
            Set<String> stored = preferences.getStringSet(KEY_FILTER_TOKENS, Collections.<String>emptySet());
            tokens = stored == null ? Collections.<String>emptySet() : new LinkedHashSet<String>(stored);
            cachedFilterTokens = tokens;
        }
        return tokens.contains(token);
    }

    public synchronized void hideDialog(long dialogId) throws GeneralSecurityException {
        requireUnlocked();
        if (dialogId == 0) {
            throw new IllegalArgumentException("Dialog ID must not be zero");
        }
        if (hiddenDialogIds.add(dialogId)) {
            try {
                persist();
            } catch (GeneralSecurityException e) {
                hiddenDialogIds.remove(dialogId);
                throw e;
            }
        }
    }

    public synchronized void unhideDialog(long dialogId) throws GeneralSecurityException {
        requireUnlocked();
        if (hiddenDialogIds.remove(dialogId)) {
            try {
                persist();
            } catch (GeneralSecurityException e) {
                hiddenDialogIds.add(dialogId);
                throw e;
            }
        }
    }

    private void persist() throws GeneralSecurityException {
        requireUnlocked();
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        byte[] plaintext = serializeDialogIds(hiddenDialogIds);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, unlockedKey, iv, plaintext);

        ByteBuffer payload = ByteBuffer.allocate(1 + SALT_BYTES + IV_BYTES + ciphertext.length)
                .order(ByteOrder.BIG_ENDIAN);
        payload.put((byte) FORMAT_VERSION);
        payload.put(salt);
        payload.put(iv);
        payload.put(ciphertext);

        Set<String> filterTokens = new LinkedHashSet<String>();
        for (Long id : hiddenDialogIds) {
            String token = FILTER_TOKEN_CACHE.get(id);
            if (token == null || FILTER_TOKEN_FAILED.equals(token)) {
                token = dialogToken(id.longValue());
                FILTER_TOKEN_CACHE.put(id, token);
            }
            filterTokens.add(token);
        }
        boolean saved = preferences.edit()
                .putString(KEY_PAYLOAD, Base64.encodeToString(payload.array(), Base64.NO_WRAP))
                .putStringSet(KEY_FILTER_TOKENS, filterTokens)
                .commit();
        if (!saved) {
            throw new GeneralSecurityException("Could not save hidden chats data");
        }
        cachedFilterTokens = new LinkedHashSet<String>(filterTokens);
    }

    private String dialogToken(long dialogId) throws GeneralSecurityException {
        try {
            SecretKey key = cachedFilterKey;
            if (key == null) {
                synchronized (HiddenChatsVault.class) {
                    key = cachedFilterKey;
                    if (key == null) {
                        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
                        keyStore.load(null);
                        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                            key = (SecretKey) keyStore.getKey(KEYSTORE_ALIAS, null);
                        } else {
                            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
                            generator.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                                    .setDigests(KeyProperties.DIGEST_SHA256)
                                    .build());
                            key = generator.generateKey();
                        }
                        cachedFilterKey = key;
                    }
                }
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] raw = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(dialogId).array();
            return Base64.encodeToString(mac.doFinal(raw), Base64.NO_WRAP);
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException("Could not access Android Keystore", e);
        }
    }

    private static byte[] deriveKey(char[] password, byte[] salt)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] crypt(int mode, byte[] key, byte[] iv, byte[] input)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(new byte[] { (byte) FORMAT_VERSION });
        return cipher.doFinal(input);
    }

    private static byte[] serializeDialogIds(Set<Long> ids) throws GeneralSecurityException {
        if (ids.size() > MAX_DIALOGS) {
            throw new GeneralSecurityException("Too many hidden chats");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(ids.size());
            for (Long id : ids) {
                output.writeLong(id.longValue());
            }
            output.flush();
            return bytes.toByteArray();
        } catch (Exception e) {
            throw new GeneralSecurityException("Could not encode hidden chats", e);
        }
    }

    private static Set<Long> deserializeDialogIds(byte[] data)
            throws GeneralSecurityException {
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
            int count = input.readInt();
            if (count < 0 || count > MAX_DIALOGS || data.length != 4 + count * 8) {
                throw new GeneralSecurityException("Invalid hidden chats list");
            }
            Set<Long> ids = new LinkedHashSet<Long>();
            for (int i = 0; i < count; i++) {
                long id = input.readLong();
                if (id == 0) {
                    throw new GeneralSecurityException("Invalid dialog ID");
                }
                ids.add(id);
            }
            return ids;
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException("Could not decode hidden chats", e);
        }
    }

    private static void validatePassword(char[] password) {
        if (password == null || password.length != 6) {
            throw new IllegalArgumentException("Password must contain exactly 6 digits");
        }
        for (char c : password) {
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("Password must contain exactly 6 digits");
            }
        }
    }

    private void requireUnlocked() {
        if (unlockedKey == null || salt == null) {
            throw new IllegalStateException("Hidden chats vault is locked");
        }
    }

    private static void clear(byte[] value) {
        if (value != null) {
            for (int i = 0; i < value.length; i++) {
                value[i] = 0;
            }
        }
    }
}
