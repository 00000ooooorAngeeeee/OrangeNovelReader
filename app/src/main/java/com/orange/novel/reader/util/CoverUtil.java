package com.orange.novel.reader.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;

import java.util.ArrayList;
import java.util.List;

/** 没有封面图时按书名生成一张，保证书架不会出现空白格子。 */
public class CoverUtil {

    private static final int[] TOP = {
            0xFF23303F, 0xFF2E2434, 0xFF22322B, 0xFF33271F, 0xFF28293A, 0xFF1F3339, 0xFF3A2A24
    };
    private static final int[] BOTTOM = {
            0xFF101418, 0xFF17121A, 0xFF121815, 0xFF1A1613, 0xFF141420, 0xFF101A1D, 0xFF1B1512
    };

    public static Bitmap generate(String title, int w, int h) {
        Bitmap bm = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        int seed = Math.abs(title == null ? 0 : title.hashCode());
        int idx = seed % TOP.length;

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, w * 0.9f, h, TOP[idx], BOTTOM[idx], Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);

        Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
        veil.setColor(0x33000000);
        c.drawRect(0, h * 0.55f, w, h, veil);

        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xFFF0F0F2);
        tp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        tp.setTextSize(w * 0.132f);
        tp.setShadowLayer(w * 0.012f, 0, w * 0.006f, 0x99000000);

        String t = title == null || title.trim().isEmpty() ? "未命名" : title.trim();
        int maxChars = Math.max(1, (int) ((w * 0.74f) / tp.measureText("国")));
        List<String> lines = wrap(t, maxChars, 4);
        float lineH = tp.getTextSize() * 1.55f;
        float y = h * 0.16f + tp.getTextSize();
        for (String line : lines) {
            c.drawText(line, w * 0.13f, y, tp);
            y += lineH;
        }

        Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
        accent.setColor(0xFFFF6B35);
        float aw = w * 0.16f;
        c.drawRoundRect(new RectF(w * 0.13f, h * 0.855f, w * 0.13f + aw, h * 0.855f + Math.max(2f, h * 0.009f)),
                h * 0.005f, h * 0.005f, accent);

        return bm;
    }

    private static List<String> wrap(String s, int maxChars, int maxLines) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length() && out.size() < maxLines) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch == ' ') {
                if (sb.length() > 0) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
                i++;
                continue;
            }
            sb.append(ch);
            i++;
            if (sb.length() >= maxChars) {
                // 不在英文单词/数字中间断行
                if (i < s.length() && isWordChar(s.charAt(i)) && isWordChar(ch)) {
                    int back = sb.length() - 1;
                    while (back > 0 && isWordChar(sb.charAt(back - 1))) back--;
                    if (back > 0) {
                        String head = sb.substring(0, back);
                        String tail = sb.substring(back);
                        if (out.size() < maxLines) {
                            out.add(head);
                            sb.setLength(0);
                            sb.append(tail);
                            continue;
                        }
                    }
                }
                out.add(sb.toString());
                sb.setLength(0);
            }
        }
        if (sb.length() > 0 && out.size() < maxLines) out.add(sb.toString());
        if (out.isEmpty()) out.add("未命名");
        if (out.size() == maxLines && i < s.length()) {
            String last = out.get(maxLines - 1);
            if (last.length() > 1) out.set(maxLines - 1, last.substring(0, last.length() - 1) + "…");
        }
        return out;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
