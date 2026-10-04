package ru.florestdev.messenger.ui;

import ru.florestdev.messenger.util.ThemeHelper;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.HashSet;
import java.util.List;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.adapter.GroupAdapter;
import ru.florestdev.messenger.model.Group;
import ru.florestdev.messenger.network.ApiClient;
import ru.florestdev.messenger.service.MessengerSocketService;
import ru.florestdev.messenger.util.Prefs;

public class GroupsActivity extends AppCompatActivity implements GroupAdapter.OnAction {
    private GroupAdapter adapter;
    private SwipeRefreshLayout swipe;
    private EditText etSearch;
    private TextView tvSearchHint;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private String currentQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyToActivity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_groups);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());
        tb.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);

        swipe = findViewById(R.id.swipeRefresh);
        etSearch = findViewById(R.id.etSearch);
        tvSearchHint = findViewById(R.id.tvSearchHint);
        RecyclerView rv = findViewById(R.id.rvGroups);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new GroupAdapter(this);
        rv.setAdapter(adapter);

        swipe.setOnRefreshListener(this::load);
        FloatingActionButton fab = findViewById(R.id.fabCreate);
        fab.setOnClickListener(v -> showCreateDialog());

        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                currentQuery = etSearch.getText() != null ? etSearch.getText().toString().trim() : "";
                load();
                return true;
            }
            return false;
        });
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
                pendingSearch = () -> {
                    currentQuery = s != null ? s.toString().trim() : "";
                    load();
                };
                handler.postDelayed(pendingSearch, 450);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        load();
    }

    private void load() {
        swipe.setRefreshing(true);
        boolean searching = currentQuery != null && !currentQuery.isEmpty();
        tvSearchHint.setText(searching
                ? ("Поиск по серверу: «" + currentQuery + "»")
                : "По умолчанию — ваши группы. Введите запрос для поиска по всему серверу.");

        ApiClient.get().fetchGroups(searching ? currentQuery : null, new ApiClient.Callback<List<Group>>() {
            @Override
            public void onSuccess(List<Group> result) {
                runOnUiThread(() -> {
                    swipe.setRefreshing(false);
                    adapter.setItems(result);
                    Prefs prefs = new Prefs(GroupsActivity.this);
                    HashSet<Integer> ids = new HashSet<>();
                    if (result != null) {
                        for (Group g : result) {
                            if (g.isMember && g.id > 0) {
                                ids.add(g.id);
                                prefs.setGroupName(g.id, g.name);
                            }
                        }
                    }
                    // при поиске не затирать полный список member — только дополнять
                    if (!searching) {
                        prefs.setMemberGroupIds(ids);
                    } else {
                        for (Integer id : ids) prefs.addMemberGroup(id);
                    }
                    MessengerSocketService.refreshGroups(GroupsActivity.this);
                    if (searching && (result == null || result.isEmpty())) {
                        Toast.makeText(GroupsActivity.this, "Ничего не найдено", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    swipe.setRefreshing(false);
                    Toast.makeText(GroupsActivity.this, error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showCreateDialog() {
        EditText etName = new EditText(this);
        etName.setHint(R.string.group_name);
        EditText etDesc = new EditText(this);
        etDesc.setHint(R.string.group_desc);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        box.addView(etName);
        box.addView(etDesc);
        new AlertDialog.Builder(this)
                .setTitle(R.string.create_group)
                .setView(box)
                .setPositiveButton(R.string.create, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) return;
                    ApiClient.get().createGroup(name, etDesc.getText().toString().trim(),
                            new ApiClient.Callback<Boolean>() {
                                @Override
                                public void onSuccess(Boolean result) {
                                    runOnUiThread(() -> {
                                        Toast.makeText(GroupsActivity.this, "Создано", Toast.LENGTH_SHORT).show();
                                        currentQuery = "";
                                        etSearch.setText("");
                                        load();
                                    });
                                }

                                @Override
                                public void onError(String error) {
                                    runOnUiThread(() -> Toast.makeText(GroupsActivity.this, error, Toast.LENGTH_SHORT).show());
                                }
                            });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onOpen(Group g) {
        Intent i = new Intent(this, GroupChatActivity.class);
        i.putExtra("group_id", g.id);
        i.putExtra("group_name", g.name);
        startActivity(i);
    }

    @Override
    public void onJoin(Group g) {
        ApiClient.get().joinGroup(g.id, new ApiClient.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                runOnUiThread(() -> {
                    Toast.makeText(GroupsActivity.this, "Вы в группе", Toast.LENGTH_SHORT).show();
                    g.isMember = true;
                    Prefs prefs = new Prefs(GroupsActivity.this);
                    prefs.addMemberGroup(g.id);
                    prefs.setGroupName(g.id, g.name);
                    MessengerSocketService.refreshGroups(GroupsActivity.this);
                    load();
                    onOpen(g);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(GroupsActivity.this, error, Toast.LENGTH_SHORT).show());
            }
        });
    }
}
