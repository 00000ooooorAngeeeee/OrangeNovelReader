package com.orange.novel.reader.ui;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.orange.novel.reader.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 全文搜索结果列表 */
public class SearchAdapter extends RecyclerView.Adapter<SearchAdapter.VH> {

    public static class Hit {
        public final int chapter;
        public final int offset;
        public final String title;
        public final String snippet;

        public Hit(int chapter, int offset, String title, String snippet) {
            this.chapter = chapter;
            this.offset = offset;
            this.title = title;
            this.snippet = snippet;
        }
    }

    public interface Listener {
        void onHit(Hit hit);
    }

    private final List<Hit> items = new ArrayList<>();
    private final Listener listener;
    private String keyword = "";

    public SearchAdapter(Listener l) {
        this.listener = l;
    }

    public void submit(List<Hit> list, String keyword) {
        items.clear();
        items.addAll(list);
        this.keyword = keyword == null ? "" : keyword;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_search, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Hit it = items.get(position);
        h.title.setText(it.title);
        h.snippet.setText(highlight(it.snippet, keyword));
        h.itemView.setOnClickListener(v -> listener.onHit(it));
    }

    private CharSequence highlight(String text, String kw) {
        if (text == null) return "";
        if (kw.isEmpty()) return text;
        SpannableString sp = new SpannableString(text);
        String lower = text.toLowerCase(Locale.ROOT);
        String k = kw.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int i = lower.indexOf(k, from);
            if (i < 0) break;
            sp.setSpan(new ForegroundColorSpan(0xFFFF8A5C), i, i + k.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = i + k.length();
        }
        return sp;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView snippet;

        VH(View v) {
            super(v);
            title = v.findViewById(R.id.title);
            snippet = v.findViewById(R.id.snippet);
        }
    }
}
