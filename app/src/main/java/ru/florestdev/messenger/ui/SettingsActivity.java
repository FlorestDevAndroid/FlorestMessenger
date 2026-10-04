package ru.florestdev.messenger.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.util.Prefs;
import ru.florestdev.messenger.util.ThemeHelper;

public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyToActivity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        tb.setNavigationOnClickListener(v -> finish());
        Prefs prefs = new Prefs(this);
        bind(R.id.btnDark, "dark", prefs);
        bind(R.id.btnLight, "light", prefs);
        bind(R.id.btnAmoled, "amoled", prefs);
        bind(R.id.btnBlue, "blue", prefs);
        bind(R.id.btnPurple, "purple", prefs);
        bind(R.id.btnGreen, "green", prefs);
    }

    private void bind(int id, String theme, Prefs prefs) {
        MaterialButton b = findViewById(id);
        if (b == null) return;
        String cur = prefs.getTheme();
        if (theme.equals(cur)) b.setEnabled(false);
        b.setOnClickListener(v -> {
            prefs.setTheme(theme);
            Toast.makeText(this, "Тема применена", Toast.LENGTH_SHORT).show();
            // Полный перезапуск главного UI
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
        });
    }

    /** @deprecated use ThemeHelper */
    public static void applyTheme(String theme) {
        // kept for MainActivity calls
    }
}
