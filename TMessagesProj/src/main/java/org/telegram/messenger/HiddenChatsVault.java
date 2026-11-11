/*
 * Local encrypted storage for the optional hidden-chats feature.
 *
 * The vault stores only dialog IDs. It does not copy, move, or delete Telegram
 * messages. Call lock() when the app leaves the foreground and require unlock()
 * on every entry to the hidden area.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static volatile HiddenChatsVault activeVault;
    private static final Object FILTER_CACHE_LOCK = new Object();
    private static final ConcurrentHashMap<String, Boolean> FILTER_CACHE = new ConcurrentHashMap<String, Boolean>();
    private static final ConcurrentHashMap<String, String> FILTER_TOKEN_CACHE = new ConcurrentHashMap<String, String>();
    private static final Set<String> FILTER_CACHE_PENDING = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<Runnable>> FILTER_CACHE_WAITERS = new ConcurrentHashMap<String, CopyOnWriteArrayList<Runnable>>();
    private static final ConcurrentHashMap<Integer, AtomicInteger> FILTER_CACHE_GENERATIONS = new ConcurrentHashMap<Integer, AtomicInteger>();

    private final SharedPreferences preferences;
    private final int account;
    private final Set<Long> hiddenDialogIds = new LinkedHashSet<Long>();

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
        activeVault = this;
    }

    public static HiddenChatsVault getActiveVault() {
        return activeVault;
    }

    public static void lockActiveVault() {
        HiddenChatsVault current = activeVault;
        if (current != null) {
            current.lock();
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
        } catch (GeneralSecurityException e) {
            clear(candidateKey);
            throw new GeneralSecurityException("Incorrect password or damaged hidden chats data", e);
        } catch (RuntimeException e) {
            clear(candidateKey);
            throw new GeneralSecurityException("Hidden chats data is invalid", e);
        }
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
     * Schedules HMAC checks away from the UI thread. Until a result is ready,
     * unknown dialogs are treated as hidden so a locked vault never leaks them.
     */
    public void prepareDialogFilterCache(List<Long> dialogIds, Runnable onReady) {
        if (dialogIds == null || dialogIds.isEmpty()) {
            return;
        }
        final ArrayList<Long> pendingIds = new ArrayList<>();
        final ArrayList<String> missingKeys = new ArrayList<>();
        synchronized (FILTER_CACHE_LOCK) {
            final Set<String> seenKeys = new LinkedHashSet<>();
            for (Long id : dialogIds) {
                if (id == null) continue;
                String key = filterCacheKey(id.longValue());
                if (!seenKeys.add(key) || FILTER_CACHE.containsKey(key)) continue;
                missingKeys.add(key);
                if (FILTER_CACHE_PENDING.add(key)) {
                    pendingIds.add(id);
                }
            }
            if (onReady != null && !missingKeys.isEmpty()) {
                final AtomicInteger remaining = new AtomicInteger(missingKeys.size());
                Runnable readyCallback = () -> {
                    if (remaining.decrementAndGet() == 0) {
                        AndroidUtilities.runOnUIThread(onReady);
                    }
                };
                for (String key : missingKeys) {
                    CopyOnWriteArrayList<Runnable> waiters = FILTER_CACHE_WAITERS.get(key);
                    if (waiters == null) {
                        CopyOnWriteArrayList<Runnable> created = new CopyOnWriteArrayList<>();
                        CopyOnWriteArrayList<Runnable> existing = FILTER_CACHE_WAITERS.putIfAbsent(key, created);
                        waiters = existing == null ? created : existing;
                    }
                    waiters.add(readyCallback);
                }
            }
        }
        if (pendingIds.isEmpty()) {
            return;
        }
        AtomicInteger generationCounter = FILTER_CACHE_GENERATIONS.get(account);
        if (generationCounter == null) {
            AtomicInteger created = new AtomicInteger();
            AtomicInteger existing = FILTER_CACHE_GENERATIONS.putIfAbsent(account, created);
            generationCounter = existing == null ? created : existing;
        }
        final AtomicInteger expectedGeneration = generationCounter;
        final int generation = expectedGeneration.get();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                Set<String> tokens;
                try {
                    Set<String> storedTokens = preferences.getStringSet(KEY_FILTER_TOKENS, Collections.<String>emptySet());
                    tokens = storedTokens == null
                            ? Collections.<String>emptySet()
                            : new LinkedHashSet<String>(storedTokens);
                } catch (Exception e) {
                    tokens = null;
                }
                for (Long id : pendingIds) {
                    boolean hidden = tokens == null;
                    if (tokens != null) {
                        try {
                            String token = dialogToken(id.longValue());
                            FILTER_TOKEN_CACHE.put(filterCacheKey(id.longValue()), token);
                            hidden = tokens.contains(token);
                        } catch (Exception e) {
                            hidden = true;
                        }
                    }
                    synchronized (FILTER_CACHE_LOCK) {
                        if (expectedGeneration.get() == generation) {
                            FILTER_CACHE.put(filterCacheKey(id.longValue()), hidden);
                        }
                    }
                }
            } finally {
                ArrayList<Runnable> callbacks = new ArrayList<>();
                synchronized (FILTER_CACHE_LOCK) {
                    for (Long id : pendingIds) {
                        String key = filterCacheKey(id.longValue());
                        FILTER_CACHE_PENDING.remove(key);
                        CopyOnWriteArrayList<Runnable> waiters = FILTER_CACHE_WAITERS.remove(key);
                        if (waiters != null) callbacks.addAll(waiters);
                    }
                }
                for (Runnable callback : callbacks) {
                    callback.run();
                }
            }
        });
    }

    public boolean isDialogFilterCached(long dialogId) {
        return FILTER_CACHE.containsKey(filterCacheKey(dialogId));
    }

    public boolean isDialogHiddenForFiltering(long dialogId) {
        Boolean hidden = FILTER_CACHE.get(filterCacheKey(dialogId));
        return hidden == null || hidden;
    }

    private String filterCacheKey(long dialogId) {
        return account + ":" + dialogId;
    }

    private void invalidateFilterCache() {
        synchronized (FILTER_CACHE_LOCK) {
            AtomicInteger generation = FILTER_CACHE_GENERATIONS.get(account);
            if (generation != null) generation.incrementAndGet();
            String prefix = account + ":";
            for (String key : FILTER_CACHE.keySet()) {
                if (key.startsWith(prefix)) {
                    FILTER_CACHE.remove(key);
                }
            }
        }
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

    public synchronized void hideDialogs(List<Long> dialogIds) throws GeneralSecurityException {
        requireUnlocked();
        if (dialogIds == null || dialogIds.isEmpty()) return;
        ArrayList<Long> added = new ArrayList<>();
        for (Long id : dialogIds) {
            if (id != null && id.longValue() != 0 && hiddenDialogIds.add(id)) {
                added.add(id);
            }
        }
        if (!added.isEmpty()) {
            try {
                persist();
            } catch (GeneralSecurityException e) {
                hiddenDialogIds.removeAll(added);
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
            String cacheKey = filterCacheKey(id.longValue());
            String token = FILTER_TOKEN_CACHE.get(cacheKey);
            if (token == null) {
                token = dialogToken(id.longValue());
                FILTER_TOKEN_CACHE.put(cacheKey, token);
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
        invalidateFilterCache();
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
