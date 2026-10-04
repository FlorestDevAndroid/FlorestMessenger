package ru.florestdev.messenger.ui;

import ru.florestdev.messenger.util.ThemeHelper;

import android.os.Bundle;
import android.view.View;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONObject;

import java.util.List;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.adapter.MessageAdapter;
import ru.florestdev.messenger.model.Message;
import ru.florestdev.messenger.network.ApiClient;
import ru.florestdev.messenger.network.SocketManager;
import ru.florestdev.messenger.util.Prefs;

public class OrgChatActivity extends AppCompatActivity implements SocketManager.Listener {
    private int orgId, chatId;
    private String username;
    private MessageAdapter adapter;
    private RecyclerView rv;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            loadHttp(false);
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyToActivity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_chat);
        orgId = getIntent().getIntExtra("org_id", 0);
        chatId = getIntent().getIntExtra("chat_id", 0);
        String name = getIntent().getStringExtra("chat_name");
        username = new Prefs(this).getUsername();

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setTitle(name != null ? "# " + name : "Чат");
        tb.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        tb.setNavigationOnClickListener(v -> finish());

        android.view.View callPanel = findViewById(R.id.callPanel);
        if (callPanel != null) callPanel.setVisibility(android.view.View.GONE);
        android.view.View btnCall = findViewById(R.id.btnCall);
        if (btnCall != null) btnCall.setVisibility(android.view.View.GONE);

        rv = findViewById(R.id.rvMessages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rv.setLayoutManager(lm);
        adapter = new MessageAdapter(username);
        adapter.setLongClickListener(this::showReactDialog);
        rv.setAdapter(adapter);

        EditText et = findViewById(R.id.etMessage);
        ImageButton btn = findViewById(R.id.btnSend);
        btn.setOnClickListener(v -> {
            String text = et.getText() != null ? et.getText().toString().trim() : "";
            if (text.isEmpty() || username == null) return;
            Message m = new Message();
            m.username = username;
            m.content = text;
            m.message = text;
            m.isOwn = true;
            adapter.add(m);
            rv.scrollToPosition(adapter.size() - 1);
            et.setText("");
            try {
                JSONObject o = new JSONObject();
                o.put("org_id", orgId);
                o.put("chat_id", chatId);
                o.put("message", text);
                o.put("username", username);
                SocketManager.get().emitRaw("send_org_message", o);
            } catch (Exception ignored) {}
            ApiClient.get().sendOrgMessage(orgId, chatId, text, new ApiClient.Callback<Boolean>() {
                @Override public void onSuccess(Boolean result) {}
                @Override public void onError(String error) {
                    runOnUiThread(() -> Toast.makeText(OrgChatActivity.this, "Ошибка: " + error, Toast.LENGTH_SHORT).show());
                }
            });
        });

        View btnSt = findViewById(R.id.btnStickers);
        if (btnSt != null) btnSt.setOnClickListener(v -> openStickerPicker());
        android.view.View _tb = findViewById(R.id.toolbar);
        if (_tb != null) _tb.setOnLongClickListener(v -> { createPollDialog(); return true; });
        SocketManager.get().connect();
        SocketManager.get().addListener(this);
        joinOrg();
        loadHttp(true);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.postDelayed(poll, 3000);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    private void joinOrg() {
        try {
            JSONObject o = new JSONObject();
            o.put("org_id", orgId);
            o.put("chat_id", chatId);
            if (username != null) o.put("username", username);
            SocketManager.get().emitRaw("join_org_chat", o);
        } catch (Exception ignored) {}
    }

    private void loadHttp(boolean scroll) {
        ApiClient.get().fetchOrgMessages(orgId, chatId, new ApiClient.Callback<List<Message>>() {
            @Override
            public void onSuccess(List<Message> result) {
                runOnUiThread(() -> {
                    if (result == null) return;
                    int prev = adapter.size();
                    adapter.setItems(result);
                    if (scroll || result.size() != prev) {
                        if (adapter.size() > 0) rv.scrollToPosition(adapter.size() - 1);
                    }
                });
            }
            @Override public void onError(String error) {}
        });
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(poll);
        SocketManager.get().removeListener(this);
        super.onDestroy();
    }

    @Override public void onConnected() { joinOrg(); }
    @Override public void onDisconnected() {}
    @Override public void onGroupMessage(Message msg) {
        if (msg == null) return;
        // org realtime may come without groupId; rely on reload via poll / socket org path
        adapter.add(msg);
        rv.scrollToPosition(adapter.size() - 1);
    }
    @Override public void onPrivateMessage(Message msg) {}
    @Override public void onSystem(String text) {}

    private void openStickerPicker() {
        ApiClient.get().fetchStickerPacks(new ApiClient.Callback<java.util.List<java.util.Map<String, Object>>>() {
            @Override
            public void onSuccess(java.util.List<java.util.Map<String, Object>> packs) {
                runOnUiThread(() -> {
                    if (packs == null || packs.isEmpty()) {
                        Toast.makeText(OrgChatActivity.this, "Нет стикер-паков. Добавь на сайте /stickers", Toast.LENGTH_LONG).show();
                        return;
                    }
                    // load first pack stickers
                    Object idObj = packs.get(0).get("id");
                    int packId = idObj instanceof Number ? ((Number) idObj).intValue() : 0;
                    ApiClient.get().fetchPackStickers(packId, new ApiClient.Callback<java.util.List<String>>() {
                        @Override
                        public void onSuccess(java.util.List<String> urls) {
                            runOnUiThread(() -> showStickerGrid(urls));
                        }
                        @Override
                        public void onError(String error) {
                            runOnUiThread(() -> Toast.makeText(OrgChatActivity.this, error, Toast.LENGTH_SHORT).show());
                        }
                    });
                });
            }
            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(OrgChatActivity.this, error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showStickerGrid(java.util.List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            Toast.makeText(this, "В паке нет стикеров", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = urls.toArray(new String[0]);
        // show simple list of indices + send
        CharSequence[] labels = new CharSequence[urls.size()];
        for (int i = 0; i < urls.size(); i++) labels[i] = "Стикер " + (i + 1);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Стикер")
                .setItems(labels, (d, which) -> sendStickerUrl(urls.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void sendStickerUrl(String url) {
        if (url == null || url.isEmpty()) return;
        // put URL into message field and trigger same send path
        EditText et = findViewById(R.id.etMessage);
        if (et != null) {
            et.setText(url);
            findViewById(R.id.btnSend).performClick();
        }
    }



    private void createPollDialog() {
        final android.widget.EditText q = new android.widget.EditText(this);
        q.setHint("Вопрос");
        final android.widget.EditText opts = new android.widget.EditText(this);
        opts.setHint("Варианты (с новой строки)");
        opts.setMinLines(3);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 8);
        box.addView(q);
        box.addView(opts);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Опрос в организации")
                .setView(box)
                .setPositiveButton("Создать", (d, w) -> {
                    String question = q.getText().toString().trim();
                    java.util.List<String> options = new java.util.ArrayList<>();
                    for (String line : opts.getText().toString().split("\n")) {
                        if (line.trim().length() > 0) options.add(line.trim());
                    }
                    if (question.isEmpty() || options.size() < 2) {
                        android.widget.Toast.makeText(this, "Нужны вопрос и 2+ варианта", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ApiClient.get().createPoll("org", orgId, chatId, question, options, "poll", null,
                            new ApiClient.Callback<String>() {
                                @Override public void onSuccess(String result) {
                                    runOnUiThread(() -> android.widget.Toast.makeText(OrgChatActivity.this, "Опрос создан", android.widget.Toast.LENGTH_SHORT).show());
                                }
                                @Override public void onError(String error) {
                                    runOnUiThread(() -> android.widget.Toast.makeText(OrgChatActivity.this, error, android.widget.Toast.LENGTH_SHORT).show());
                                }
                            });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }


    private void showReactDialog(ru.florestdev.messenger.model.Message m) {
        if (m == null || m.id <= 0) return;
        final String[] E = {"👍", "❤️", "😂", "🔥", "😮", "😢", "👏"};
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Реакция")
                .setItems(E, (d, which) -> {
                    org.json.JSONObject o = new org.json.JSONObject();
                    try {
                        o.put("message_id", m.id);
                        o.put("chat_id", chatId);
                        o.put("emoji", E[which]);
                        ru.florestdev.messenger.network.SocketManager.get().emit("react_org", o);
                    } catch (Exception ignored) {}
                })
                .show();
    }

}
