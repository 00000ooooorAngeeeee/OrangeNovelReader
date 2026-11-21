package com.orange.novel.reader.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * txt 章节切分：纯逻辑、不依赖 Android，方便离线验证。
 *
 * <p>处理的现实情况：开头可能有大段目录（一行一章、没有正文）、
 * 章节可能倒序、同一章可能出现两遍、标题可能只有编号没有名字。
 */
public final class TxtParser {

    private TxtParser() {
    }

    /** 第X章 / 第X回 / 第X节，允许数字里夹多余的“第”（第两百第一十九章） */
    private static final Pattern HEAD = Pattern.compile(
            "^\\s*第\\s*([0-9零〇一二三四五六七八九十百千两第]{1,16})\\s*[章回节]");
    /** 漏写“章”字的：第一千四百七十九 献祭两大天域 */
    private static final Pattern HEAD_NO_UNIT = Pattern.compile(
            "^\\s*第\\s*([0-9零〇一二三四五六七八九十百千两]{1,10})\\s+\\S.{0,30}$");

    private static final int HEAD_MAX_LEN = 40;

    public static class Ch {
        public int num;
        public String title;
        public String text;
    }

    public static class Parsed {
        public String title;
        public final List<Ch> chapters = new ArrayList<>();
    }

    public static Parsed parse(String raw, String fallbackTitle) {
        Parsed out = new Parsed();
        out.title = fallbackTitle;

        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = text.split("\n", -1);

        // 1) 全文收集标题行（不预先排除开头的目录：目录里的标题后面没有正文，稍后按文字量淘汰）
        List<int[]> heads = new ArrayList<>();   // {行号, 章号}
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) continue;
            boolean prevBlank = i == 0 || lines[i - 1].trim().isEmpty();
            int n = headNumber(t, prevBlank);
            if (n > 0) heads.add(new int[]{i, n});
        }

        // 2) 第一个标题之前的内容（前言/书名页），够长才算一章
        if (heads.isEmpty()) {
            String body = join(lines, 0, lines.length);
            if (body.length() > 0) {
                Ch c = new Ch();
                c.num = 0;
                c.title = fallbackTitle;
                c.text = body;
                out.chapters.add(c);
            }
            return out;
        }
        String pre = join(lines, 0, heads.get(0)[0]);
        if (pre.length() >= 200 && cjkCount(pre) >= 100) {
            Ch c = new Ch();
            c.num = 0;
            c.title = null;
            c.text = pre;
            out.chapters.add(c);
        }

        // 3) 按标题切段；每段文字太少的说明是目录条目，丢掉
        for (int k = 0; k < heads.size(); k++) {
            int from = heads.get(k)[0] + 1;
            int to = k + 1 < heads.size() ? heads.get(k + 1)[0] : lines.length;
            String body = join(lines, from, to);
            if (body.length() < 20) continue;
            Ch c = new Ch();
            c.num = heads.get(k)[1];
            c.title = lines[heads.get(k)[0]].trim();
            c.text = body;
            out.chapters.add(c);
        }

        // 4) 同号去重（保留文字最多的），再按章号排序
        Map<Integer, Ch> best = new LinkedHashMap<>();
        List<Ch> unnumbered = new ArrayList<>();
        for (Ch c : out.chapters) {
            if (c.num <= 0) {
                unnumbered.add(c);
                continue;
            }
            Ch old = best.get(c.num);
            if (old == null || c.text.length() > old.text.length()) best.put(c.num, c);
        }
        List<Ch> merged = new ArrayList<>(best.values());
        boolean mostlyNumbered = merged.size() >= 3
                && merged.size() >= (merged.size() + unnumbered.size()) * 0.8f;
        if (mostlyNumbered) {
            Collections.sort(merged, (a, b) -> Integer.compare(a.num, b.num));
        }

        out.chapters.clear();
        out.chapters.addAll(unnumbered);
        out.chapters.addAll(merged);
        return out;
    }

    /** @return 章号，识别不出返回 -1 */
    private static int headNumber(String line, boolean allowNoUnit) {
        if (line == null) return -1;
        String t = line.trim();
        if (t.isEmpty() || t.length() > HEAD_MAX_LEN) return -1;
        Matcher m = HEAD.matcher(t);
        if (m.find()) {
            int n = toInt(m.group(1));
            return n > 0 && n < 100000 ? n : -1;
        }
        if (allowNoUnit) {
            Matcher m2 = HEAD_NO_UNIT.matcher(t);
            if (m2.find()) {
                int n = toInt(m2.group(1));
                return n > 0 && n < 100000 ? n : -1;
            }
        }
        return -1;
    }

    /** 统计中日韩字符数，用来区分“正文”和“====== 书名 ======”这种分隔装饰 */
    private static int cjkCount(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) n++;
        }
        return n;
    }

    private static String join(String[] lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to && i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        return sb.toString();
    }

    /** 自动识别编码：BOM → UTF-8 → GB18030 兜底 */
    public static String decode(byte[] data) {
        if (data.length > 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        }
        if (data.length > 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        }
        if (data.length > 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            try {
                return new String(data, Charset.forName("GB18030"));
            } catch (Exception e2) {
                return new String(data, StandardCharsets.UTF_8);
            }
        }
    }

    public static int toInt(String s) {
        if (s == null || s.isEmpty()) return -1;
        s = s.replace("第", "");
        if (s.matches("[0-9]+")) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        boolean place = s.indexOf('十') >= 0 || s.indexOf('百') >= 0
                || s.indexOf('千') >= 0 || s.indexOf('万') >= 0;
        if (!place) {
            int v = 0;
            for (int i = 0; i < s.length(); i++) {
                int d = digit(s.charAt(i));
                if (d < 0) return -1;
                v = v * 10 + d;
            }
            return v;
        }
        int total = 0, sec = 0, num = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int d = digit(c);
            if (d >= 0) {
                num = d;
            } else if (c == '十') {
                sec += (num == 0 ? 1 : num) * 10;
                num = 0;
            } else if (c == '百') {
                sec += (num == 0 ? 1 : num) * 100;
                num = 0;
            } else if (c == '千') {
                sec += (num == 0 ? 1 : num) * 1000;
                num = 0;
            } else if (c == '万') {
                total += (sec + num) * 10000;
                sec = 0;
                num = 0;
            } else {
                return -1;
            }
        }
        return total + sec + num;
    }

    private static int digit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        switch (c) {
            case '零':
            case '〇':
                return 0;
            case '一':
                return 1;
            case '二':
            case '两':
                return 2;
            case '三':
                return 3;
            case '四':
                return 4;
            case '五':
                return 5;
            case '六':
                return 6;
            case '七':
                return 7;
            case '八':
                return 8;
            case '九':
                return 9;
            default:
                return -1;
        }
    }
}
