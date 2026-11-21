package com.orange.novel.reader.ui;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.orange.novel.reader.R;

import java.util.ArrayList;
import java.util.List;

public class ChapterAdapter extends RecyclerView.Adapter<ChapterAdapter.VH> {

    public static class Entry {
        public final String title;
        public final int chapter;
        public final int level;
        public final String sub;
        public String right;

        public Entry(String title, int chapter, int level) {
            this(title, chapter, level, null);
        }

        public Entry(String title, int chapter, int level, String sub) {
            this.title = title;
            this.chapter = chapter;
            this.level = level;
            this.sub = sub;
        }
    }

    public interface Listener {
        void onClick(Entry e);
    }

    private final List<Entry> items = new ArrayList<>();
    private final Listener listener;
    private int current = -1;
    private boolean compact;
    /** 当前章的已读百分比，-1 表示不显示 */
    private int readPct = -1;

    /** 精简模式：隐藏字数副标题，列表更紧凑 */
    public void setCompact(boolean compact) {
        if (this.compact == compact) return;
        this.compact = compact;
        notifyDataSetChanged();
    }

    public ChapterAdapter(Listener l) {
        this.listener = l;
    }

    public void setItems(List<Entry> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    public void setCurrent(int chapter, int pct) {
        this.readPct = pct;
        setCurrent(chapter);
    }

    public void setCurrent(int chapter) {
        if (chapter < 0 || current == chapter) return;
        int old = current;
        current = chapter;
        int oi = indexOfChapter(old);
        if (oi >= 0) notifyItemChanged(oi);
        int ni = indexOfChapter(current);
        if (ni >= 0) notifyItemChanged(ni);
    }

    public int indexOfChapter(int chapter) {
        if (chapter < 0) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).chapter == chapter) return i;
        }
        return -1;
    }

    public String labelAt(int position) {
        if (position < 0 || position >= items.size()) return "";
        Entry e = items.get(position);
        return e.title == null ? "" : e.title;
    }

    public int chapterAt(int position) {
        if (position < 0 || position >= items.size()) return -1;
        return items.get(position).chapter;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chapter, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Entry e = items.get(position);
        h.tv.setText(e.title);
        // 分卷已用独立行展示，正文条目不再按层级缩进，避免和左侧圆点拉开距离
        h.tv.setPadding(0, h.tv.getPaddingTop(), h.tv.getPaddingRight(), h.tv.getPaddingBottom());

        if (e.chapter < 0) {
            // 分卷标题：不可点，样式区分开
            h.tv.setTextColor(0xFF8FB8FF);
            h.tv.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            h.tv.setOnClickListener(null);
            if (h.sub != null) h.sub.setVisibility(View.GONE);
            if (h.dot != null) h.dot.setVisibility(View.INVISIBLE);
            return;
        }
        if (h.sub != null) {
            if (e.sub == null || e.sub.isEmpty() || compact) {
                h.sub.setVisibility(View.GONE);
            } else {
                h.sub.setVisibility(View.VISIBLE);
                h.sub.setText(e.sub);
            }
        }
        if (h.read != null) {
            // 只给「当前章」显示读到百分之几，避免章节切换后标签留在旧行
            if (e.chapter == current && readPct >= 0) {
                h.read.setVisibility(View.VISIBLE);
                h.read.setText("读到 " + readPct + "%");
            } else {
                h.read.setVisibility(View.GONE);
            }
        }
        boolean sel = e.chapter == current;
        if (h.dot != null) h.dot.setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
        h.tv.setTextColor(sel ? 0xFFFF6B35 : 0xFFB9B9BE);
        h.tv.setTypeface(Typeface.create(Typeface.DEFAULT, sel ? Typeface.BOLD : Typeface.NORMAL));
        h.tv.setOnClickListener(v -> listener.onClick(e));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tv;
        final TextView sub;
        final TextView read;
        final View dot;

        VH(View v) {
            super(v);
            tv = v.findViewById(R.id.chTitle);
            sub = v.findViewById(R.id.chSub);
            dot = v.findViewById(R.id.dot);
            read = v.findViewById(R.id.chRead);
        }
    }
}
