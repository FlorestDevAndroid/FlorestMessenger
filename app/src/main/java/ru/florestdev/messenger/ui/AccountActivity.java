package ru.florestdev.messenger.ui;

import ru.florestdev.messenger.util.ThemeHelper;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.model.AccountInfo;
import ru.florestdev.messenger.network.ApiClient;
import ru.florestdev.messenger.network.SocketManager;
import ru.florestdev.messenger.service.MessengerSocketService;
import ru.florestdev.messenger.util.Prefs;

public class AccountActivity extends AppCompatActivity {
    private Prefs prefs;
    private ImageView ivAvatar;
    private TextView tvUsername, tvEmailStatus;
    private EditText etBio, etCurrentPassword, etNewPassword, etNewUsername, etEmail, etDeletePassword;

    private final ActivityResultLauncher<String> pickImage =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) uploadAvatar(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyToActivity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);
        prefs = new Prefs(this);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        tb.setNavigationOnClickListener(v -> finish());

        ivAvatar = findViewById(R.id.ivAvatar);
        tvUsername = findViewById(R.id.tvUsername);
        tvEmailStatus = findViewById(R.id.tvEmailStatus);
        etBio = findViewById(R.id.etBio);
        etCurrentPassword = findViewById(R.id.etCurrentPassword);
        etNewPassword = findViewById(R.id.etNewPassword);
        etNewUsername = findViewById(R.id.etNewUsername);
        etEmail = findViewById(R.id.etEmail);
        etDeletePassword = findViewById(R.id.etDeletePassword);

        String user = prefs.getUsername();
        tvUsername.setText(user != null ? user : "");
        if (user != null) {
            Glide.with(this)
                    .load(ApiClient.get().avatarUrl(user) + "?t=" + System.currentTimeMillis())
                    .transform(new CircleCrop())
                    .into(ivAvatar);
        }

        findViewById(R.id.btnPickAvatar).setOnClickListener(v -> pickImage.launch("image/*"));
        findViewById(R.id.btnSaveBio).setOnClickListener(v -> saveBio());
        findViewById(R.id.btnChangePassword).setOnClickListener(v -> changePassword());
        findViewById(R.id.btnChangeUsername).setOnClickListener(v -> changeUsername());
        findViewById(R.id.btnBindEmail).setOnClickListener(v -> bindEmail());
        findViewById(R.id.btnDeleteAccount).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.btnLogout).setOnClickListener(v -> logout());
        // Telegram channel bind
        loadTgSection();

        // recovery codes: use toolbar long title area via button if present
        findViewById(R.id.btnRecoveryCodes).setOnClickListener(v -> resendRecovery());

        loadInfo();
    }

    private void loadInfo() {
        ApiClient.get().fetchAccountInfo(new ApiClient.Callback<AccountInfo>() {
            @Override
            public void onSuccess(AccountInfo result) {
                runOnUiThread(() -> {
                    if (result.bio != null) etBio.setText(result.bio);
                    if (result.emailStatus != null) tvEmailStatus.setText(result.emailStatus);
                    if (result.username != null) {
                        tvUsername.setText(result.username);
                        prefs.setUsername(result.username);
                    }
                });
            }

            @Override
            public void onError(String error) {
                // ignore soft
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void saveBio() {
        String bio = etBio.getText() != null ? etBio.getText().toString() : "";
        ApiClient.get().updateBio(bio, new ApiClient.Callback<String>() {
            @Override public void onSuccess(String result) { runOnUiThread(() -> toast(result != null ? result : "Сохранено")); }
            @Override public void onError(String error) { runOnUiThread(() -> toast(error)); }
        });
    }

    private void changePassword() {
        String cur = text(etCurrentPassword);
        String neu = text(etNewPassword);
        if (cur.isEmpty() || neu.isEmpty()) {
            toast("Введи оба пароля");
            return;
        }
        ApiClient.get().changePassword(cur, neu, new ApiClient.Callback<String>() {
            @Override public void onSuccess(String result) {
                runOnUiThread(() -> {
                    toast(result != null ? result : "Пароль изменён");
                    etCurrentPassword.setText("");
                    etNewPassword.setText("");
                });
            }
            @Override public void onError(String error) { runOnUiThread(() -> toast(error)); }
        });
    }

    private void changeUsername() {
        String neu = text(etNewUsername);
        if (neu.length() < 2) {
            toast("Минимум 2 символа");
            return;
        }
        ApiClient.get().changeUsername(neu, new ApiClient.Callback<String>() {
            @Override public void onSuccess(String result) {
                runOnUiThread(() -> {
                    toast(result != null ? result : "Ник изменён");
                    tvUsername.setText(neu);
                    prefs.setUsername(neu);
                });
            }
            @Override public void onError(String error) { runOnUiThread(() -> toast(error)); }
        });
    }

    private void bindEmail() {
        String email = text(etEmail);
        if (!email.contains("@")) {
            toast("Введи нормальный email");
            return;
        }
        ApiClient.get().bindEmail(email, new ApiClient.Callback<String>() {
            @Override public void onSuccess(String result) {
                runOnUiThread(() -> {
                    toast(result != null ? result : "Почта привязана");
                    loadInfo();
                });
            }
            @Override public void onError(String error) { runOnUiThread(() -> toast(error)); }
        });
    }

    private void confirmDelete() {
        String pass = text(etDeletePassword);
        if (pass.isEmpty()) {
            toast("Введи пароль");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Удалить аккаунт?")
                .setMessage("Это действие нельзя отменить.")
                .setPositiveButton("Удалить", (d, w) ->
                        ApiClient.get().deleteAccount(pass, new ApiClient.Callback<String>() {
                            @Override
                            public void onSuccess(String result) {
                                runOnUiThread(() -> {
                                    toast("Аккаунт удалён");
                                    logout();
                                });
                            }

                            @Override
                            public void onError(String error) {
                                runOnUiThread(() -> toast(error));
                            }
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void uploadAvatar(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                toast("Не удалось открыть файл");
                return;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            byte[] data = bos.toByteArray();
            String mime = getContentResolver().getType(uri);
            if (mime == null) mime = "image/jpeg";
            ApiClient.get().uploadAvatar(data, "avatar.jpg", mime, new ApiClient.Callback<String>() {
                @Override
                public void onSuccess(String result) {
                    runOnUiThread(() -> {
                        toast(result != null ? result : "Аватар обновлён");
                        String user = prefs.getUsername();
                        if (user != null) {
                            Glide.with(AccountActivity.this)
                                    .load(ApiClient.get().avatarUrl(user) + "?t=" + System.currentTimeMillis())
                                    .transform(new CircleCrop())
                                    .into(ivAvatar);
                        }
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> toast(error));
                }
            });
        } catch (Exception e) {
            toast(e.getMessage() != null ? e.getMessage() : "Ошибка");
        }
    }

    private String text(EditText et) {
        return et.getText() != null ? et.getText().toString().trim() : "";
    }


    private void generateRecovery() {
        new AlertDialog.Builder(this)
                .setTitle("Recovery-коды")
                .setMessage("Сгенерировать 10 новых кодов? Старые перестанут работать.")
                .setPositiveButton("Сгенерировать", (d, w) ->
                        ApiClient.get().generateRecoveryCodes(new ApiClient.Callback<java.util.List<String>>() {
                            @Override
                            public void onSuccess(java.util.List<String> result) {
                                runOnUiThread(() -> {
                                    StringBuilder sb = new StringBuilder();
                                    if (result != null) for (String c : result) sb.append(c).append("\n");
                                    new AlertDialog.Builder(AccountActivity.this)
                                            .setTitle("Сохрани коды!")
                                            .setMessage(sb.toString())
                                            .setPositiveButton("OK", null)
                                            .show();
                                });
                            }
                            @Override
                            public void onError(String error) {
                                runOnUiThread(() -> toast(error));
                            }
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }


    private void resendRecovery() {
        new AlertDialog.Builder(this)
                .setTitle("Recovery-коды")
                .setMessage("Отправить новые recovery-коды на привязанную почту?")
                .setPositiveButton("Отправить", (d, w) ->
                        ApiClient.get().resendRecoveryCodes(new ApiClient.Callback<String>() {
                            @Override
                            public void onSuccess(String result) {
                                runOnUiThread(() -> toast(result != null ? result : "Отправлено"));
                            }
                            @Override
                            public void onError(String error) {
                                runOnUiThread(() -> toast(error));
                            }
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void logout() {
        MessengerSocketService.stop(this);
        SocketManager.get().disconnect();
        ApiClient.get().logout(null);
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finishAffinity();
    }

    private void loadTgSection() {
        android.view.View logout = findViewById(R.id.btnLogout);
        if (logout == null || !(logout.getParent() instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup parent = (android.view.ViewGroup) logout.getParent();

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("Telegram → Блоги");
        title.setTextSize(18);
        title.setPadding(0, 48, 0, 8);
        title.setTextColor(0xFFE8EAED);
        parent.addView(title, parent.indexOfChild(logout));

        final android.widget.TextView status = new android.widget.TextView(this);
        status.setText("Загрузка TG...");
        status.setTextColor(0xFF94A3B8);
        parent.addView(status, parent.indexOfChild(logout));

        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("@channel или t.me/channel");
        et.setTextColor(0xFFE8EAED);
        et.setHintTextColor(0xFF64748B);
        parent.addView(et, parent.indexOfChild(logout));

        com.google.android.material.button.MaterialButton link =
                new com.google.android.material.button.MaterialButton(this);
        link.setText("Привязать канал");
        parent.addView(link, parent.indexOfChild(logout));

        final android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        parent.addView(list, parent.indexOfChild(logout));

        final Runnable[] refreshHolder = new Runnable[1];
        refreshHolder[0] = () -> ApiClient.get().fetchTgStatus(new ApiClient.Callback<String>() {
            @Override
            public void onSuccess(String result) {
                runOnUiThread(() -> {
                    try {
                        org.json.JSONObject j = new org.json.JSONObject(result);
                        if (!j.optBoolean("bot_configured")) {
                            status.setText("Бот не настроен (TG_MSGS_TOKEN)");
                            return;
                        }
                        status.setText("Бот @" + j.optString("bot_username", "?")
                                + " — добавь админом в канал");
                        list.removeAllViews();
                        org.json.JSONArray links = j.optJSONArray("links");
                        if (links == null || links.length() == 0) {
                            android.widget.TextView empty =
                                    new android.widget.TextView(AccountActivity.this);
                            empty.setText("Нет привязанных каналов");
                            empty.setTextColor(0xFF64748B);
                            list.addView(empty);
                        } else {
                            for (int i = 0; i < links.length(); i++) {
                                org.json.JSONObject L = links.getJSONObject(i);
                                String titleStr = L.optString("title",
                                        String.valueOf(L.optLong("channel_id")));
                                final long cid = L.optLong("channel_id");
                                com.google.android.material.button.MaterialButton un =
                                        new com.google.android.material.button.MaterialButton(
                                                AccountActivity.this);
                                un.setText("Отвязать: " + titleStr);
                                un.setOnClickListener(v2 -> ApiClient.get().tgUnlinkChannel(
                                        cid, new ApiClient.Callback<Boolean>() {
                                            @Override
                                            public void onSuccess(Boolean r) {
                                                if (refreshHolder[0] != null) {
                                                    runOnUiThread(refreshHolder[0]);
                                                }
                                            }
                                            @Override
                                            public void onError(String e) {
                                                runOnUiThread(() ->
                                                        android.widget.Toast.makeText(
                                                                AccountActivity.this, e,
                                                                android.widget.Toast.LENGTH_SHORT)
                                                                .show());
                                            }
                                        }));
                                list.addView(un);
                            }
                        }
                    } catch (Exception e) {
                        status.setText(result);
                    }
                });
            }
            @Override
            public void onError(String error) {
                runOnUiThread(() -> status.setText(error));
            }
        });
        refreshHolder[0].run();

        link.setOnClickListener(v -> {
            String ch = et.getText().toString().trim();
            if (ch.isEmpty()) return;
            ApiClient.get().tgLinkChannel(ch, new ApiClient.Callback<String>() {
                @Override
                public void onSuccess(String result) {
                    runOnUiThread(() -> {
                        android.widget.Toast.makeText(AccountActivity.this, "Привязано",
                                android.widget.Toast.LENGTH_SHORT).show();
                        et.setText("");
                        if (refreshHolder[0] != null) refreshHolder[0].run();
                    });
                }
                @Override
                public void onError(String error) {
                    runOnUiThread(() -> android.widget.Toast.makeText(AccountActivity.this, error,
                            android.widget.Toast.LENGTH_LONG).show());
                }
            });
        });
    }




}
