package ru.florestdev.messenger.util;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ru.florestdev.messenger.R;
import ru.florestdev.messenger.network.ApiClient;

/** Bottom dialog: pack tabs + image grid. Tap sticker → callback. */
public final class StickerPickerHelper {

    public interface OnSticker {
        void onPick(String url);
    }

    private StickerPickerHelper() {}

    public static void show(Activity activity, OnSticker onSticker) {
        ApiClient.get().fetchStickerPacks(new ApiClient.Callback<List<Map<String, Object>>>() {
            @Override
            public void onSuccess(List<Map<String, Object>> packs) {
                activity.runOnUiThread(() -> {
                    if (packs == null || packs.isEmpty()) {
                        Toast.makeText(activity, "Нет стикер-паков. Добавь на /stickers", Toast.LENGTH_LONG).show();
                        return;
                    }
                    showWithPacks(activity, packs, onSticker);
                });
            }

            @Override
            public void onError(String error) {
                activity.runOnUiThread(() ->
                        Toast.makeText(activity, error != null ? error : "Ошибка стикеров", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private static void showWithPacks(Activity activity, List<Map<String, Object>> packs, OnSticker onSticker) {
        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_sticker_picker, null, false);
        TextView title = root.findViewById(R.id.tvPackTitle);
        LinearLayout tabs = root.findViewById(R.id.packTabs);
        RecyclerView rv = root.findViewById(R.id.rvStickers);
        rv.setLayoutManager(new GridLayoutManager(activity, 3));
        StickerAdapter adapter = new StickerAdapter(url -> {
            if (onSticker != null) onSticker.onPick(url);
        });
        rv.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setNegativeButton("Закрыть", null)
                .create();

        adapter.setOnPick(url -> {
            if (onSticker != null) onSticker.onPick(url);
            dialog.dismiss();
        });

        final int[] selected = {0};
        Runnable loadPack = () -> {
            Map<String, Object> pack = packs.get(selected[0]);
            Object idObj = pack.get("id");
            int packId = idObj instanceof Number ? ((Number) idObj).intValue() : 0;
            String name = String.valueOf(pack.get("name") != null ? pack.get("name") : "Пак");
            title.setText(name);
            for (int i = 0; i < tabs.getChildCount(); i++) {
                View child = tabs.getChildAt(i);
                child.setAlpha(i == selected[0] ? 1f : 0.5f);
            }
            ApiClient.get().fetchPackStickers(packId, new ApiClient.Callback<List<String>>() {
                @Override
                public void onSuccess(List<String> urls) {
                    activity.runOnUiThread(() -> adapter.setItems(urls != null ? urls : new ArrayList<>()));
                }

                @Override
                public void onError(String error) {
                    activity.runOnUiThread(() ->
                            Toast.makeText(activity, error != null ? error : "Ошибка", Toast.LENGTH_SHORT).show());
                }
            });
        };

        for (int i = 0; i < packs.size(); i++) {
            Map<String, Object> pack = packs.get(i);
            String name = String.valueOf(pack.get("name") != null ? pack.get("name") : ("#" + (i + 1)));
            MaterialButton b = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setText(name);
            b.setTextSize(12f);
            b.setAllCaps(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(8);
            b.setLayoutParams(lp);
            final int idx = i;
            b.setOnClickListener(v -> {
                selected[0] = idx;
                loadPack.run();
            });
            tabs.addView(b);
        }

        dialog.show();
        loadPack.run();
    }

    private static class StickerAdapter extends RecyclerView.Adapter<StickerAdapter.VH> {
        private final List<String> items = new ArrayList<>();
        private OnSticker onPick;

        StickerAdapter(OnSticker onPick) {
            this.onPick = onPick;
        }

        void setOnPick(OnSticker onPick) {
            this.onPick = onPick;
        }

        void setItems(List<String> urls) {
            items.clear();
            if (urls != null) items.addAll(urls);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_sticker_cell, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            String url = items.get(position);
            Glide.with(h.iv.getContext())
                    .load(url)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .error(android.R.drawable.ic_menu_report_image)
                    .into(h.iv);
            h.itemView.setOnClickListener(v -> {
                if (onPick != null) onPick.onPick(url);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final ImageView iv;

            VH(View v) {
                super(v);
                iv = v.findViewById(R.id.ivSticker);
            }
        }
    }
}
