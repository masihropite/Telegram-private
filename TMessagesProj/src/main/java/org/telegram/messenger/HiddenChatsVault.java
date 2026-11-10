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

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Device-local encrypted vault for dialog IDs selected by the user.
 *
 * Password policy: at least 8 ASCII characters, including at least one letter
 * and one digit. Passwords are never persisted. The derived AES key exists
 * only in memory while the vault is unlocked.
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

    private final SharedPreferences preferences;
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
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFS_NAME + "_" + account, Context.MODE_PRIVATE);
    }

    public void registerAsActiveVault() {
        activeVault = this;
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
    public synchronized boolean isDialogHiddenForFiltering(long dialogId) {
        try {
            String token = dialogToken(dialogId);
            Set<String> tokens = preferences.getStringSet(KEY_FILTER_TOKENS, Collections.<String>emptySet());
            return tokens != null && tokens.contains(token);
        } catch (Exception e) {
            // Fail closed: a keystore failure must not reveal hidden chats.
            return true;
        }
    }

    public synchronized void hideDialog(long dialogId) throws GeneralSecurityException {
        requireUnlocked();
        if (dialogId == 0) {
            throw new IllegalArgumentException("Dialog ID must not be zero");
        }
        if (hiddenDialogIds.add(dialogId)) {
            persist();
        }
    }

    public synchronized void unhideDialog(long dialogId) throws GeneralSecurityException {
        requireUnlocked();
        if (hiddenDialogIds.remove(dialogId)) {
            persist();
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
            filterTokens.add(dialogToken(id.longValue()));
        }
        boolean saved = preferences.edit()
                .putString(KEY_PAYLOAD, Base64.encodeToString(payload.array(), Base64.NO_WRAP))
                .putStringSet(KEY_FILTER_TOKENS, filterTokens)
                .commit();
        if (!saved) {
            throw new GeneralSecurityException("Could not save hidden chats data");
        }
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
        if (password == null || password.length < 8) {
            throw new IllegalArgumentException("Password must contain at least 8 characters");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char c : password) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                hasLetter = true;
            } else if (c >= '0' && c <= '9') {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new IllegalArgumentException("Password must contain English letters and digits");
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
