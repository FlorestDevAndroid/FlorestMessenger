package ru.florestdev.messenger.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ru.florestdev.messenger.R;

public class MenuAdapter extends RecyclerView.Adapter<MenuAdapter.VH> {
    public static class Item {
        public final String icon;
        public final String title;
        public final String subtitle;
        public final Runnable action;
        public Item(String icon, String title, String subtitle, Runnable action) {
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.action = action;
        }
    }

    private final List<Item> items;

    public MenuAdapter(List<Item> items) {
        this.items = items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_menu_card, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Item it = items.get(position);
        h.tvIcon.setText(it.icon);
        h.tvTitle.setText(it.title);
        h.tvSubtitle.setText(it.subtitle);
        h.itemView.setOnClickListener(v -> {
            if (it.action != null) it.action.run();
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvIcon, tvTitle, tvSubtitle;
        VH(View v) {
            super(v);
            tvIcon = v.findViewById(R.id.tvIcon);
            tvTitle = v.findViewById(R.id.tvTitle);
            tvSubtitle = v.findViewById(R.id.tvSubtitle);
        }
    }
}
