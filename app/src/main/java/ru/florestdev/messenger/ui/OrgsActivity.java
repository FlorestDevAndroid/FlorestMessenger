package ru.florestdev.messenger.ui;

import ru.florestdev.messenger.util.ThemeHelper;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
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

public class OrgsActivity extends AppCompatActivity {
    private final List<Organization> items = new ArrayList<>();
    private RecyclerView.Adapter<?> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyToActivity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_orgs);
        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        tb.setNavigationOnClickListener(v -> finish());

        RecyclerView rv = findViewById(R.id.rvOrgs);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
                TextView tv = new TextView(parent.getContext());
                tv.setPadding(24, 28, 24, 28);
                tv.setTextColor(0xFFE8EAED);
                tv.setTextSize(16f);
                tv.setBackgroundColor(0xFF1A1D27);
                return new RecyclerView.ViewHolder(tv) {};
            }
            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
                Organization o = items.get(pos);
                ((TextView) h.itemView).setText(o.name + (o.role != null ? " · " + o.role : ""));
                h.itemView.setOnClickListener(v -> {
                    Intent i = new Intent(OrgsActivity.this, OrgDetailActivity.class);
                    i.putExtra("org_id", o.id);
                    i.putExtra("org_name", o.name);
                    startActivity(i);
                });
            }
            @Override public int getItemCount() { return items.size(); }
        };
        rv.setAdapter(adapter);

        findViewById(R.id.btnCreate).setOnClickListener(v -> showCreate());
        findViewById(R.id.btnJoin).setOnClickListener(v -> showJoin());
        load();
    }

    @Override protected void onResume() { super.onResume(); load(); }

    private void load() {
        ApiClient.get().fetchOrgs(new ApiClient.Callback<List<Organization>>() {
            @Override public void onSuccess(List<Organization> result) {
                runOnUiThread(() -> {
                    items.clear();
                    if (result != null) items.addAll(result);
                    adapter.notifyDataSetChanged();
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(OrgsActivity.this, error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showCreate() {
        EditText etName = new EditText(this); etName.setHint("Название");
        EditText etSlug = new EditText(this); etSlug.setHint("Ссылка (необязательно, латиница)");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p, p, p);
        box.addView(etName); box.addView(etSlug);
        new AlertDialog.Builder(this).setTitle("Новая организация")
                .setView(box)
                .setPositiveButton("Создать", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) return;
                    ApiClient.get().createOrg(name, etSlug.getText().toString().trim(), "",
                            new ApiClient.Callback<Organization>() {
                                @Override public void onSuccess(Organization result) {
                                    runOnUiThread(() -> {
                                        Toast.makeText(OrgsActivity.this, "Создано. Invite: " + result.inviteSlug, Toast.LENGTH_LONG).show();
                                        load();
                                    });
                                }
                                @Override public void onError(String error) {
                                    runOnUiThread(() -> Toast.makeText(OrgsActivity.this, error, Toast.LENGTH_SHORT).show());
                                }
                            });
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showJoin() {
        EditText et = new EditText(this);
        et.setHint("invite slug или код из ссылки");
        new AlertDialog.Builder(this).setTitle("Вступить по ссылке")
                .setView(et)
                .setPositiveButton("Войти", (d, w) -> {
                    String slug = et.getText().toString().trim();
                    if (slug.contains("/")) slug = slug.substring(slug.lastIndexOf('/') + 1);
                    ApiClient.get().joinOrg(slug, new ApiClient.Callback<Organization>() {
                        @Override public void onSuccess(Organization result) {
                            runOnUiThread(() -> {
                                Toast.makeText(OrgsActivity.this, "Вы в «" + result.name + "»", Toast.LENGTH_SHORT).show();
                                load();
                            });
                        }
                        @Override public void onError(String error) {
                            runOnUiThread(() -> Toast.makeText(OrgsActivity.this, error, Toast.LENGTH_SHORT).show());
                        }
                    });
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
}
