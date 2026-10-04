package ru.florestdev.messenger.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.List;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.model.Contact;
import ru.florestdev.messenger.model.Group;
import ru.florestdev.messenger.network.ApiClient;
import ru.florestdev.messenger.service.MessengerSocketService;
import ru.florestdev.messenger.util.Prefs;

public class SearchActivity extends AppCompatActivity {
    private EditText etSearch;
    private ProgressBar progress;
    private ResultsAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        tb.setNavigationOnClickListener(v -> finish());

        etSearch = findViewById(R.id.etSearch);
        progress = findViewById(R.id.progress);
        RecyclerView rv = findViewById(R.id.rvResults);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ResultsAdapter();
        rv.setAdapter(adapter);

        etSearch.requestFocus();
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(etSearch.getText() != null ? etSearch.getText().toString() : "");
                return true;
            }
            return false;
        });
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (pending != null) handler.removeCallbacks(pending);
                pending = () -> runSearch(s != null ? s.toString() : "");
                handler.postDelayed(pending, 400);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void runSearch(String q) {
        if (q == null || q.trim().isEmpty()) {
            adapter.setItems(new ArrayList<>());
            return;
        }
        progress.setVisibility(View.VISIBLE);
        ApiClient.get().globalSearch(q.trim(), new ApiClient.Callback<ApiClient.SearchResult>() {
            @Override
            public void onSuccess(ApiClient.SearchResult result) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    List<Row> rows = new ArrayList<>();
                    if (result.users != null) {
                        for (Contact c : result.users) {
                            Row r = new Row();
                            r.type = "user";
                            r.title = c.username;
                            r.subtitle = c.bot ? "бот" : (c.online ? "онлайн" : "пользователь");
                            r.contact = c;
                            rows.add(r);
                        }
                    }
                    if (result.groups != null) {
                        for (Group g : result.groups) {
                            Row r = new Row();
                            r.type = "group";
                            r.title = g.name;
                            r.subtitle = "группа · " + (g.creator != null ? g.creator : "")
                                    + (g.isMember ? " · вы внутри" : "");
                            r.group = g;
                            rows.add(r);
                        }
                    }
                    adapter.setItems(rows);
                    if (rows.isEmpty()) {
                        Toast.makeText(SearchActivity.this, "Ничего не найдено", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(SearchActivity.this, error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    static class Row {
        String type; // user | group
        String title;
        String subtitle;
        Contact contact;
        Group group;
    }

    class ResultsAdapter extends RecyclerView.Adapter<ResultsAdapter.VH> {
        private final List<Row> items = new ArrayList<>();

        void setItems(List<Row> list) {
            items.clear();
            if (list != null) items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_search_result, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Row r = items.get(position);
            h.tvTitle.setText(r.title);
            h.tvSubtitle.setText(r.subtitle);
            h.tvBadge.setText("user".equals(r.type) ? "ЛС" : "Группа");
            String ava;
            if ("user".equals(r.type)) {
                ava = ApiClient.get().avatarUrl(r.title);
            } else {
                ava = r.group != null ? ApiClient.get().groupAvatarUrl(r.group.id) : null;
            }
            Glide.with(h.ivAvatar).load(ava).transform(new CircleCrop())
                    .placeholder(android.R.drawable.sym_def_app_icon).into(h.ivAvatar);

            h.itemView.setOnClickListener(v -> {
                if ("user".equals(r.type)) {
                    Intent i = new Intent(SearchActivity.this, DmChatActivity.class);
                    i.putExtra("partner", r.title);
                    startActivity(i);
                } else if (r.group != null) {
                    if (r.group.isMember) {
                        Intent i = new Intent(SearchActivity.this, GroupChatActivity.class);
                        i.putExtra("group_id", r.group.id);
                        i.putExtra("group_name", r.group.name);
                        startActivity(i);
                    } else {
                        ApiClient.get().joinGroup(r.group.id, new ApiClient.Callback<Boolean>() {
                            @Override
                            public void onSuccess(Boolean result) {
                                runOnUiThread(() -> {
                                    Prefs p = new Prefs(SearchActivity.this);
                                    p.addMemberGroup(r.group.id);
                                    p.setGroupName(r.group.id, r.group.name);
                                    MessengerSocketService.refreshGroups(SearchActivity.this);
                                    Intent i = new Intent(SearchActivity.this, GroupChatActivity.class);
                                    i.putExtra("group_id", r.group.id);
                                    i.putExtra("group_name", r.group.name);
                                    startActivity(i);
                                });
                            }

                            @Override
                            public void onError(String error) {
                                runOnUiThread(() -> Toast.makeText(SearchActivity.this, error, Toast.LENGTH_SHORT).show());
                            }
                        });
                    }
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivAvatar;
            TextView tvTitle, tvSubtitle, tvBadge;
            VH(View v) {
                super(v);
                ivAvatar = v.findViewById(R.id.ivAvatar);
                tvTitle = v.findViewById(R.id.tvTitle);
                tvSubtitle = v.findViewById(R.id.tvSubtitle);
                tvBadge = v.findViewById(R.id.tvBadge);
            }
        }
    }
}
