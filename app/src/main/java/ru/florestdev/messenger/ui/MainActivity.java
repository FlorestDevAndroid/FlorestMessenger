package ru.florestdev.messenger.ui;

import ru.florestdev.messenger.util.ThemeHelper;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ru.florestdev.messenger.R;
import android.content.Intent;
import ru.florestdev.messenger.adapter.MenuAdapter;
import ru.florestdev.messenger.model.Group;
import ru.florestdev.messenger.network.ApiClient;
import ru.florestdev.messenger.network.SocketManager;
import ru.florestdev.messenger.service.MessengerSocketService;
import ru.florestdev.messenger.util.Prefs;

public class MainActivity extends AppCompatActivity {
    private Prefs prefs;

    private final ActivityResultLauncher<String> notifPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // сервис уже запущен; permission влияет на показ уведомлений
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyToActivity(this);
        super.onCreate(savedInstanceState);
        ru.florestdev.messenger.util.FcmHelper.registerIfLoggedIn(this);
        prefs = new Prefs(this);
        if (!prefs.isLoggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);

        TextView tvWelcome = findViewById(R.id.tvWelcome);
        tvWelcome.setText(getString(R.string.welcome, prefs.getUsername()));

        ImageButton btnLogout = findViewById(R.id.btnLogout);
        btnLogout.setOnClickListener(v -> logout());

        RecyclerView rv = findViewById(R.id.rvMenu);
        rv.setLayoutManager(new LinearLayoutManager(this));

        List<MenuAdapter.Item> items = new ArrayList<>();
        items.add(new MenuAdapter.Item("👥", "Группы", "Чаты, файлы, войс",
                () -> startActivity(new Intent(this, GroupsActivity.class))));
        items.add(new MenuAdapter.Item("🏢", "Организации", "Закрытые, только по ссылке",
                () -> startActivity(new Intent(this, OrgsActivity.class))));
        items.add(new MenuAdapter.Item("💬", "Личные сообщения", "Поиск по нику",
                () -> startActivity(new Intent(this, DmActivity.class))));
        items.add(new MenuAdapter.Item("📝", "Блоги", "Посты, лайки, комментарии",
                () -> startActivity(new Intent(this, BlogsActivity.class))));
        items.add(new MenuAdapter.Item("⏱", "Истории", "24 часа",
                () -> startActivity(new Intent(this, StoriesActivity.class))));
        items.add(new MenuAdapter.Item("🎨", "Настройки", "Цветовые схемы",
                () -> startActivity(new Intent(this, SettingsActivity.class))));
        items.add(new MenuAdapter.Item("⚙️", "Аккаунт", "Профиль, пароль, аватар",
                () -> startActivity(new Intent(this, AccountActivity.class))));

        rv.setAdapter(new MenuAdapter(items));

        requestNotifPermission();
        SocketManager.get().connect();
        MessengerSocketService.start(this);
        refreshMemberGroups();
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    /** Подтянуть список групп, в которых состоишь — для фильтра уведомлений */
    private void refreshMemberGroups() {
        ApiClient.get().fetchGroups(new ApiClient.Callback<List<Group>>() {
            @Override
            public void onSuccess(List<Group> result) {
                Set<Integer> ids = new HashSet<>();
                if (result != null) {
                    for (Group g : result) {
                        if (g.isMember && g.id > 0) {
                            ids.add(g.id);
                            prefs.setGroupName(g.id, g.name);
                        }
                    }
                }
                prefs.setMemberGroupIds(ids);
                MessengerSocketService.refreshGroups(MainActivity.this);
            }

            @Override
            public void onError(String error) {
                // ignore
            }
        });
    }

    private void logout() {
        MessengerSocketService.stop(this);
        SocketManager.get().disconnect();
        ApiClient.get().logout(null);
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    // called from menu if wired
    public void openExtra(String which) {
        if ("settings".equals(which)) startActivity(new Intent(this, SettingsActivity.class));
        else if ("stories".equals(which)) startActivity(new Intent(this, StoriesActivity.class));
    }
}
