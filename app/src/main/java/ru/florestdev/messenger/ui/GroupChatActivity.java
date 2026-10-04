package ru.florestdev.messenger.ui;

import ru.florestdev.messenger.util.ThemeHelper;

import android.os.Bundle;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.adapter.MessageAdapter;
import ru.florestdev.messenger.model.Message;
import ru.florestdev.messenger.network.ApiClient;
import ru.florestdev.messenger.network.SocketManager;
import ru.florestdev.messenger.service.MessengerSocketService;
import ru.florestdev.messenger.util.Prefs;

public class GroupChatActivity extends AppCompatActivity implements SocketManager.Listener {
    private int groupId;
    private String groupName;
    private String username;
    private MessageAdapter adapter;
    private RecyclerView rv;
    private EditText etMessage;
    private View callPanel;
    private LinearLayout callAvatars;
    private final ActivityResultLauncher<String> pickFile =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onFilePicked);
    private boolean inCall;
    private final Set<String> callUsers = new LinkedHashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyToActivity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_chat);

        groupId = getIntent().getIntExtra("group_id", 0);
        groupName = getIntent().getStringExtra("group_name");
        if (groupName == null) groupName = "Группа";
        username = new Prefs(this).getUsername();

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setTitle(groupName);
        tb.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        tb.setNavigationOnClickListener(v -> finish());

        callPanel = findViewById(R.id.callPanel);
        callAvatars = findViewById(R.id.callAvatars);
        findViewById(R.id.btnHangup).setOnClickListener(v -> leaveCall());

        rv = findViewById(R.id.rvMessages);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MessageAdapter(username);
        adapter.setLongClickListener(this::showReactDialog);
        rv.setAdapter(adapter);

        etMessage = findViewById(R.id.etMessage);
        ImageButton btnSend = findViewById(R.id.btnSend);
        btnSend.setOnClickListener(v -> send());
        View btnAttach = findViewById(R.id.btnAttach);
        if (btnAttach != null) btnAttach.setOnClickListener(v -> pickFile.launch("*/*"));
        findViewById(R.id.btnCall).setOnClickListener(v -> {
            if (inCall) leaveCall();
            else joinCall();
        });

        View btnSt = findViewById(R.id.btnStickers);
        if (btnSt != null) btnSt.setOnClickListener(v -> openStickerPicker());
        android.view.View _tb = findViewById(R.id.toolbar);
        if (_tb != null) _tb.setOnLongClickListener(v -> { createPollDialog(); return true; });
        SocketManager.get().connect();
        SocketManager.get().addListener(this);
        Prefs p = new Prefs(this);
        p.addMemberGroup(groupId);
        p.setGroupName(groupId, groupName);
        p.setActiveChat(Prefs.groupKey(groupId));
        MessengerSocketService.refreshGroups(this);
        if (username != null) SocketManager.get().joinGroup(groupId, username);
    }

    private void joinCall() {
        inCall = true;
        callUsers.clear();
        if (username != null) callUsers.add(username);
        callPanel.setVisibility(View.VISIBLE);
        renderCallAvatars();
        SocketManager.get().voiceJoin(groupId);
        Toast.makeText(this, "Вы в звонке", Toast.LENGTH_SHORT).show();
    }

    private void leaveCall() {
        inCall = false;
        callUsers.clear();
        callPanel.setVisibility(View.GONE);
        callAvatars.removeAllViews();
        SocketManager.get().voiceLeave();
    }

    private void renderCallAvatars() {
        callAvatars.removeAllViews();
        List<String> list = new ArrayList<>(callUsers);
        int show = Math.min(3, list.size());
        int size = (int) (40 * getResources().getDisplayMetrics().density);
        int margin = (int) (6 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < show; i++) {
            ImageView iv = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(0, 0, margin, 0);
            iv.setLayoutParams(lp);
            Glide.with(this)
                    .load(ApiClient.get().avatarUrl(list.get(i)))
                    .transform(new CircleCrop())
                    .into(iv);
            callAvatars.addView(iv);
        }
        if (list.size() > 3) {
            TextView plus = new TextView(this);
            plus.setText("+" + (list.size() - 3));
            plus.setTextColor(0xFFE8EAED);
            plus.setTextSize(16f);
            plus.setPadding(margin, margin, margin, margin);
            callAvatars.addView(plus);
        }
    }

    private void showReactDialog(Message m) {
        if (m.id <= 0) return;
        final String[] E = {"👍", "❤️", "😂", "🔥", "😮", "😢", "👏"};
        new AlertDialog.Builder(this)
                .setTitle("Реакция")
                .setItems(E, (d, which) -> SocketManager.get().sendReact(m.id, E[which]))
                .setNegativeButton("Убрать", (d, w) -> SocketManager.get().sendReact(m.id, ""))
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        new Prefs(this).setActiveChat(Prefs.groupKey(groupId));
    }

    @Override
    protected void onPause() {
        new Prefs(this).setActiveChat("");
        super.onPause();
    }

    private void send() {
        String text = etMessage.getText() != null ? etMessage.getText().toString().trim() : "";
        if (text.isEmpty() || username == null) return;
        SocketManager.get().sendGroupMessage(groupId, username, text, null);
        Message m = new Message();
        m.username = username;
        m.content = text;
        m.message = text;
        m.isOwn = true;
        m.groupId = groupId;
        adapter.add(m);
        rv.scrollToPosition(adapter.size() - 1);
        etMessage.setText("");
    }

    @Override
    protected void onDestroy() {
        if (inCall) leaveCall();
        SocketManager.get().removeListener(this);
        super.onDestroy();
    }

    @Override public void onConnected() {
        if (username != null) SocketManager.get().joinGroup(groupId, username);
        if (inCall) SocketManager.get().voiceJoin(groupId);
    }
    @Override public void onDisconnected() {}
    @Override public void onGroupMessage(Message msg) {
        if (msg.groupId != null && msg.groupId == groupId) {
            adapter.add(msg);
            rv.scrollToPosition(adapter.size() - 1);
        }
    }
    @Override public void onPrivateMessage(Message msg) {}
    @Override public void onSystem(String text) {
        if (text != null && !text.isEmpty()) {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        }
    }
    @Override
    public void onReactionUpdate(long messageId, List<Message.Reaction> reactions) {
        adapter.updateReactions(messageId, reactions);
    }

    @Override
    public void onVoicePeers(Integer gid, List<SocketManager.VoicePeer> peers) {
        if (gid == null || gid != groupId || !inCall) return;
        callUsers.clear();
        if (username != null) callUsers.add(username);
        if (peers != null) {
            for (SocketManager.VoicePeer p : peers) {
                if (p != null && p.username != null) callUsers.add(p.username);
            }
        }
        callPanel.setVisibility(View.VISIBLE);
        renderCallAvatars();
    }

    @Override
    public void onVoiceUserJoined(Integer gid, String user, String sid) {
        if (gid == null || gid != groupId || !inCall) return;
        if (user != null) callUsers.add(user);
        renderCallAvatars();
    }

    @Override
    public void onVoiceUserLeft(Integer gid, String user, String sid) {
        if (gid == null || gid != groupId) return;
        if (user != null) callUsers.remove(user);
        if (inCall) renderCallAvatars();
    }

    private void openStickerPicker() {
        ru.florestdev.messenger.util.StickerPickerHelper.show(this, this::sendStickerUrl);
    }

    private void sendStickerUrl(String url) {
        if (url == null || url.isEmpty()) return;
        String send = url;
        if (send.contains("/stickers/")) {
            int i = send.indexOf("/stickers/");
            send = send.substring(i);
        }
        SocketManager.get().sendGroupMessage(groupId, username, send, null, "sticker");
        Message m = new Message();
        m.isOwn = true;
        m.username = username;
        m.type = "sticker";
        m.content = url;
        m.message = url;
        m.url = url.startsWith("http") ? url : (ApiClient.BASE + (url.startsWith("/") ? url : "/" + url));
        m.groupId = groupId;
        adapter.add(m);
        rv.scrollToPosition(adapter.size() - 1);
    }

    private void createPollDialog() {
        final android.widget.EditText q = new android.widget.EditText(this);
        q.setHint("Вопрос");
        final android.widget.EditText opts = new android.widget.EditText(this);
        opts.setHint("Варианты (каждый с новой строки)");
        opts.setMinLines(3);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 8);
        box.addView(q);
        box.addView(opts);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Опрос / викторина")
                .setView(box)
                .setPositiveButton("Создать опрос", (d, w) -> {
                    String question = q.getText().toString().trim();
                    String[] lines = opts.getText().toString().split("\n");
                    java.util.List<String> options = new java.util.ArrayList<>();
                    for (String line : lines) {
                        if (line.trim().length() > 0) options.add(line.trim());
                    }
                    if (question.isEmpty() || options.size() < 2) {
                        android.widget.Toast.makeText(this, "Нужны вопрос и 2+ варианта", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int gid = getIntent().getIntExtra("group_id", 0);
                    ApiClient.get().createPoll("group", gid, null, question, options, "poll", null,
                            new ApiClient.Callback<String>() {
                                @Override public void onSuccess(String result) {
                                    runOnUiThread(() -> android.widget.Toast.makeText(GroupChatActivity.this, "Опрос создан", android.widget.Toast.LENGTH_SHORT).show());
                                }
                                @Override public void onError(String error) {
                                    runOnUiThread(() -> android.widget.Toast.makeText(GroupChatActivity.this, error, android.widget.Toast.LENGTH_SHORT).show());
                                }
                            });
                })
                .setNeutralButton("Викторина", (d, w) -> {
                    String question = q.getText().toString().trim();
                    String[] lines = opts.getText().toString().split("\n");
                    java.util.List<String> options = new java.util.ArrayList<>();
                    for (String line : lines) {
                        if (line.trim().length() > 0) options.add(line.trim());
                    }
                    if (question.isEmpty() || options.size() < 2) {
                        android.widget.Toast.makeText(this, "Нужны вопрос и 2+ варианта", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int gid = getIntent().getIntExtra("group_id", 0);
                    ApiClient.get().createPoll("group", gid, null, question, options, "quiz", 0,
                            new ApiClient.Callback<String>() {
                                @Override public void onSuccess(String result) {
                                    runOnUiThread(() -> android.widget.Toast.makeText(GroupChatActivity.this, "Викторина (верный=1й)", android.widget.Toast.LENGTH_SHORT).show());
                                }
                                @Override public void onError(String error) {
                                    runOnUiThread(() -> android.widget.Toast.makeText(GroupChatActivity.this, error, android.widget.Toast.LENGTH_SHORT).show());
                                }
                            });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }



    private void onFilePicked(Uri uri) {
        if (uri == null) return;
        Toast.makeText(this, "Загрузка…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
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
                name = name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
                File out = new File(getCacheDir(), System.currentTimeMillis() + "_" + name);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    if (in == null) {
                        runOnUiThread(() -> Toast.makeText(this, "Не удалось открыть файл", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
                String mime = getContentResolver().getType(uri);
                if (mime == null) mime = "application/octet-stream";
                final File tmp = out;
                ApiClient.get().uploadMedia(tmp, mime, null, groupId, new ApiClient.Callback<Message>() {
                    @Override public void onSuccess(Message result) {
                        runOnUiThread(() -> {
                            if (result != null) {
                                result.isOwn = true;
                                if (result.username == null) result.username = username;
                                result.groupId = groupId;
                                adapter.add(result);
                                rv.scrollToPosition(adapter.size() - 1);
                                Toast.makeText(GroupChatActivity.this, "Отправлено", Toast.LENGTH_SHORT).show();
                            }
                        });
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    }
                    @Override public void onError(String error) {
                        runOnUiThread(() -> Toast.makeText(GroupChatActivity.this,
                                error != null ? error : "Ошибка загрузки", Toast.LENGTH_LONG).show());
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

}
