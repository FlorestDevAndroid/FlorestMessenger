package ru.florestdev.messenger.ui;

import ru.florestdev.messenger.util.ThemeHelper;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.List;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.model.Organization;
import ru.florestdev.messenger.network.ApiClient;

public class OrgDetailActivity extends AppCompatActivity {
    private int orgId;
    private final List<Organization.Chat> chats = new ArrayList<>();
    private RecyclerView.Adapter<?> adapter;
    private TextView tvInvite;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyToActivity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_org_detail);
        orgId = getIntent().getIntExtra("org_id", 0);
        String name = getIntent().getStringExtra("org_name");
        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setTitle(name != null ? name : "Организация");
        tb.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        tb.setNavigationOnClickListener(v -> finish());
        tvInvite = findViewById(R.id.tvInvite);
        RecyclerView rv = findViewById(R.id.rvChats);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int vt) {
                TextView tv = new TextView(parent.getContext());
                tv.setPadding(24, 32, 24, 32);
                tv.setTextColor(0xFFE8EAED);
                tv.setTextSize(16f);
                return new RecyclerView.ViewHolder(tv) {};
            }
            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
                Organization.Chat c = chats.get(pos);
                ((TextView) h.itemView).setText("# " + c.name);
                h.itemView.setOnClickListener(v -> {
                    Intent i = new Intent(OrgDetailActivity.this, OrgChatActivity.class);
                    i.putExtra("org_id", orgId);
                    i.putExtra("chat_id", c.id);
                    i.putExtra("chat_name", c.name);
                    startActivity(i);
                });
            }
            @Override public int getItemCount() { return chats.size(); }
        };
        rv.setAdapter(adapter);
        findViewById(R.id.btnAddChat).setOnClickListener(v -> {
            EditText et = new EditText(this);
            et.setHint("Название чата");
            new AlertDialog.Builder(this).setTitle("Новый чат").setView(et)
                    .setPositiveButton("Создать", (d, w) -> {
                        String n = et.getText().toString().trim();
                        if (n.isEmpty()) return;
                        ApiClient.get().createOrgChat(orgId, n, new ApiClient.Callback<Organization.Chat>() {
                            @Override public void onSuccess(Organization.Chat result) {
                                runOnUiThread(() -> load());
                            }
                            @Override public void onError(String error) {
                                runOnUiThread(() -> Toast.makeText(OrgDetailActivity.this, error, Toast.LENGTH_SHORT).show());
                            }
                        });
                    }).setNegativeButton(android.R.string.cancel, null).show();
        });
        load();
    }

    private void load() {
        ApiClient.get().fetchOrg(orgId, new ApiClient.Callback<Organization>() {
            @Override public void onSuccess(Organization result) {
                runOnUiThread(() -> {
                    String link = "https://messenger.florestone4185.ru/org/join/" + result.inviteSlug;
                    tvInvite.setText("Invite: " + result.inviteSlug + "\n" + link + "\n(тап — копировать)");
                    tvInvite.setOnClickListener(v -> {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("invite", link));
                        Toast.makeText(OrgDetailActivity.this, "Скопировано", Toast.LENGTH_SHORT).show();
                    });
                    chats.clear();
                    chats.addAll(result.chats);
                    adapter.notifyDataSetChanged();
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(OrgDetailActivity.this, error, Toast.LENGTH_SHORT).show());
            }
        });
    }
}
