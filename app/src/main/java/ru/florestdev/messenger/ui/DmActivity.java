package ru.florestdev.messenger.ui;

import ru.florestdev.messenger.util.ThemeHelper;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.List;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.adapter.ContactAdapter;
import ru.florestdev.messenger.model.Contact;
import ru.florestdev.messenger.network.ApiClient;

/**
 * Список — только существующие контакты.
 * Поиск — отдельное поле: ввёл ник → нашёл → открыл чат (появится в контактах после переписки на сервере).
 */
public class DmActivity extends AppCompatActivity {
    private ContactAdapter contactsAdapter;
    private ContactAdapter searchAdapter;
    private RecyclerView rvSearch;
    private TextView tvSearchSection;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyToActivity(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dm);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        tb.setNavigationOnClickListener(v -> finish());

        RecyclerView rvContacts = findViewById(R.id.rvContacts);
        rvContacts.setLayoutManager(new LinearLayoutManager(this));
        contactsAdapter = new ContactAdapter(this::openChat);
        rvContacts.setAdapter(contactsAdapter);

        rvSearch = findViewById(R.id.rvSearch);
        tvSearchSection = findViewById(R.id.tvSearchSection);
        rvSearch.setLayoutManager(new LinearLayoutManager(this));
        searchAdapter = new ContactAdapter(this::openChat);
        rvSearch.setAdapter(searchAdapter);

        EditText etSearch = findViewById(R.id.etSearch);
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                String q = etSearch.getText() != null ? etSearch.getText().toString().trim() : "";
                if (!q.isEmpty()) {
                    // Enter: сразу открыть чат с введённым ником
                    openChat(new Contact(q, false, false));
                }
                return true;
            }
            return false;
        });
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
                String q = s != null ? s.toString().trim() : "";
                if (q.isEmpty()) {
                    hideSearch();
                    return;
                }
                pendingSearch = () -> doUserSearch(q);
                handler.postDelayed(pendingSearch, 350);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        loadContacts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadContacts();
    }

    private void openChat(Contact c) {
        if (c == null || c.username == null || c.username.trim().isEmpty()) return;
        Intent i = new Intent(this, DmChatActivity.class);
        i.putExtra("partner", c.username.trim());
        startActivity(i);
    }

    private void hideSearch() {
        rvSearch.setVisibility(View.GONE);
        tvSearchSection.setVisibility(View.GONE);
        searchAdapter.setItems(null);
    }

    private void doUserSearch(String q) {
        ApiClient.get().searchUsers(q, new ApiClient.Callback<List<Contact>>() {
            @Override
            public void onSuccess(List<Contact> result) {
                runOnUiThread(() -> {
                    if (result == null || result.isEmpty()) {
                        hideSearch();
                        return;
                    }
                    tvSearchSection.setVisibility(View.VISIBLE);
                    rvSearch.setVisibility(View.VISIBLE);
                    searchAdapter.setItems(result);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(this::hideSearchSilent);
            }

            private void hideSearchSilent() {
                hideSearch();
            }
        });
    }

    private void loadContacts() {
        ApiClient.get().fetchContacts(new ApiClient.Callback<List<Contact>>() {
            @Override
            public void onSuccess(List<Contact> result) {
                runOnUiThread(() -> contactsAdapter.setItems(result));
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(DmActivity.this, error, Toast.LENGTH_SHORT).show());
            }
        });
    }
}
