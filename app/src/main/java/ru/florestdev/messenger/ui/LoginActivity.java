package ru.florestdev.messenger.ui;

import ru.florestdev.messenger.util.ThemeHelper;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.CheckBox;
import android.net.Uri;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.network.ApiClient;
import ru.florestdev.messenger.network.SocketManager;
import ru.florestdev.messenger.util.Prefs;
import ru.florestdev.messenger.service.MessengerSocketService;

public class LoginActivity extends AppCompatActivity {
    private TextInputEditText etUser, etPass;
    private MaterialButton btnLogin, btnRegister;
    private ProgressBar progress;
    private TextView tvError;
    private View layoutLegal;
    private CheckBox cbTos, cbPrivacy;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyToActivity(this);
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        if (prefs.isLoggedIn() && prefs.getUsername() != null) {
            goMain();
            return;
        }
        setContentView(R.layout.activity_login);
        etUser = findViewById(R.id.etUsername);
        etPass = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        btnRegister = findViewById(R.id.btnRegister);
        progress = findViewById(R.id.progress);
        View btnRecovery = findViewById(R.id.btnRecovery);
        if (btnRecovery != null) btnRecovery.setOnClickListener(v -> showRecovery());
        tvError = findViewById(R.id.tvError);
        layoutLegal = findViewById(R.id.layoutLegal);
        cbTos = findViewById(R.id.cbTos);
        cbPrivacy = findViewById(R.id.cbPrivacy);
        View linkTos = findViewById(R.id.linkTos);
        View linkPrivacy = findViewById(R.id.linkPrivacy);
        if (linkTos != null) {
            linkTos.setOnClickListener(v -> openUrl(ApiClient.BASE + "/tos"));
        }
        if (linkPrivacy != null) {
            linkPrivacy.setOnClickListener(v -> openUrl(ApiClient.BASE + "/privacy-policy"));
        }

        btnLogin.setOnClickListener(v -> {
            if (layoutLegal != null) layoutLegal.setVisibility(View.GONE);
            doLogin(false);
        });
        btnRegister.setOnClickListener(v -> {
            // первый тап — показать чекбоксы, второй (с галочками) — регистрация
            if (layoutLegal != null && layoutLegal.getVisibility() != View.VISIBLE) {
                layoutLegal.setVisibility(View.VISIBLE);
                showError("Примите Условия и Политику конфиденциальности");
                return;
            }
            doLogin(true);
        });
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show();
        }
    }

    private void doLogin(boolean register) {
        String u = etUser.getText() != null ? etUser.getText().toString().trim() : "";
        String p = etPass.getText() != null ? etPass.getText().toString() : "";
        if (u.length() < 2 || p.isEmpty()) {
            showError("Введите логин и пароль");
            return;
        }
        if (register) {
            boolean tos = cbTos != null && cbTos.isChecked();
            boolean priv = cbPrivacy != null && cbPrivacy.isChecked();
            if (!tos || !priv) {
                if (layoutLegal != null) layoutLegal.setVisibility(View.VISIBLE);
                showError("Нужно принять Условия использования и Политику конфиденциальности");
                return;
            }
        }
        setLoading(true);
        // сохраняем username заранее (нужно для 2FA)
        prefs.setUsername(u);

        ApiClient.Callback<Boolean> cb = new ApiClient.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                runOnUiThread(() -> {
                    setLoading(false);
                    SocketManager.get().connect();
                    ru.florestdev.messenger.util.FcmHelper.registerIfLoggedIn(LoginActivity.this);
                    MessengerSocketService.start(LoginActivity.this);
                    goMain();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    setLoading(false);
                    if (ApiClient.NEED_2FA.equals(error)) {
                        show2faDialog();
                    } else {
                        showError(error != null ? error : "Ошибка входа");
                    }
                });
            }
        };
        if (register) ApiClient.get().register(u, p, cb);
        else ApiClient.get().login(u, p, cb);
    }

    private void show2faDialog() {
        EditText input = new EditText(this);
        input.setHint("Код из письма или recovery-код");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setFilters(new android.text.InputFilter[]{ new android.text.InputFilter.LengthFilter(32) });
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout wrap = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = pad;
        lp.rightMargin = pad;
        wrap.addView(input, lp);

        new AlertDialog.Builder(this)
                .setTitle("Двухфакторная аутентификация")
                .setMessage("6 цифр из письма ИЛИ recovery-код (буквы и дефисы):")
                .setView(wrap)
                .setCancelable(false)
                .setPositiveButton("Подтвердить", (d, w) -> {
                    String code = input.getText() != null ? input.getText().toString().trim() : "";
                    if (code.length() < 4) {
                        showError("Код из письма или recovery-код");
                        return;
                    }
                    setLoading(true);
                    ApiClient.get().verify2fa(code, new ApiClient.Callback<Boolean>() {
                        @Override
                        public void onSuccess(Boolean result) {
                            runOnUiThread(() -> {
                                setLoading(false);
                                SocketManager.get().connect();
                                ru.florestdev.messenger.util.FcmHelper.registerIfLoggedIn(LoginActivity.this);
                    MessengerSocketService.start(LoginActivity.this);
                                goMain();
                            });
                        }

                        @Override
                        public void onError(String error) {
                            runOnUiThread(() -> {
                                setLoading(false);
                                showError(error);
                            });
                        }
                    });
                })
                .setNegativeButton("Отмена", (d, w) -> showError("Вход отменён (нужен код 2FA)"))
                .show();
    }

    private void setLoading(boolean v) {
        progress.setVisibility(v ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(!v);
        btnRegister.setEnabled(!v);
        if (v) tvError.setVisibility(View.GONE);
    }

    private void showError(String e) {
        tvError.setText(e);
        tvError.setVisibility(View.VISIBLE);
        Toast.makeText(this, e, Toast.LENGTH_LONG).show();
    }


    private void showRecovery() {
        EditText etUser = new EditText(this);
        etUser.setHint("Ник");
        EditText etCode = new EditText(this);
        etCode.setHint("XXXX-XXXX-XXXX-XXXX");
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        box.addView(etUser);
        box.addView(etCode);
        new AlertDialog.Builder(this)
                .setTitle("Recovery-код")
                .setMessage("Одноразовый код из настроек аккаунта")
                .setView(box)
                .setPositiveButton("Войти", (d, w) -> {
                    String u = etUser.getText() != null ? etUser.getText().toString().trim() : "";
                    String c = etCode.getText() != null ? etCode.getText().toString().trim() : "";
                    if (u.isEmpty() || c.isEmpty()) {
                        showError("Заполни ник и код");
                        return;
                    }
                    setLoading(true);
                    ApiClient.get().recoveryLogin(u, c, new ApiClient.Callback<Boolean>() {
                        @Override
                        public void onSuccess(Boolean result) {
                            runOnUiThread(() -> {
                                setLoading(false);
                                prefs.setUsername(u);
                                prefs.setLoggedIn(true);
                                SocketManager.get().connect();
                                ru.florestdev.messenger.util.FcmHelper.registerIfLoggedIn(LoginActivity.this);
                    MessengerSocketService.start(LoginActivity.this);
                                goMain();
                            });
                        }
                        @Override
                        public void onError(String error) {
                            runOnUiThread(() -> {
                                setLoading(false);
                                showError(error);
                            });
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void goMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
