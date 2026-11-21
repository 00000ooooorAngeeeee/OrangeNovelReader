package com.orange.novel.reader.reader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.LeadingMarginSpan;

import com.orange.novel.reader.epub.ChapterSource;
import com.orange.novel.reader.epub.EpubBook;
import com.orange.novel.reader.epub.HtmlBlocks;

import java.util.ArrayList;
import java.util.List;

/**
 * 一章排版后的结果。按段落切块（每段一个 StaticLayout），
 * 这样行距、段距、首行缩进都能精确控制，且滚动时按需绘制。
 */
public class ChapterLayout {

    public static class Block {
        public StaticLayout layout;
        public Bitmap image;
        public int imageW, imageH;
        public int top;
        public int height;
        public int insetTop;
        public int charStart;
        public int charLength;
        public boolean header;
    }

    public int index;
    public String title = "";
    public int height;
    public int charCount;
    public long bytes = 1;
    public long bytesBefore;
    public final List<Block> blocks = new ArrayList<>();

    public static ChapterLayout build(ReaderStyle st, EpubBook.Chapter ch, ChapterSource src, EpubBook book) {
        ChapterLayout cl = new ChapterLayout();
        cl.index = ch.index;
        cl.bytes = Math.max(1, ch.bytes);
        cl.bytesBefore = ch.bytesBefore;

        String title = ch.title;
        if (title == null || title.trim().isEmpty()) title = deriveTitle(src);
        cl.title = title;

        int y = 0;
        int charPos = 0;

        // 正文开头若重复了章标题，跳过那一段，统一由标题块渲染。
        // 注意：有些书每章开头先放一张 logo 图，所以不能只看第一个段，要往后找到第一个标题段
        int skipFrom = -1, skipTo = -1;   // [skipFrom, skipTo) 内是书里自带的标题段
        if (src != null && !src.paras.isEmpty()) {
            int n = src.paras.size();
            for (int pi = 0; pi < Math.min(8, n); pi++) {
                HtmlBlocks.Para p = src.paras.get(pi);
                if (p.text == null || p.text.length() == 0) continue;
                boolean heading = p.type == HtmlBlocks.H1 || p.type == HtmlBlocks.H2
                        || p.type == HtmlBlocks.H3 || titleLike(p.text);
                if (!heading) continue;
                if (sameHeading(p.text.toString(), title)) {
                    skipFrom = pi;
                    int j = pi + 1;
                    // 紧跟的「卡塞尔之门 / The Gate to Cassell」这类副标题一起跳过
                    while (j < n && j <= pi + 2 && titleLike(src.paras.get(j).text)) j++;
                    skipTo = j;
                    break;
                }
                // 不匹配就继续往后找（前面可能有「小丑」这种部件标题、装饰短句）
            }
        }

        if (!title.isEmpty()) {
            float size = st.textSize * 1.16f;
            TextPaint tp = paint(size, st.colorTitle, true);
            StaticLayout sl = makeLayout(title, tp, st.width, 1.35f, size);
            Block b = new Block();
            b.header = true;
            b.layout = sl;
            b.top = y;
            b.insetTop = Math.round(st.textSize * 2.3f);
            b.height = b.insetTop + sl.getHeight() + Math.round(st.textSize * 1.1f);
            b.charStart = 0;
            b.charLength = title.length();
            cl.blocks.add(b);
            y += b.height;
        }

        if (src == null || src.paras.isEmpty()) {
            float size = st.textSize;
            TextPaint tp = paint(size, st.colorQuote, false);
            StaticLayout sl = makeLayout("（本章内容为空）", tp, st.width, st.lineMult, size);
            Block b = new Block();
            b.layout = sl;
            b.top = y;
            b.height = sl.getHeight();
            cl.blocks.add(b);
            cl.height = b.height;
            cl.charCount = 1;
            return cl;
        }

        for (int pi = 0; pi < src.paras.size(); pi++) {
            HtmlBlocks.Para p = src.paras.get(pi);
            if (pi >= skipFrom && pi < skipTo) {
                // 跳过的标题段仍计入字符偏移，否则首段与标题块偏移相同，定位算法会把标题顶出屏幕
                charPos += p.text == null ? 0 : p.text.length();
                continue;
            }
            if (p.type == HtmlBlocks.IMAGE) {
                Bitmap bmp = decode(st, book, p.imgSrc);
                if (bmp == null) continue;
                // 超出显示宽度则等比缩小
                float scale = Math.min(1f, (float) st.width / bmp.getWidth());
                int w = Math.max(1, Math.round(bmp.getWidth() * scale));
                int h = Math.max(1, Math.round(bmp.getHeight() * scale));
                int maxH = Math.round(st.height * 1.05f);
                if (h > maxH) {
                    float s2 = (float) maxH / h;
                    h = maxH;
                    w = Math.max(1, Math.round(w * s2));
                }
                Block b = new Block();
                b.image = bmp;
                b.imageW = w;
                b.imageH = h;
                b.top = y;
                b.insetTop = Math.round(st.textSize * 0.5f);
                b.height = b.insetTop + h + Math.round(st.textSize * 0.8f);
                b.charStart = charPos;
                cl.blocks.add(b);
                y += b.height;
                continue;
            }

            CharSequence text = p.text;
            if (text == null || text.length() == 0) continue;

            float size = st.textSize;
            int color = st.colorText;
            boolean bold = false;
            float lineMult = st.lineMult;
            float insetTop = 0f;
            float gap = st.textSize * st.paraMult;
            int indentChars = 2;

            switch (p.type) {
                case HtmlBlocks.H1:
                    size = st.textSize * 1.14f;
                    bold = true;
                    color = st.colorTitle;
                    indentChars = 0;
                    insetTop = st.textSize * 0.9f;
                    gap = st.textSize * 0.9f;
                    lineMult = 1.4f;
                    break;
                case HtmlBlocks.H2:
                    size = st.textSize * 1.06f;
                    bold = true;
                    color = st.colorTitle;
                    indentChars = 0;
                    insetTop = st.textSize * 0.75f;
                    gap = st.textSize * 0.8f;
                    lineMult = 1.4f;
                    break;
                case HtmlBlocks.H3:
                    bold = true;
                    color = st.colorTitle;
                    indentChars = 0;
                    insetTop = st.textSize * 0.6f;
                    break;
                case HtmlBlocks.QUOTE:
                    color = st.colorQuote;
                    indentChars = 0;
                    break;
                default:
                    break;
            }

            TextPaint tp = paint(size, color, bold);
            SpannableStringBuilder sb = new SpannableStringBuilder(text);
            if (p.type == HtmlBlocks.QUOTE) {
                int m = Math.round(st.textSize * 1.4f);
                sb.setSpan(new LeadingMarginSpan.Standard(m, m), 0, sb.length(),
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (indentChars > 0) {
                sb.setSpan(new LeadingMarginSpan.Standard(Math.round(size * indentChars), 0), 0, sb.length(),
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            StaticLayout sl = makeLayout(sb, tp, st.width, lineMult, size);

            Block b = new Block();
            b.layout = sl;
            b.top = y;
            b.insetTop = Math.round(insetTop);
            b.height = b.insetTop + sl.getHeight() + Math.round(gap);
            b.charStart = charPos;
            b.charLength = text.length();
            cl.blocks.add(b);
            y += b.height;
            charPos += text.length();
        }

        cl.height = y;
        cl.charCount = Math.max(1, charPos);
        return cl;
    }

    private static String deriveTitle(ChapterSource src) {
        if (src != null) {
            int limit = Math.min(4, src.paras.size());
            for (int i = 0; i < limit; i++) {
                HtmlBlocks.Para p = src.paras.get(i);
                if (p.text == null) continue;
                String t = p.text.toString().trim();
                if (t.isEmpty()) continue;
                if ((p.type == HtmlBlocks.H1 || p.type == HtmlBlocks.H2) && t.length() <= 60) return t;
            }
            for (int i = 0; i < limit; i++) {
                HtmlBlocks.Para p = src.paras.get(i);
                if (p.text == null) continue;
                String t = p.text.toString().trim();
                if (t.isEmpty()) continue;
                return t.length() > 22 ? t.substring(0, 22) : t;
            }
        }
        return "正文";
    }

    /** 短、不带句末标点的段落，视为标题行（很多书用居中 &lt;p&gt; 排标题，而不是 &lt;h1&gt;） */
    private static boolean titleLike(CharSequence t) {
        if (t == null) return false;
        String s = t.toString().trim();
        if (s.isEmpty() || s.length() > 20) return false;
        char last = s.charAt(s.length() - 1);
        return "。！？，、；：.!?,;:".indexOf(last) < 0;
    }

    /** 书里自带的标题段是否与归一化后的章标题重复 */
    private static boolean sameHeading(String heading, String title) {
        String a = norm(heading);
        String b = norm(title);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b) || a.contains(b) || b.contains(a)) return true;
        String na = stripNum(a);
        String nb = stripNum(b);
        if (na.isEmpty()) return true;            // 正文标题只有编号（第两百三十二章），一定是本章标题
        if (nb.isEmpty()) return false;
        if (na.equals(nb)) return true;
        return na.length() >= 2 && nb.length() >= 2 && (na.contains(nb) || nb.contains(na));
    }

    private static final java.util.regex.Pattern NUM_PREFIX = java.util.regex.Pattern.compile(
            "^第[0-9零〇一二三四五六七八九十百千两]+[章回节幕话集]");

    private static String stripNum(String s) {
        java.util.regex.Matcher m = NUM_PREFIX.matcher(s);
        return m.find() ? s.substring(m.end()) : s;
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\u3000]+", "").toLowerCase();
    }

    private static TextPaint paint(float size, int color, boolean bold) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
        p.setSubpixelText(true);
        p.setLinearText(false);
        return p;
    }

