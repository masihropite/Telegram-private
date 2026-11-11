/*
 * Local hidden chats screen for this unofficial Telegram fork.
 * Hidden chats remain in Telegram storage; this screen only changes local visibility.
 */
package org.telegram.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.HiddenChatsVault;
import org.telegram.messenger.LocalProfilePhotoVisibility;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HiddenChatsActivity extends BaseFragment {
    private HiddenChatsVault vault;
    private LinearLayout content;
    private LinearLayout rows;
    private boolean handedOffToHiddenList;

    public HiddenChatsActivity(int account) {
        super();
        currentAccount = account;
    }

    @Override
    public boolean onFragmentCreate() {
        vault = new HiddenChatsVault(ApplicationLoader.applicationContext, currentAccount);
        vault.registerAsActiveVault();
        return super.onFragmentCreate();
    }

    @Override
    protected View createView(Context context) {
        if (getParentActivity() != null) {
            getParentActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("گفتگوهای مخفی");

        ScrollView scroll = new ScrollView(context);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        TextView description = new TextView(context);
        description.setText("گفتگوهای این بخش فقط در همین دستگاه مخفی می‌شوند و هیچ پیامی حذف نخواهد شد.");
        description.setTextSize(14);
        content.addView(description, new LinearLayout.LayoutParams(-1, -2));

        addLocalProfilePhotoControls(context);

        Button unlock = new Button(context);
        unlock.setText(vault.isConfigured() ? "باز کردن گفتگوهای مخفی" : "تنظیم رمز عبور");
        unlock.setOnClickListener(v -> requestPassword());
        content.addView(unlock, new LinearLayout.LayoutParams(-1, -2));

        Button lock = new Button(context);
        lock.setText("قفل کردن");
        lock.setOnClickListener(v -> {
            vault.lock();
            refreshRows();
        });
        content.addView(lock, new LinearLayout.LayoutParams(-1, -2));

        Button add = new Button(context);
        add.setText("مخفی کردن گفتگوها");
        add.setOnClickListener(v -> openChatPicker());
        content.addView(add, new LinearLayout.LayoutParams(-1, -2));

        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        content.addView(rows, new LinearLayout.LayoutParams(-1, -2));
        refreshRows();
        fragmentView = scroll;
        return scroll;
    }

    private static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void addLocalProfilePhotoControls(Context context) {
        TextView header = new TextView(context);
        header.setText("مخفی‌سازی عکس پروفایل فقط برای خودم");
        header.setTextSize(16);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, dp(context, 20), 0, dp(context, 4));
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(context);
        note.setText("این تغییر فقط در TelegramZ روی همین دستگاه اعمال می‌شود و عکس مخاطبین را تغییر نمی‌دهد. برای باقی‌ماندن حداقل یک عکس، نمی‌توان هر دو گزینه را هم‌زمان فعال کرد.");
        note.setTextSize(13);
        content.addView(note, new LinearLayout.LayoutParams(-1, -2));

        CheckBox privatePhoto = new CheckBox(context);
        privatePhoto.setText("مخفی‌کردن عکس خصوصی (برای مخاطبین مجاز)");
        privatePhoto.setChecked(LocalProfilePhotoVisibility.isPrivateHidden(currentAccount));
        privatePhoto.setOnCheckedChangeListener((button, checked) -> {
            if (!LocalProfilePhotoVisibility.setPrivateHidden(currentAccount, checked)) {
                button.setChecked(false);
                Toast.makeText(getParentActivity(), "برای باقی‌ماندن یک عکس، ابتدا گزینه عکس عمومی را خاموش کنید.", Toast.LENGTH_LONG).show();
            }
        });
        content.addView(privatePhoto, new LinearLayout.LayoutParams(-1, -2));

        CheckBox publicPhoto = new CheckBox(context);
        publicPhoto.setText("مخفی‌کردن عکس عمومی (برای سایر افراد)");
        publicPhoto.setChecked(LocalProfilePhotoVisibility.isPublicHidden(currentAccount));
        publicPhoto.setOnCheckedChangeListener((button, checked) -> {
            if (!LocalProfilePhotoVisibility.setPublicHidden(currentAccount, checked)) {
                button.setChecked(false);
                Toast.makeText(getParentActivity(), "برای باقی‌ماندن یک عکس، ابتدا گزینه عکس خصوصی را خاموش کنید.", Toast.LENGTH_LONG).show();
            }
        });
        content.addView(publicPhoto, new LinearLayout.LayoutParams(-1, -2));
    }

    private void requestPassword() {
        requestPassword(this::openHiddenDialogs);
    }

    private void requestPassword(Runnable onUnlocked) {
        if (getParentActivity() == null) return;
        final EditText password = new EditText(getParentActivity());
        password.setSingleLine(true);
        password.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
        password.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        password.setFilters(new InputFilter[] { new InputFilter.LengthFilter(6) });
        password.setHint("رمز عددی ۶ رقمی");
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
                .setTitle(vault.isConfigured() ? "باز کردن گفتگوهای مخفی" : "ایجاد رمز عبور")
                .setView(password)
                .setNegativeButton("انصراف", null)
                .setPositiveButton(vault.isConfigured() ? "باز کردن" : "ادامه", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            char[] entered = password.getText().toString().toCharArray();
            try {
                if (vault.isConfigured()) {
                    vault.unlock(entered);
                    dialog.dismiss();
                    if (onUnlocked != null) {
                        onUnlocked.run();
                    }
                } else {
                    requestPasswordConfirmation(entered.clone(), onUnlocked);
                    dialog.dismiss();
                }
            } catch (Exception e) {
                Toast.makeText(getParentActivity(), e.getMessage() == null ? "Password could not be accepted" : e.getMessage(), Toast.LENGTH_LONG).show();
            } finally {
                Arrays.fill(entered, '\0');
                password.setText("");
            }
        }));
        dialog.show();
    }

    private void requestPasswordConfirmation(char[] first, Runnable onUnlocked) {
        final EditText confirmation = new EditText(getParentActivity());
        confirmation.setSingleLine(true);
        confirmation.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
        confirmation.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        confirmation.setFilters(new InputFilter[] { new InputFilter.LengthFilter(6) });
        new AlertDialog.Builder(getParentActivity())
                .setTitle("تأیید رمز عبور")
                .setMessage("همان رمز عبور را دوباره وارد کنید.")
                .setView(confirmation)
                .setNegativeButton("انصراف", (d, w) -> Arrays.fill(first, '\0'))
                .setPositiveButton("ایجاد", (d, w) -> {
                    char[] second = confirmation.getText().toString().toCharArray();
                    try {
                        if (!Arrays.equals(first, second)) {
                            Toast.makeText(getParentActivity(), "رمزهای عبور یکسان نیستند", Toast.LENGTH_LONG).show();
                            return;
                        }
                        vault.create(first);
                        if (onUnlocked != null) {
                            onUnlocked.run();
                        }
                    } catch (Exception e) {
                        Toast.makeText(getParentActivity(), e.getMessage() == null ? "ساخت فضای گفتگوهای مخفی انجام نشد" : e.getMessage(), Toast.LENGTH_LONG).show();
                    } finally {
                        Arrays.fill(first, '\0');
                        Arrays.fill(second, '\0');
                        confirmation.setText("");
                    }
                }).show();
    }

    private void openHiddenDialogs() {
        if (vault == null || !vault.isUnlocked()) return;
        android.os.Bundle args = new android.os.Bundle();
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_DEFAULT);
        args.putBoolean("hiddenChatsMode", true);
        handedOffToHiddenList = true;
        presentFragment(new DialogsActivity(args));
    }

    private void refreshRows() {
        if (rows == null) return;
        rows.removeAllViews();
        if (!vault.isUnlocked()) {
            TextView locked = new TextView(getParentActivity());
            locked.setText("فهرست گفتگوهای مخفی قفل است.");
            locked.setTextSize(15);
            locked.setPadding(0, dp(getParentActivity(), 20), 0, dp(getParentActivity(), 12));
            rows.addView(locked);
            return;
        }
        List<Long> ids;
        try {
            ids = vault.getHiddenDialogIds();
        } catch (Exception e) {
            return;
        }
        boolean filterTokensReady = vault.prepareFilterTokens(ids, this::refreshRows);
        if (ids.isEmpty()) {
            TextView empty = new TextView(getParentActivity());
            empty.setText("هنوز گفتگویی مخفی نشده است.");
            empty.setPadding(0, dp(getParentActivity(), 20), 0, dp(getParentActivity(), 12));
            rows.addView(empty);
            return;
        }
        for (Long id : ids) {
            final long dialogId = id;
            LinearLayout line = new LinearLayout(getParentActivity());
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(getParentActivity());
            title.setText(resolveDialogTitle(dialogId));
            title.setTextSize(16);
            title.setTypeface(null, Typeface.BOLD);
            line.addView(title, new LinearLayout.LayoutParams(0, dp(getParentActivity(), 56), 1));
            title.setOnClickListener(v -> openHiddenChat(dialogId));
            Button restore = new Button(getParentActivity());
            restore.setText(filterTokensReady ? "نمایش دوباره" : "در حال آماده‌سازی امن...");
            restore.setEnabled(filterTokensReady);
            restore.setOnClickListener(v -> {
                if (!vault.isUnlocked()) {
                    requestPassword(() -> restoreDialog(dialogId));
                    return;
                }
                restoreDialog(dialogId);
            });
            line.addView(restore, new LinearLayout.LayoutParams(-2, -2));
            rows.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void restoreDialog(long dialogId) {
        try {
            vault.unhideDialog(dialogId);
            org.telegram.messenger.NotificationCenter.getInstance(currentAccount).postNotificationName(org.telegram.messenger.NotificationCenter.dialogsNeedReload);
            refreshRows();
        } catch (IllegalStateException e) {
            // The app may have been backgrounded between drawing the row and
            // the tap. Re-authenticate instead of crashing the main thread.
            requestPassword(() -> restoreDialog(dialogId));
        } catch (GeneralSecurityException e) {
            Toast.makeText(getParentActivity(), "نمایش دوباره گفتگو انجام نشد", Toast.LENGTH_LONG).show();
        }
    }

    private String resolveDialogTitle(long dialogId) {
        if (dialogId > 0) {
            TLRPC.User user = org.telegram.messenger.MessagesController.getInstance(currentAccount).getUser(dialogId);
            return user == null ? "User " + dialogId : UserObject.getUserName(user);
        }
        TLRPC.Chat chat = org.telegram.messenger.MessagesController.getInstance(currentAccount).getChat(-dialogId);
        return chat == null ? "Chat " + (-dialogId) : chat.title;
    }

    private void openChatPicker() {
        if (!vault.isUnlocked()) {
            Toast.makeText(getParentActivity(), "ابتدا بخش گفتگوهای مخفی را باز کنید", Toast.LENGTH_SHORT).show();
            return;
        }
        android.os.Bundle args = new android.os.Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_WIDGET);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            try {
                for (MessagesStorage.TopicKey key : dids) {
                    if (key.topicId == 0) {
                        vault.hideDialog(key.dialogId);
                        org.telegram.messenger.NotificationsController.getInstance(currentAccount).removeNotificationsForDialog(key.dialogId);
                    }
                }
                org.telegram.messenger.NotificationCenter.getInstance(currentAccount).postNotificationName(org.telegram.messenger.NotificationCenter.dialogsNeedReload);
                fragment.finishFragment();
                refreshRows();
            } catch (Exception e) {
                Toast.makeText(getParentActivity(), "مخفی‌کردن گفتگوی انتخاب‌شده انجام نشد؛ بخش گفتگوهای مخفی را دوباره باز کنید.", Toast.LENGTH_LONG).show();
            }
            return true;
        });
        presentFragment(picker);
    }

    private void openHiddenChat(long dialogId) {
        android.os.Bundle args = new android.os.Bundle();
        if (dialogId > 0) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        presentFragment(new ChatActivity(args));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getParentActivity() != null) {
            getParentActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        refreshRows();
    }

    @Override
    public void onFragmentDestroy() {
        if (!handedOffToHiddenList && vault != null) vault.lock();
        if (getParentActivity() != null) {
            getParentActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        super.onFragmentDestroy();
    }
}
