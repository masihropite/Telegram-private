/*
 * Local hidden chats screen for this unofficial Telegram fork.
 * Hidden chats remain in Telegram storage; this screen only changes local visibility.
 */
package org.telegram.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.HiddenChatsVault;
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

    public HiddenChatsActivity(int account) {
        super();
        currentAccount = account;
    }

    @Override
    public boolean onFragmentCreate() {
        vault = new HiddenChatsVault(ApplicationLoader.applicationContext);
        return super.onFragmentCreate();
    }

    @Override
    protected View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Hidden chats");

        ScrollView scroll = new ScrollView(context);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        TextView description = new TextView(context);
        description.setText("Chats in this area are hidden locally. Their messages are not deleted.");
        description.setTextSize(14);
        content.addView(description, new LinearLayout.LayoutParams(-1, -2));

        Button unlock = new Button(context);
        unlock.setText(vault.isConfigured() ? "Unlock hidden chats" : "Set hidden-chats password");
        unlock.setOnClickListener(v -> requestPassword());
        content.addView(unlock, new LinearLayout.LayoutParams(-1, -2));

        Button lock = new Button(context);
        lock.setText("Lock now");
        lock.setOnClickListener(v -> {
            vault.lock();
            refreshRows();
        });
        content.addView(lock, new LinearLayout.LayoutParams(-1, -2));

        Button add = new Button(context);
        add.setText("Hide existing chats");
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

    private void requestPassword() {
        if (getParentActivity() == null) return;
        final EditText password = new EditText(getParentActivity());
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setHint("At least 8 characters, letters and numbers");
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
                .setTitle(vault.isConfigured() ? "Unlock hidden chats" : "Create password")
                .setView(password)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(vault.isConfigured() ? "Unlock" : "Continue", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            char[] entered = password.getText().toString().toCharArray();
            try {
                if (vault.isConfigured()) {
                    vault.unlock(entered);
                    dialog.dismiss();
                    refreshRows();
                } else {
                    requestPasswordConfirmation(entered.clone());
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

    private void requestPasswordConfirmation(char[] first) {
        final EditText confirmation = new EditText(getParentActivity());
        confirmation.setSingleLine(true);
        confirmation.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Confirm password")
                .setMessage("Enter the same password again.")
                .setView(confirmation)
                .setNegativeButton("Cancel", (d, w) -> Arrays.fill(first, '\0'))
                .setPositiveButton("Create", (d, w) -> {
                    char[] second = confirmation.getText().toString().toCharArray();
                    try {
                        if (!Arrays.equals(first, second)) {
                            Toast.makeText(getParentActivity(), "Passwords do not match", Toast.LENGTH_LONG).show();
                            return;
                        }
                        vault.create(first);
                        refreshRows();
                    } catch (Exception e) {
                        Toast.makeText(getParentActivity(), e.getMessage() == null ? "Could not create vault" : e.getMessage(), Toast.LENGTH_LONG).show();
                    } finally {
                        Arrays.fill(first, '\0');
                        Arrays.fill(second, '\0');
                        confirmation.setText("");
                    }
                }).show();
    }

    private void refreshRows() {
        if (rows == null) return;
        rows.removeAllViews();
        if (!vault.isUnlocked()) {
            TextView locked = new TextView(getParentActivity());
            locked.setText("The hidden-chat list is locked.");
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
        if (ids.isEmpty()) {
            TextView empty = new TextView(getParentActivity());
            empty.setText("No hidden chats yet.");
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
            Button restore = new Button(getParentActivity());
            restore.setText("Unhide");
            restore.setOnClickListener(v -> {
                try {
                    vault.unhideDialog(dialogId);
                    refreshRows();
                } catch (GeneralSecurityException e) {
                    Toast.makeText(getParentActivity(), "Could not restore chat", Toast.LENGTH_LONG).show();
                }
            });
            line.addView(restore, new LinearLayout.LayoutParams(-2, -2));
            rows.addView(line, new LinearLayout.LayoutParams(-1, -2));
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
            Toast.makeText(getParentActivity(), "Unlock the hidden-chat area first", Toast.LENGTH_SHORT).show();
            return;
        }
        android.os.Bundle args = new android.os.Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_WIDGET);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            try {
                for (MessagesStorage.TopicKey key : dids) {
                    if (key.topicId == 0) vault.hideDialog(key.dialogId);
                }
                fragment.finishFragment();
                refreshRows();
            } catch (GeneralSecurityException e) {
                Toast.makeText(getParentActivity(), "Could not hide selected chat", Toast.LENGTH_LONG).show();
            }
            return true;
        });
        presentFragment(picker);
    }

    @Override
    public void onFragmentDestroy() {
        if (vault != null) vault.lock();
        super.onFragmentDestroy();
    }
}