    private static StaticLayout makeLayout(CharSequence text, TextPaint paint, int width,
                                           float lineMult, float textSize) {
        Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        int base = fm.descent - fm.ascent;
        int want = Math.round(textSize * lineMult);
        int extra = Math.max(0, want - base);
        StaticLayout.Builder b = StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, Math.max(1, width))
                .setLineSpacing(extra, 1f)
                .setIncludePad(false)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        return b.build();
    }

    private static Bitmap decode(ReaderStyle st, EpubBook book, String path) {
        if (path == null || book == null) return null;
        byte[] data = book.readBytes(path);
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int target = Math.max(1, Math.round(st.width * 1.2f));
            int sample = 1;
            while (o.outWidth / (sample * 2) >= target) sample *= 2;

            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ 位置换算

    /** 视口内可见的第一个字符在章内的偏移 */
    public int charOffsetAt(int y) {
        if (blocks.isEmpty()) return 0;
        int lo = 0, hi = blocks.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (blocks.get(mid).top <= y) lo = mid;
            else hi = mid - 1;
        }
        Block b = blocks.get(lo);
        int local = y - b.top - b.insetTop;
        if (b.layout == null) return b.charStart;
        if (local <= 0) return b.charStart;
        int line = b.layout.getLineForVertical(local);
        return b.charStart + b.layout.getLineStart(line);
    }

    /** 字符偏移对应的 y（段落起点） */
    public int yForChar(int offset) {
        if (blocks.isEmpty()) return 0;
        Block best = blocks.get(0);
        for (Block b : blocks) {
            if (b.charStart <= offset) best = b;
            else break;
        }
        return best.top;
    }

    public void draw(Canvas canvas, int top) {
        draw(canvas, top, -1, -1, null);
    }

    /** selStart/selEnd 为章内字符偏移，落在本章时才画选中底色 */
    public void draw(Canvas canvas, int top, int selStart, int selEnd, Paint highlight) {
        boolean hasSel = highlight != null && selEnd > selStart && selStart >= 0;
        for (Block b : blocks) {
            int by = top + b.top;
            if (hasSel && !b.header && b.layout != null
                    && selEnd > b.charStart && selStart < b.charStart + b.charLength) {
                drawHighlight(canvas, b, by, selStart - b.charStart, selEnd - b.charStart, highlight);
            }
            if (b.layout != null) {
                canvas.save();
                canvas.translate(0, by + b.insetTop);
                b.layout.draw(canvas);
                canvas.restore();
            } else if (b.image != null && !b.image.isRecycled()) {
                int left = 0;
                canvas.drawBitmap(b.image, null,
                        new android.graphics.Rect(left, by + b.insetTop, left + b.imageW, by + b.insetTop + b.imageH),
                        null);
            }
        }
    }

    private static void drawHighlight(Canvas canvas, Block b, int by, int from, int to, Paint paint) {
        StaticLayout lay = b.layout;
        int len = lay.getText().length();
        int start = Math.max(0, Math.min(from, len));
        int end = Math.max(0, Math.min(to, len));
        if (end <= start) return;
        int l1 = lay.getLineForOffset(start);
        int l2 = lay.getLineForOffset(end - 1);
        for (int line = l1; line <= l2; line++) {
            int a = Math.max(start, lay.getLineStart(line));
            int b2 = Math.min(end, lay.getLineEnd(line));
            if (b2 <= a) continue;
            float x1 = lay.getPrimaryHorizontal(a);
            float x2 = b2 >= lay.getLineEnd(line) ? lay.getLineRight(line) : lay.getPrimaryHorizontal(b2);
            float ty = by + b.insetTop + lay.getLineTop(line);
            float bt = by + b.insetTop + lay.getLineBottom(line);
            canvas.drawRect(x1, ty, x2, bt, paint);
        }
    }

    public void recycle() {
        for (Block b : blocks) {
            if (b.image != null && !b.image.isRecycled()) b.image.recycle();
            b.image = null;
        }
        blocks.clear();
    }
}
