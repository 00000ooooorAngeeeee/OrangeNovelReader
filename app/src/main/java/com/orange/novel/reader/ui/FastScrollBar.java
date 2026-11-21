package com.orange.novel.reader.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 目录右侧的快速滚动条：按住拖动可按比例跳到任意位置，
 * 松手后回到跟随列表的位置。列表不需要滚动时自动隐身。
 */
public class FastScrollBar extends View {

    public interface Listener {
        void onFastScroll(int position);

        void onFastScrollEnd();
    }

    private RecyclerView target;
    private Listener listener;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean dragging;

    public FastScrollBar(Context c) {
        this(c, null);
    }

    public FastScrollBar(Context c, AttributeSet a) {
        super(c, a);
        trackPaint.setColor(0x1FFFFFFF);
        thumbPaint.setColor(0x66FFFFFF);
    }

    public void attach(RecyclerView rv) {
        target = rv;
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView r, int dx, int dy) {
                invalidate();
            }

            @Override
            public void onScrollStateChanged(RecyclerView r, int newState) {
                invalidate();
            }
        });
        invalidate();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    private int count() {
        if (target == null || target.getAdapter() == null) return 0;
        return target.getAdapter().getItemCount();
    }

    private LinearLayoutManager lm() {
        if (target == null) return null;
        RecyclerView.LayoutManager m = target.getLayoutManager();
        return m instanceof LinearLayoutManager ? (LinearLayoutManager) m : null;
    }

    private int firstVisible() {
        LinearLayoutManager m = lm();
        return m == null ? 0 : Math.max(0, m.findFirstVisibleItemPosition());
    }

    private int visibleCount() {
        LinearLayoutManager m = lm();
        if (m == null) return 1;
        int a = m.findFirstVisibleItemPosition();
        int b = m.findLastVisibleItemPosition();
        if (a < 0 || b < 0) return 1;
        return b - a + 1;
    }

    private int maxScrollPos() {
        return Math.max(0, count() - visibleCount());
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float trackTop() {
        return dp(8);
    }

    private float trackBottom() {
        return getHeight() - dp(8);
    }

    private float thumbHeight() {
        float h = trackBottom() - trackTop();
        int n = count();
        if (n <= 0) return h;
        return Math.max(dp(32), h * Math.min(1f, visibleCount() / (float) n));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = count();
        if (n <= 0 || visibleCount() >= n) return;
        float cx = getWidth() / 2f;
        float top = trackTop();
        float bottom = trackBottom();
        float th = thumbHeight();
        float travel = Math.max(1, (bottom - top) - th);
        float ratio = maxScrollPos() <= 0 ? 0 : firstVisible() / (float) maxScrollPos();
        float ty = top + ratio * travel;

        canvas.drawRoundRect(cx - dp(1.5f), top, cx + dp(1.5f), bottom,
                dp(1.5f), dp(1.5f), trackPaint);
        thumbPaint.setColor(dragging ? 0xCCFFFFFF : 0x66FFFFFF);
        canvas.drawRoundRect(cx - dp(2f), ty, cx + dp(2f), ty + th,
                dp(2f), dp(2f), thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int n = count();
        if (n <= 0 || visibleCount() >= n) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                jumpTo(ev.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) jumpTo(ev.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    if (listener != null) listener.onFastScrollEnd();
                    invalidate();
                }
                return true;
        }
        return super.onTouchEvent(ev);
    }

    private void jumpTo(float y) {
        LinearLayoutManager m = lm();
        if (m == null) return;
        float top = trackTop();
        float travel = Math.max(1, (trackBottom() - top) - thumbHeight());
        float ratio = (y - top) / travel;
        ratio = Math.max(0f, Math.min(1f, ratio));
        int pos = Math.round(ratio * maxScrollPos());
        m.scrollToPositionWithOffset(pos, 0);
        if (listener != null) listener.onFastScroll(pos);
        invalidate();
    }
}
