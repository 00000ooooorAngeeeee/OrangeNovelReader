package com.orange.novel.reader.reader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import com.orange.novel.reader.db.Book;
import com.orange.novel.reader.epub.ChapterSource;
import com.orange.novel.reader.epub.EpubBook;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 连续滚动阅读视图：章节首尾相接，滚动到边界自动续接上下章。
 * 文字用 StaticLayout 逐段排版，滚动时按需绘制。
 */
public class ReaderView extends View {

    public interface Callback {
        void onProgressChanged(int chapter, float progress, String chapterTitle);

        void onToggleMenu();

        void onScrollIdle();

        /** 长按选中文字：rect 为选区在视图内的矩形，用于浮动工具条定位 */
        void onTextSelected(RectF rect, String text);

        void onSelectionCleared();
    }

    private static final float BASE_TEXT_SP = 19f;
    public static final int FONT_MIN = 14;
    public static final int FONT_MAX = 30;
    public static final int FONT_DEFAULT = (int) BASE_TEXT_SP;
    private static final float LINE_MULT = 1.62f;

    /** 正文字号（sp，仍会随系统字号缩放叠加） */
    private int fontSp = FONT_DEFAULT;

    private final ArrayList<ChapterLayout> loaded = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Book book;
    private EpubBook epub;
    private ReaderStyle style;
    private Callback callback;

    private OverScroller scroller;
    private VelocityTracker vt;

    private int scrollY;
    private int inTop, inBottom;
    private int padSide, padTop, padBottom;
    private boolean loadingNext, loadingPrev;
    private int generation;
    private int touchSlop, minFling, maxFling;
    private boolean menuVisible;
    private boolean flinging;
    /** 上一帧 scroller 的 Y：惯性只消费增量，避免绝对坐标与内容坐标空间错位 */
    private int lastFlingY;
    private boolean progressPosted;

    // 文本选择
    private boolean selecting;
    private int selChapter = -1, selBlock = -1, selStart, selEnd;
    private int dragHandle; // 0 无 / 1 起点 / 2 终点
    private GestureDetector gesture;
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** >=0 时表示按比例定位（进度条跳转用） */
    private float pendingFrac = -1f;

    private float downX, downY, lastY;
    private boolean dragged;

    private final Paint footerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint loadingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable saveTick = this::fireIdle;

    public ReaderView(Context c) {
        this(c, null);
    }

