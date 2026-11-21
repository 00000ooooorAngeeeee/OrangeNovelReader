package com.orange.novel.reader.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.orange.novel.reader.R;
import com.orange.novel.reader.db.Book;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class BookAdapter extends RecyclerView.Adapter<BookAdapter.VH> {

    public static final int MODE_GRID = 0;
    public static final int MODE_LIST = 1;

    public interface Listener {
        void onOpenBook(Book b);

        void onOpenGroup(long groupId, String name);

        void onLongPressBook(Book b);

        void onLongPressGroup(long groupId, String name);
    }

    private final List<ShelfItem> items = new ArrayList<>();
    private final Set<Long> selected = new HashSet<>();
    private final Listener listener;
    private boolean selecting;
    private int mode = MODE_GRID;

    public BookAdapter(Listener l) {
        this.listener = l;
    }

    public void submit(List<ShelfItem> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    public void setSelection(boolean selecting, Set<Long> ids) {
        this.selecting = selecting;
        selected.clear();
        selected.addAll(ids);
        notifyDataSetChanged();
    }

    public void setMode(int mode) {
        if (this.mode == mode) return;
        this.mode = mode;
        notifyDataSetChanged();
    }

    public int getMode() {
        return mode;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type * 2 + mode;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout;
        switch (viewType) {
            case ShelfItem.TYPE_GROUP * 2 + MODE_LIST:
                layout = R.layout.item_group_list;
                break;
            case ShelfItem.TYPE_GROUP * 2 + MODE_GRID:
                layout = R.layout.item_group;
                break;
            case ShelfItem.TYPE_BOOK * 2 + MODE_LIST:
                layout = R.layout.item_book_list;
                break;
            default:
                layout = R.layout.item_book;
        }
        return new VH(LayoutInflater.from(parent.getContext()).inflate(layout, parent, false), viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ShelfItem it = items.get(position);
        if (it.type == ShelfItem.TYPE_GROUP) {
            bindGroup(h, it);
        } else {
            bindBook(h, it.book);
        }
    }

    private void bindGroup(VH h, ShelfItem it) {
        h.title.setText(it.groupName);
        h.progress.setText("共 " + it.groupCount + " 本");
        if (h.check != null) h.check.setVisibility(View.GONE);
        if (h.pin != null) h.pin.setVisibility(View.GONE);

        if (h.ivEmpty != null) {
            h.ivEmpty.setVisibility(it.covers.isEmpty() ? View.VISIBLE : View.GONE);
        }

        ImageView[] slots = {h.gc1, h.gc2, h.gc3, h.gc4};
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) continue;
            if (i < it.covers.size()) {
                CoverLoader.get().load(slots[i], it.covers.get(i));
            } else {
                slots[i].setImageDrawable(null);
            }
        }

        h.itemView.setOnClickListener(v -> listener.onOpenGroup(it.groupId, it.groupName));
        h.itemView.setOnLongClickListener(v -> {
            listener.onLongPressGroup(it.groupId, it.groupName);
            return true;
        });
    }

    private void bindBook(VH h, Book b) {
        h.title.setText(b.title);
        if (mode == MODE_LIST) {
            h.progress.setText(readLine(b));
            if (h.meta != null) h.meta.setText(metaLine(b));
        } else {
            h.progress.setText(compactLine(b));
        }
        CoverLoader.get().load(h.cover, b.coverPath);

        boolean checked = selecting && selected.contains(b.id);
        h.check.setVisibility(selecting ? View.VISIBLE : View.GONE);
        h.check.setImageResource(checked ? R.drawable.ic_check_on : R.drawable.ic_check_off);
        h.mask.setVisibility(checked ? View.VISIBLE : View.GONE);
        if (h.pin != null) h.pin.setVisibility(b.pinned ? View.VISIBLE : View.GONE);

        h.itemView.setOnClickListener(v -> {
            if (selecting) listener.onLongPressBook(b);   // 多选态下点按 = 勾选/取消
            else listener.onOpenBook(b);
        });
        h.itemView.setOnLongClickListener(v -> {
            listener.onLongPressBook(b);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // ------------------------------------------------------------------ 文案

    /** 网格卡片：一行放得下的紧凑进度 */
    static String compactLine(Book b) {
        if (b.progress <= 0.0005f) return "未读";
        String head = b.progress >= 0.995f ? "已读完" : "已读至" + chapterNo(b);
        String t = ago(b.lastReadAt);
        return t.isEmpty() ? head : (head + " · " + t);
    }

    /** 列表主行：已读至第387章 章节名 */
    static String readLine(Book b) {
        if (b.progress <= 0.0005f) return "未开始";
        String title = b.chapterTitle == null ? "" : b.chapterTitle.trim();
        if (title.isEmpty()) title = chapterNo(b);
        if (b.progress >= 0.995f) return "已读完 · " + title;
        return "已读至" + title;
    }

    /** 列表次行：进度 · 时间 */
    static String metaLine(Book b) {
        StringBuilder sb = new StringBuilder();
        float pct = b.progress * 100f;
        if (pct >= 0.05f) {
            sb.append(pct >= 1f ? String.valueOf(Math.round(pct))
                    : String.format(Locale.CHINA, "%.1f", pct)).append('%');
        }
        String t = ago(b.lastReadAt);
        if (!t.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(t);
        }
        return sb.toString();
    }

    /** 从「第387章 名字」里取编号部分 */
    static String chapterNo(Book b) {
        String title = b.chapterTitle == null ? "" : b.chapterTitle.trim();
        if (title.isEmpty()) return "第" + (b.chapter + 1) + "章";
        int sp = title.indexOf(' ');
        return sp > 0 ? title.substring(0, sp) : title;
    }

    /** 相对时间：刚刚 / 12分钟前 / 3小时前 / 昨天 / 5天前 / 2026-03-01 */
    public static String ago(long ts) {
        if (ts <= 0) return "";
        long diff = System.currentTimeMillis() - ts;
        if (diff < 60_000L) return "刚刚";
        if (diff < 3600_000L) return (diff / 60_000L) + "分钟前";
        if (diff < 86400_000L) return (diff / 3600_000L) + "小时前";
        long days = diff / 86400_000L;
        if (days == 1) return "昨天";
        if (days < 30) return days + "天前";
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(ts));
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView cover;
        final View mask;
        final ImageView check;
        final TextView pin;
        final TextView title;
        final TextView progress;
        final TextView meta;
        final ImageView gc1, gc2, gc3, gc4;
        final ImageView ivEmpty;

        VH(View v, int viewType) {
            super(v);
            cover = v.findViewById(R.id.cover);
            mask = v.findViewById(R.id.coverMask);
            check = v.findViewById(R.id.check);
            pin = v.findViewById(R.id.tvPin);
            title = v.findViewById(R.id.title);
            progress = v.findViewById(R.id.progress);
            meta = v.findViewById(R.id.tvMeta);
            boolean group = viewType / 2 == ShelfItem.TYPE_GROUP;
            gc1 = group ? v.findViewById(R.id.gc1) : null;
            gc2 = group ? v.findViewById(R.id.gc2) : null;
            gc3 = group ? v.findViewById(R.id.gc3) : null;
            gc4 = group ? v.findViewById(R.id.gc4) : null;
            ivEmpty = group ? v.findViewById(R.id.ivEmpty) : null;
        }
    }
}
