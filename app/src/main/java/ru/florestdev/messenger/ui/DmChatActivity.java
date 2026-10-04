package ru.florestdev.messenger.ui;

import ru.florestdev.messenger.util.ThemeHelper;

import android.os.Bundle;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.content.ContentResolver;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.List;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.adapter.MessageAdapter;
import ru.florestdev.messenger.model.Message;
import ru.florestdev.messenger.network.ApiClient;
import ru.florestdev.messenger.network.SocketManager;
import ru.florestdev.messenger.util.Prefs;

public class DmChatActivity extends AppCompatActivity implements SocketManager.Listener {
    private static final String[] EMOJIS = {"👍", "❤️", "😂", "🔥", "😮", "😢", "👏"};

    private String partner;
    private String username;
    private MessageAdapter adapter;
    private RecyclerView rv;
    private EditText etMessage;
    private TextView tvTyping;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable stopTypingNotify;
    private boolean typingSent;
    private final ActivityResultLauncher<String> pickFile =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onFilePicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyToActivity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dm_chat);

        partner = getIntent().getStringExtra("partner");
        if (partner == null) partner = "";
        username = new Prefs(this).getUsername();

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setTitle(partner);
        tb.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        tb.setNavigationOnClickListener(v -> finish());

        tvTyping = findViewById(R.id.tvTyping);
        rv = findViewById(R.id.rvMessages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rv.setLayoutManager(lm);
        adapter = new MessageAdapter(username);
        adapter.setLongClickListener(this::showReactDialog);
        rv.setAdapter(adapter);

        etMessage = findViewById(R.id.etMessage);
        ImageButton btnSend = findViewById(R.id.btnSend);
        btnSend.setOnClickListener(v -> send());

        etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                notifyTyping();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        View btnSt = findViewById(R.id.btnStickers);
        if (btnSt != null) btnSt.setOnClickListener(v -> openStickerPicker());
        View btnCall = findViewById(R.id.btnCall);
        if (btnCall != null) btnCall.setOnClickListener(v -> CallActivity.startOutgoing(this, partner));
        View btnAttach = findViewById(R.id.btnAttach);
        if (btnAttach != null) btnAttach.setOnClickListener(v -> pickFile.launch("*/*"));
        SocketManager.get().connect();
        SocketManager.get().addListener(this);
        new Prefs(this).setActiveChat(Prefs.dmKey(partner));

        loadHistory();
        loadReadStatus();
    }

    private void notifyTyping() {
        if (!typingSent) {
            SocketManager.get().sendTyping(partner);
            typingSent = true;
        }
        if (stopTypingNotify != null) handler.removeCallbacks(stopTypingNotify);
        stopTypingNotify = () -> {
            SocketManager.get().sendStopTyping(partner);
            typingSent = false;
        };
        handler.postDelayed(stopTypingNotify, 2500);
    }

    private void showReactDialog(Message m) {
        if (m.id <= 0) {
            Toast.makeText(this, "Подождите, сообщение ещё отправляется", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Реакция")
                .setItems(EMOJIS, (d, which) ->
                        SocketManager.get().sendReact(m.id, EMOJIS[which]))
                .setNegativeButton("Убрать", (d, w) ->
                        SocketManager.get().sendReact(m.id, ""))
                .show();
    }

    private void loadReadStatus() {
        ApiClient.get().fetchDmStatus(partner, new ApiClient.Callback<long[]>() {
            @Override
            public void onSuccess(long[] result) {
                // [their, my]
                runOnUiThread(() -> {
                    if (result != null && result.length >= 1) {
                        adapter.setTheirLastReadId(result[0]);
                    }
                    markRead();
                });
            }
            @Override
            public void onError(String error) {
                runOnUiThread(this::markRead);
            }
            private void markRead() {
                long max = adapter.maxIncomingId();
                if (max > 0) SocketManager.get().sendDmRead(partner, max);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        new Prefs(this).setActiveChat(Prefs.dmKey(partner));
        long max = adapter.maxIncomingId();
        if (max > 0) SocketManager.get().sendDmRead(partner, max);
    }

    @Override
    protected void onPause() {
        new Prefs(this).setActiveChat("");
        SocketManager.get().sendStopTyping(partner);
        super.onPause();
    }

    private void loadHistory() {
        ApiClient.get().fetchDmHistory(partner, new ApiClient.Callback<List<Message>>() {
            @Override
            public void onSuccess(List<Message> result) {
                runOnUiThread(() -> {
                    adapter.setItems(result);
                    if (adapter.size() > 0) rv.scrollToPosition(adapter.size() - 1);
                    long max = adapter.maxIncomingId();
                    if (max > 0) SocketManager.get().sendDmRead(partner, max);
                });
            }
            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(DmChatActivity.this, error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void send() {
        String text = etMessage.getText() != null ? etMessage.getText().toString().trim() : "";
        if (text.isEmpty() || username == null) return;
        SocketManager.get().sendStopTyping(partner);
        typingSent = false;
        SocketManager.get().sendPrivateMessage(partner, username, text);
        Message m = new Message();
        m.username = username;
        m.content = text;
        m.message = text;
        m.isOwn = true;
        m.isPrivate = true;
        m.recipient = partner;
        m.status = "sent";
        adapter.add(m);
        rv.scrollToPosition(adapter.size() - 1);
        etMessage.setText("");
    }

    @Override
    protected void onDestroy() {
        SocketManager.get().removeListener(this);
        super.onDestroy();
    }

    @Override public void onConnected() {}
    @Override public void onDisconnected() {}
    @Override public void onGroupMessage(Message msg) {}
    @Override public void onPrivateMessage(Message msg) {
        String other = msg.username;
        if (username != null && username.equals(msg.username)) other = msg.recipient;
        if (partner.equals(other) || partner.equals(msg.username) || partner.equals(msg.recipient)) {
            adapter.add(msg);
            rv.scrollToPosition(adapter.size() - 1);
            if (!msg.isOwn && (username == null || !username.equals(msg.username))) {
                if (msg.id > 0) SocketManager.get().sendDmRead(partner, msg.id);
            }
        }
    }
    @Override public void onSystem(String text) {}

    @Override
    public void onTyping(String user, String to) {
        if (partner.equals(user)) {
            tvTyping.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onStopTyping(String user, String to) {
        if (partner.equals(user)) {
            tvTyping.setVisibility(View.GONE);
        }
    }

    @Override
    public void onMessagesRead(String reader, String partnerName, long lastReadId) {
        // partner прочитал наши сообщения
        if (partner.equals(reader)) {
            adapter.setTheirLastReadId(lastReadId);
        }
    }

    @Override
    public void onReactionUpdate(long messageId, List<Message.Reaction> reactions) {
        adapter.updateReactions(messageId, reactions);
    }

    private void openStickerPicker() {
        ru.florestdev.messenger.util.StickerPickerHelper.show(this, this::sendStickerUrl);
    }

    private void sendStickerUrl(String url) {
        if (url == null || url.isEmpty()) return;
        // Prefer relative /stickers/... for server normalize
        String send = url;
        if (send.contains("/stickers/")) {
            int i = send.indexOf("/stickers/");
            send = send.substring(i);
        }
        SocketManager.get().sendPrivateMessage(partner, username, send, "sticker");
        Message m = new Message();
        m.isOwn = true;
        m.username = username;
        m.type = "sticker";
        m.content = url;
        m.message = url;
        m.url = url.startsWith("http") ? url : (ApiClient.BASE + (url.startsWith("/") ? url : "/" + url));
        m.status = "sent";
        adapter.add(m);
        rv.scrollToPosition(adapter.size() - 1);
    }

    private void onFilePicked(Uri uri) {
        if (uri == null) return;
        Toast.makeText(this, "Загрузка…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File tmp = copyUriToCache(uri);
                if (tmp == null) {
                    runOnUiThread(() -> Toast.makeText(this, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show());
                    return;
                }
                String mime = getContentResolver().getType(uri);
                if (mime == null) mime = "application/octet-stream";
                final File f = tmp;
                ApiClient.get().uploadMedia(f, mime, partner, null, new ApiClient.Callback<Message>() {
                    @Override public void onSuccess(Message result) {
                        runOnUiThread(() -> {
                            if (result != null) {
                                result.isOwn = true;
                                if (result.username == null) result.username = username;
                                adapter.add(result);
                                rv.scrollToPosition(adapter.size() - 1);
                                Toast.makeText(DmChatActivity.this, "Отправлено", Toast.LENGTH_SHORT).show();
                            }
                        });
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                    @Override public void onError(String error) {
                        runOnUiThread(() -> Toast.makeText(DmChatActivity.this,
                                error != null ? error : "Ошибка загрузки", Toast.LENGTH_LONG).show());
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private File copyUriToCache(Uri uri) {
        try {
            String name = "upload.bin";
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (c.moveToFirst() && idx >= 0) name = c.getString(idx);
                } finally {
                    c.close();
                }
            }
            // sanitize
            name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
            File out = new File(getCacheDir(), System.currentTimeMillis() + "_" + name);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(out)) {
                if (in == null) return null;
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }



}