    public ReaderView(Context c, AttributeSet a) {
        super(c, a);
        setWillNotDraw(false);
        scroller = new OverScroller(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        touchSlop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        footerPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        footerPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f,
                getResources().getDisplayMetrics()));
        footerPaint.setColor(0xFF7E7E86);
        loadingPaint.setColor(0xFF5C5C63);
        loadingPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f,
                getResources().getDisplayMetrics()));
        loadingPaint.setTypeface(Typeface.DEFAULT);

        selPaint.setColor(0x40FF6B35);
        handlePaint.setColor(0xFFFF6B35);
        handleRing.setStyle(Paint.Style.STROKE);
        handleRing.setColor(0xFFFFFFFF);
        handleRing.setStrokeWidth(dp(1.6f));

        gesture = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (!selecting && !loaded.isEmpty()) beginSelection(e.getX(), e.getY());
            }
        });
        setFocusable(true);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    public void setInsets(int top, int bottom) {
        if (inTop == top && inBottom == bottom) return;
        inTop = top;
        inBottom = bottom;
        boolean had = !loaded.isEmpty();
        int ch = currentChapter();
        int off = currentOffset();
        buildStyle();
        if (had && epub != null) {
            openAt(ch, off);
        } else if (book != null && epub != null) {
            openAt(startChapter(book.chapter), book.offset);
        }
    }

    /** 未读过的书从第一条正文开始，避免打开就落在封面/目录页 */
    private int startChapter(int saved) {
        if (epub == null) return saved;
        if (saved > 0 && epub.isBody(saved)) return saved;
        int first = epub.firstBody();
        return first >= 0 ? first : saved;
    }

    public void setMenuVisible(boolean visible) {
        if (menuVisible == visible) return;
        menuVisible = visible;
        invalidate();
    }

    private int totalChapters;

    public void setBook(Book book, EpubBook epub) {
        this.book = book;
        this.epub = epub;
        int body = 0;
        if (epub != null) {
            for (EpubBook.Chapter c : epub.chapters) {
                if (c.body) body++;
            }
        }
        totalChapters = epub == null ? body : Math.max(body, epub.maxChapterNo);
        if (getWidth() == 0 || getHeight() == 0) return; // 等 onSizeChanged
        buildStyle();
        openAt(startChapter(book.chapter), book.offset);
    }

    private float lineMult = LINE_MULT;
    private float paraMult = 0.70f;

    public float getLineMult() {
        return lineMult;
    }

    public void setLineMult(float v) {
        v = Math.max(1.2f, Math.min(2.4f, v));
        if (Math.abs(v - lineMult) < 0.001f) return;
        lineMult = v;
        relayoutKeepingPosition();
    }

    public float getParaMult() {
        return paraMult;
    }

    public void setParaMult(float v) {
        v = Math.max(0.2f, Math.min(2.0f, v));
        if (Math.abs(v - paraMult) < 0.001f) return;
        paraMult = v;
        relayoutKeepingPosition();
    }

    /** 按当前位置重排，保持正在读的那一段仍在屏幕顶部 */
    private void relayoutKeepingPosition() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        int ch = currentChapter();
        int off = currentOffset();
        boolean had = !loaded.isEmpty();
        buildStyle();
        if (had && epub != null) openAt(ch, off);
        else invalidate();
    }

    public int getFontSize() {
        return fontSp;
    }

    /** 改字号后按当前位置重排，保持正在读的那一段仍在屏幕顶部 */
    public void setFontSize(int sp) {
        sp = Math.max(FONT_MIN, Math.min(FONT_MAX, sp));
        if (sp == fontSp) return;
        int ch = currentChapter();
        int off = currentOffset();
        boolean had = !loaded.isEmpty();
        fontSp = sp;
        if (getWidth() > 0 && getHeight() > 0) buildStyle();
        if (had && epub != null) {
            openAt(ch, off);
        } else {
            invalidate();
        }
    }

    // ------------------------------------------------------------- 排版参数

    private void buildStyle() {
        ReaderStyle s = new ReaderStyle();
        s.density = getResources().getDisplayMetrics().density;
        s.lineMult = lineMult;
        s.paraMult = paraMult;
        s.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSp,
                getResources().getDisplayMetrics());
        padSide = Math.round(s.dp(22));
        padTop = inTop + Math.round(s.dp(14));
        padBottom = inBottom + Math.round(s.dp(34));
        s.width = Math.max(1, getWidth() - padSide * 2);
        s.height = Math.max(1, getHeight() - padTop - padBottom);
        style = s;
    }

    private int contentH() {
        return style == null ? 1 : style.height;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w == 0 || h == 0) return;
        int ch = currentChapter();
        int off = currentOffset();
        boolean had = !loaded.isEmpty();
        buildStyle();
        if (epub == null) return;
        if (had) openAt(ch, off);
        else if (book != null) openAt(startChapter(book.chapter), book.offset);
    }

    // ------------------------------------------------------------- 章节装载

    private void openAt(int chapter, int offset) {
        openAt(chapter, offset, 1);
    }

    /** dir: 落在非正文章节时往哪个方向找正文（1 向后 / -1 向前） */
    private void openAt(int chapter, int offset, int dir) {
        if (epub == null || epub.chapters.isEmpty()) return;
        clearSelection();
        stopFling();
        int clamped = Math.max(0, Math.min(chapter, epub.chapters.size() - 1));
        int snapped = epub.isBody(clamped) ? clamped : epub.nextBody(clamped, dir);
        if (snapped < 0) snapped = epub.nextBody(clamped, -dir);
        final int idx = snapped >= 0 ? snapped : clamped;
        final int gen = ++generation;
        final float frac = pendingFrac;
        pendingFrac = -1f;
        loaded.clear();
        scrollY = 0;
        loadingNext = loadingPrev = false;
        invalidate();
        io.execute(() -> {
            ChapterLayout lay = loadLayout(idx);
            int y = 0;
            if (lay != null) {
                y = frac >= 0 ? lay.yForChar((int) (frac * lay.charCount)) : lay.yForChar(offset);
            }
            final int targetY = y;
            main.post(() -> {
                if (gen != generation) return;
                loaded.clear();
                if (lay != null) loaded.add(lay);
                scrollY = Math.max(0, targetY);
                invalidate();
                ensureWindow();
                dispatchProgress();
            });
        });
    }

    private ChapterLayout loadLayout(int idx) {
        EpubBook.Chapter ch = epub.chapters.get(idx);
        String html = epub.readText(ch.path);
        ChapterSource src = null;
        if (html != null) {
            src = ChapterSource.build(html, EpubBook.dirOf(ch.path));
        }
        return ChapterLayout.build(style, ch, src, epub);
    }

    private void appendChapter(int idx) {
        if (loadingNext) return;
        loadingNext = true;
        final int gen = generation;
        io.execute(() -> {
            ChapterLayout lay = loadLayout(idx);
            main.post(() -> {
                loadingNext = false;
                if (gen != generation || lay == null) return;
                int last = loaded.isEmpty() ? -1 : loaded.get(loaded.size() - 1).index;
                if (idx <= last) return;
                loaded.add(lay);
                invalidate();
                ensureWindow();
            });
        });
    }

    private void prependChapter(int idx) {
        if (loadingPrev) return;
        loadingPrev = true;
        final int gen = generation;
        io.execute(() -> {
            ChapterLayout lay = loadLayout(idx);
            main.post(() -> {
                loadingPrev = false;
                if (gen != generation || lay == null) return;
                int first = loaded.isEmpty() ? Integer.MAX_VALUE : loaded.get(0).index;
                if (idx >= first) return;
                loaded.add(0, lay);
                scrollY += lay.height;
                invalidate();
                ensureWindow();
                dispatchProgress();
            });
        });
    }

    private void ensureWindow() {
        if (loaded.isEmpty() || epub == null) return;
        int total = loadedHeight();
        int first = loaded.get(0).index;
        int last = loaded.get(loaded.size() - 1).index;
        boolean nearBottom = total - (scrollY + contentH()) < contentH() * 1.2f;
        boolean nearTop = scrollY < contentH() * 1.2f;
        if (!loadingNext && nearBottom && loaded.size() < 6) {
            int next = epub.nextBody(last + 1, 1);
            if (next >= 0) appendChapter(next);
        }
        if (!loadingPrev && nearTop && loaded.size() < 6) {
            int prev = epub.nextBody(first - 1, -1);
            if (prev >= 0) prependChapter(prev);
        }
        prune();
    }

    private void prune() {
        while (loaded.size() > 2 && scrollY - loaded.get(0).height > contentH() * 2.5f) {
            ChapterLayout c = loaded.remove(0);
            scrollY -= c.height;
            c.recycle();
        }
        while (loaded.size() > 2) {
            ChapterLayout last = loaded.get(loaded.size() - 1);
            int lastTop = loadedHeight() - last.height;
            if (lastTop > scrollY + contentH() * 3) {
                loaded.remove(loaded.size() - 1);
                last.recycle();
            } else {
                break;
            }
        }
    }

    private int loadedHeight() {
        int h = 0;
        for (ChapterLayout c : loaded) h += c.height;
        return h;
    }

    // ------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        if (style == null) buildStyle();
        if (loaded.isEmpty()) {
            if (epub != null) {
                String tip = "正在载入…";
                float w = loadingPaint.measureText(tip);
                canvas.drawText(tip, (getWidth() - w) / 2f, getHeight() / 2f, loadingPaint);
            }
            return;
        }
        canvas.save();
        canvas.clipRect(padSide, padTop, getWidth() - padSide, padTop + contentH());
        canvas.translate(padSide, padTop - scrollY);
        int y = 0;
        for (ChapterLayout c : loaded) {
            if (y + c.height - scrollY > 0 && y - scrollY < contentH()) {
                if (selecting && c.index == selChapter) {
                    c.draw(canvas, y, selStart, selEnd, selPaint);
                } else {
                    c.draw(canvas, y);
                }
            }
            y += c.height;
        }
        canvas.restore();
        if (selecting) drawHandles(canvas);
    }

    /** 画两个可拖拽的端点圆 */
    private void drawHandles(Canvas canvas) {
        float[] pts = handlePoints();
        if (pts == null) return;
        float r = dp(7.5f);
        canvas.drawCircle(pts[0], pts[1], r, handlePaint);
        canvas.drawCircle(pts[0], pts[1], r, handleRing);
        canvas.drawCircle(pts[2], pts[3], r, handlePaint);
        canvas.drawCircle(pts[2], pts[3], r, handleRing);
    }

    private void drawFooter(Canvas canvas) {
        if (menuVisible || loaded.isEmpty() || epub == null) return;
        int baseLine = getHeight() - inBottom - Math.round(style.dp(13));
        String right = percentText();
        float rw = footerPaint.measureText(right);
        canvas.drawText(right, getWidth() - padSide - rw, baseLine, footerPaint);

        int ch = currentChapter();
        ChapterLayout cl = chapterOf(ch);
        String name = "第" + (ch + 1) + "章";
        if (cl != null && cl.title != null && !cl.title.isEmpty()) {
            // 只取「第387章」这一截，和总章数凑成「第387章 / 共842章」
            String t = cl.title;
            int sp = t.indexOf(' ');
            name = sp > 0 ? t.substring(0, sp) : t;
        }
        String left = ellipsize(name + " / 共" + totalChapters + "章",
                getWidth() - padSide * 2 - rw - style.dp(16));
        canvas.drawText(left, padSide, baseLine, footerPaint);
    }

    private String ellipsize(String s, float max) {
        if (footerPaint.measureText(s) <= max) return s;
        int n = s.length();
        while (n > 1 && footerPaint.measureText(s.substring(0, n) + "…") > max) n--;
        return s.substring(0, n) + "…";
    }

    private String percentText() {
        float p = progress();
        if (p >= 0.9995f) return "100%";
        return String.format(java.util.Locale.US, "%.2f%%", p * 100f);
    }

    // ------------------------------------------------------------- 位置

    public int currentChapter() {
        if (loaded.isEmpty()) return book == null ? 0 : book.chapter;
        int y = 0;
        for (ChapterLayout c : loaded) {
            if (scrollY < y + c.height) return c.index;
            y += c.height;
        }
        return loaded.get(loaded.size() - 1).index;
    }

    public int currentOffset() {
        if (loaded.isEmpty()) return 0;
        int y = 0;
        for (ChapterLayout c : loaded) {
            if (scrollY < y + c.height) return c.charOffsetAt(Math.max(0, scrollY - y));
            y += c.height;
        }
        ChapterLayout last = loaded.get(loaded.size() - 1);
        return Math.max(0, last.charCount - 1);
    }

    private ChapterLayout chapterOf(int idx) {
        for (ChapterLayout c : loaded) {
            if (c.index == idx) return c;
        }
        return null;
    }

    public String currentTitle() {
        ChapterLayout c = chapterOf(currentChapter());
        return c == null ? "" : c.title;
    }

    public float progress() {
        if (epub == null || loaded.isEmpty()) return 0f;
        int ch = currentChapter();
        ChapterLayout cl = chapterOf(ch);
        if (cl == null) return 0f;
        float frac = Math.min(1f, Math.max(0f, (float) currentOffset() / Math.max(1, cl.charCount)));
        double pos = cl.bytesBefore + cl.bytes * frac;
        return (float) Math.min(1.0, pos / Math.max(1, epub.totalBytes));
    }

    private void dispatchProgress() {
        if (callback == null || loaded.isEmpty() || progressPosted) return;
        progressPosted = true;
        main.post(() -> {
            progressPosted = false;
            if (callback == null || loaded.isEmpty()) return;
            callback.onProgressChanged(currentChapter(), progress(), currentTitle());
        });
    }

    private void fireIdle() {
        if (callback != null) callback.onScrollIdle();
    }

    private void scheduleSave() {
        main.removeCallbacks(saveTick);
        main.postDelayed(saveTick, 900);
    }

    // ------------------------------------------------------------- 滚动

    private void scrollTo(int y) {
        int max = Math.max(0, loadedHeight() - contentH());
        int v = y;
        if (v > max) v = max;
        if (v < 0) v = 0;
        if (v == scrollY) {
            invalidate();
            ensureWindow();
            return;
        }
        scrollY = v;
        invalidate();
        ensureWindow();
        dispatchProgress();
        scheduleSave();
    }

    private void scrollBy(int d) {
        scrollTo(scrollY + d);
    }

    /** 跳到某章某个字符位置（全文搜索用） */
    public void jumpTo(int chapter, int offset) {
        if (epub == null) return;
        int idx = Math.max(0, Math.min(chapter, epub.chapters.size() - 1));
        openAt(idx, Math.max(0, offset), 1);
    }

    public void jumpChapter(int target) {
        if (epub == null) return;
        int idx = Math.max(0, Math.min(target, epub.chapters.size() - 1));
        int dir = target < currentChapter() ? -1 : 1;
        openAt(idx, 0, dir);
    }

    public void seekToProgress(float p) {
        if (epub == null || epub.chapters.isEmpty()) return;
        p = Math.max(0f, Math.min(1f, p));
        double target = p * epub.totalBytes;
        int idx = 0;
        float frac = 1f;
        for (EpubBook.Chapter c : epub.chapters) {
            if (!c.body) continue;   // 非正文不参与定位
            if (target < c.bytesBefore + c.bytes) {
                idx = c.index;
                frac = (float) ((target - c.bytesBefore) / Math.max(1, c.bytes));
                break;
            }
            idx = c.index;
            frac = 1f;
        }
        pendingFrac = Math.max(0f, Math.min(1f, frac));
        openAt(idx, 0);
    }

    // ------------------------------------------------------------- 触摸

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (loaded.isEmpty()) return true;
        gesture.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                lastY = downY;
                dragged = false;
                stopFling();
                if (vt == null) vt = VelocityTracker.obtain();
                else vt.clear();
                vt.addMovement(ev);
                if (selecting) {
                    if (nearHandle(ev.getX(), ev.getY(), 1)) {
                        dragHandle = 1;
                        return true;
                    }
                    if (nearHandle(ev.getX(), ev.getY(), 2)) {
                        dragHandle = 2;
                        return true;
                    }
                    if (!inSelection(ev.getX(), ev.getY())) {
                        clearSelection();
                    }
                    return true;
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (dragHandle != 0) {
                    dragSelection(ev.getX(), ev.getY());
                    return true;
                }
                if (selecting) return true;
                if (vt != null) vt.addMovement(ev);
                float dy = ev.getY() - downY;
                if (!dragged && Math.abs(dy) > touchSlop) {
                    dragged = true;
                    lastY = ev.getY();
                }
                if (dragged) {
                    int d = Math.round(lastY - ev.getY());
                    lastY = ev.getY();
                    if (d != 0) scrollBy(d);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (dragHandle != 0) {
                    dragHandle = 0;
                    notifySelection();
                    return true;
                }
                if (selecting) return true;
                if (vt == null) {
                    if (!dragged) handleTap(downX, downY);
                    return true;
                }
                vt.addMovement(ev);
                if (!dragged) {
                    handleTap(downX, downY);
                    return true;
                }
                vt.computeCurrentVelocity(1000, maxFling);
                float vy = vt.getYVelocity();
                if (Math.abs(vy) > minFling) {
                    lastFlingY = 0;
                    scroller.fling(0, 0, 0, (int) -vy, 0, 0,
                            -1_000_000, 1_000_000);
                    flinging = true;
                    postInvalidateOnAnimation();
                } else {
                    fireIdle();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                dragHandle = 0;
                fireIdle();
                return true;
        }
        return super.onTouchEvent(ev);
    }

    // ------------------------------------------------------------- 选中文字

    public boolean isSelecting() {
        return selecting;
    }

    public String selectedText() {
        ChapterLayout cl = chapterOf(selChapter);
        if (cl == null || selBlock < 0 || selBlock >= cl.blocks.size()) return "";
        ChapterLayout.Block b = cl.blocks.get(selBlock);
        if (b.layout == null) return "";
        CharSequence t = b.layout.getText();
        int ps = Math.max(0, Math.min(t.length(), selStart - b.charStart));
        int pe = Math.max(0, Math.min(t.length(), selEnd - b.charStart));
        if (pe <= ps) return "";
        return t.subSequence(ps, pe).toString().trim();
    }

    public void clearSelection() {
        if (!selecting) return;
        selecting = false;
        dragHandle = 0;
        selBlock = -1;
        selStart = 0;
        selEnd = 0;
        invalidate();
        if (callback != null) callback.onSelectionCleared();
    }

    private void beginSelection(float x, float y) {
        int[] hit = hitTest(x, y);
        if (hit == null) return;
        ChapterLayout cl = chapterOf(hit[0]);
        if (cl == null) return;
        ChapterLayout.Block b = cl.blocks.get(hit[1]);
        if (b.layout == null) return;
        int[] se = sentenceBounds(b.layout.getText(), hit[2]);
        if (se[1] <= se[0]) return;
        selChapter = hit[0];
        selBlock = hit[1];
        selStart = b.charStart + se[0];
        selEnd = b.charStart + se[1];
        selecting = true;
        dragHandle = 0;
        stopFling();
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        notifySelection();
        invalidate();
    }

    /** 把触点换算成 {章号, 块下标, 块内偏移} */
    private int[] hitTest(float x, float y) {
        float cy = y - padTop + scrollY;
        if (cy < 0) return null;
        int top = 0;
        for (ChapterLayout c : loaded) {
            if (cy < top + c.height) {
                float local = cy - top;
                for (int i = 0; i < c.blocks.size(); i++) {
                    ChapterLayout.Block b = c.blocks.get(i);
                    if (b.layout == null || b.header) continue;
                    if (local >= b.top && local < b.top + b.height) {
                        float wy = Math.max(0, local - b.top - b.insetTop);
                        int line = b.layout.getLineForVertical((int) wy);
                        float wx = Math.max(0, x - padSide);
                        int off = b.layout.getOffsetForHorizontal(line, wx);
                        return new int[]{c.index, i, off};
                    }
                }
                return null;
            }
            top += c.height;
        }
        return null;
    }

    private void dragSelection(float x, float y) {
        ChapterLayout cl = chapterOf(selChapter);
        if (cl == null || selBlock < 0 || selBlock >= cl.blocks.size()) return;
        ChapterLayout.Block b = cl.blocks.get(selBlock);
        if (b.layout == null) return;
        float cy = y - padTop + scrollY - chapterTop(cl) - b.top - b.insetTop;
        int line = b.layout.getLineForVertical(Math.max(0, (int) cy));
        int off = b.layout.getOffsetForHorizontal(line, Math.max(0, x - padSide));
        int len = b.layout.getText().length();
        off = Math.max(0, Math.min(len, off));
        if (dragHandle == 1) {
            selStart = Math.min(b.charStart + off, selEnd - 1);
        } else if (dragHandle == 2) {
            selEnd = Math.max(b.charStart + off, selStart + 1);
        }
        notifySelection();
        invalidate();
    }

    private boolean nearHandle(float x, float y, int which) {
        float[] pts = handlePoints();
        if (pts == null) return false;
        float hx = which == 1 ? pts[0] : pts[2];
        float hy = which == 1 ? pts[1] : pts[3];
        float r = dp(24);
        return (x - hx) * (x - hx) + (y - hy) * (y - hy) <= r * r;
    }

    private boolean inSelection(float x, float y) {
        RectF r = selectionBounds();
        if (r == null) return false;
        return y >= r.top - dp(6) && y <= r.bottom + dp(6)
                && x >= r.left - dp(6) && x <= r.right + dp(6);
    }

    private int chapterTop(ChapterLayout target) {
        int y = 0;
        for (ChapterLayout c : loaded) {
            if (c == target) return y;
            y += c.height;
        }
        return 0;
    }

    /** 两个端点圆心的视图坐标 {x1,y1,x2,y2} */
    private float[] handlePoints() {
        ChapterLayout cl = chapterOf(selChapter);
        if (cl == null || selBlock < 0 || selBlock >= cl.blocks.size()) return null;
        ChapterLayout.Block b = cl.blocks.get(selBlock);
        if (b.layout == null) return null;
        android.text.StaticLayout lay = b.layout;
        int len = lay.getText().length();
        int ps = Math.max(0, Math.min(len, selStart - b.charStart));
        int pe = Math.max(0, Math.min(len, selEnd - b.charStart));
        if (pe <= ps) return null;
        int l1 = lay.getLineForOffset(ps);
        int l2 = lay.getLineForOffset(pe - 1);
        float baseY = padTop - scrollY + chapterTop(cl) + b.top + b.insetTop;
        float x1 = ps <= lay.getLineStart(l1) ? lay.getLineLeft(l1) : lay.getPrimaryHorizontal(ps);
        float x2 = pe >= lay.getLineEnd(l2) ? lay.getLineRight(l2) : lay.getPrimaryHorizontal(pe);
        return new float[]{padSide + x1, baseY + lay.getLineTop(l1),
                padSide + x2, baseY + lay.getLineBottom(l2)};
    }

    private RectF selectionBounds() {
        ChapterLayout cl = chapterOf(selChapter);
        if (cl == null || selBlock < 0 || selBlock >= cl.blocks.size()) return null;
        ChapterLayout.Block b = cl.blocks.get(selBlock);
        if (b.layout == null) return null;
        android.text.StaticLayout lay = b.layout;
        int len = lay.getText().length();
        int ps = Math.max(0, Math.min(len, selStart - b.charStart));
        int pe = Math.max(0, Math.min(len, selEnd - b.charStart));
        if (pe <= ps) return null;
        int l1 = lay.getLineForOffset(ps);
        int l2 = lay.getLineForOffset(pe - 1);
        float baseY = padTop - scrollY + chapterTop(cl) + b.top + b.insetTop;
        float left = Float.MAX_VALUE, right = -Float.MAX_VALUE;
        for (int line = l1; line <= l2; line++) {
            left = Math.min(left, lay.getLineLeft(line));
            right = Math.max(right, lay.getLineRight(line));
        }
        if (right <= left) return null;
        return new RectF(padSide + left, baseY + lay.getLineTop(l1),
                padSide + right, baseY + lay.getLineBottom(l2));
    }

    private void notifySelection() {
        if (callback == null) return;
        RectF r = selectionBounds();
        String text = selectedText();
        if (r == null || text.isEmpty()) {
            callback.onSelectionCleared();
            return;
        }
        callback.onTextSelected(r, text);
    }

    /** 中英文通用的"一句"边界：以句末标点/换行切分 */
    private static int[] sentenceBounds(CharSequence t, int pos) {
        int n = t.length();
        if (n == 0) return new int[]{0, 0};
        String stops = "。！？…；!?;\n";
        int p = Math.max(0, Math.min(n - 1, pos));
        int s = p, e = p;
        while (s > 0) {
            char c = t.charAt(s - 1);
            if (stops.indexOf(c) >= 0) break;
            s--;
        }
        while (e < n) {
            char c = t.charAt(e);
            e++;
            if (stops.indexOf(c) >= 0) break;
        }
        while (s < e && Character.isWhitespace(t.charAt(s))) s++;
        while (e > s && Character.isWhitespace(t.charAt(e - 1))) e--;
        if (e <= s) return new int[]{0, n};
        return new int[]{s, e};
    }

    private void handleTap(float x, float y) {
        // 屏幕横向三等分，只有中间一列呼出菜单，左右两侧不响应
        float w = getWidth();
        if (x < w / 3f || x > w * 2f / 3f) return;
        if (callback != null) callback.onToggleMenu();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            // 只取本帧增量：这样 prune/prepend 对 scrollY 的补偿不会被覆盖，
            // 章节异步加载导致可滚范围变大时也不会把越界距离一次性释放
            int dy = scroller.getCurrY() - lastFlingY;
            lastFlingY = scroller.getCurrY();
            if (dy != 0) scrollBy(dy);
            postInvalidateOnAnimation();
        } else if (flinging) {
            flinging = false;
            fireIdle();
        }
    }

    private void stopFling() {
        if (!scroller.isFinished()) scroller.abortAnimation();
        flinging = false;
    }

    public void shutdown() {
        generation++;
        main.removeCallbacks(saveTick);
        io.shutdownNow();
        for (ChapterLayout c : loaded) c.recycle();
        loaded.clear();
    }
}
