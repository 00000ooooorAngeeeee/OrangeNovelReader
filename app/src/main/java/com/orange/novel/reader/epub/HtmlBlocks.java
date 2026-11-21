package com.orange.novel.reader.epub;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.UnderlineSpan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 容错的 XHTML 分词器：把一章的 XHTML 拆成"段"。
 * 不用系统 XML 解析器，因为真实世界的 epub 里到处是未闭合标签。
 */
public final class HtmlBlocks {

    public static final int BODY = 0;
    public static final int H1 = 1;
    public static final int H2 = 2;
    public static final int H3 = 3;
    public static final int QUOTE = 4;
    public static final int IMAGE = 5;

    public static class Para {
        public final int type;
        public CharSequence text;
        public String imgSrc;

        public Para(int type) {
            this.type = type;
        }
    }

    private static final Map<String, String> ENTITIES = new HashMap<>();

    static {
        ENTITIES.put("amp", "&");
        ENTITIES.put("lt", "<");
        ENTITIES.put("gt", ">");
        ENTITIES.put("quot", "\"");
        ENTITIES.put("apos", "'");
        ENTITIES.put("nbsp", " ");
        ENTITIES.put("mdash", "—");
        ENTITIES.put("ndash", "–");
        ENTITIES.put("hellip", "…");
        ENTITIES.put("ldquo", "“");
        ENTITIES.put("rdquo", "”");
        ENTITIES.put("lsquo", "‘");
        ENTITIES.put("rsquo", "’");
        ENTITIES.put("middot", "·");
        ENTITIES.put("bull", "•");
        ENTITIES.put("times", "×");
        ENTITIES.put("divide", "÷");
        ENTITIES.put("copy", "©");
        ENTITIES.put("reg", "®");
        ENTITIES.put("trade", "™");
        ENTITIES.put("deg", "°");
        ENTITIES.put("laquo", "«");
        ENTITIES.put("raquo", "»");
        ENTITIES.put("emsp", " ");
        ENTITIES.put("ensp", " ");
        ENTITIES.put("thinsp", " ");
        ENTITIES.put("shy", "");
        ENTITIES.put("zwsp", "");
    }

    public static List<Para> parse(String html, String baseDir) {
        List<Para> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;

        SpannableStringBuilder buf = new SpannableStringBuilder();
        ArrayDeque<int[]> inline = new ArrayDeque<>();
        int blockType = BODY;
        int n = html.length();
        int i = 0;

        while (i < n) {
            char c = html.charAt(i);
            if (c != '<') {
                int lt = html.indexOf('<', i);
                if (lt < 0) lt = n;
                appendText(buf, html.substring(i, lt));
                i = lt;
                continue;
            }
            if (html.startsWith("<!--", i)) {
                int e = html.indexOf("-->", i + 4);
                i = e < 0 ? n : e + 3;
                continue;
            }
            if (startsWithCI(html, i, "<script")) {
                i = skipBlock(html, i, "</script");
                continue;
            }
            if (startsWithCI(html, i, "<style")) {
                i = skipBlock(html, i, "</style");
                continue;
            }
            if (startsWithCI(html, i, "<head")) {
                i = skipBlock(html, i, "</head");
                continue;
            }
            if (startsWithCI(html, i, "<rt") || startsWithCI(html, i, "<rp")) {
                // 注音内容不展示
                i = skipShortBlock(html, i, startsWithCI(html, i, "<rt") ? "</rt" : "</rp");
                continue;
            }
            int close = html.indexOf('>', i);
            if (close < 0) break;
            String raw = html.substring(i + 1, close).trim();
            i = close + 1;

            if (raw.isEmpty()) continue;
            boolean end = raw.startsWith("/");
            if (end) raw = raw.substring(1).trim();
            boolean self = raw.endsWith("/");
            if (self) raw = raw.substring(0, raw.length() - 1).trim();
            int sp = indexOfWs(raw);
            String name = (sp < 0 ? raw : raw.substring(0, sp)).toLowerCase(Locale.ROOT);
            if (name.startsWith("!")) continue;
            String attrs = sp < 0 ? "" : raw.substring(sp + 1);

            if (!end) {
                switch (name) {
                    case "br":
                        buf.append('\n');
                        continue;
                    case "img": {
                        flush(out, buf, inline, blockType);
                        String src = attrValue(attrs, "src");
                        String resolved = EpubBook.resolve(baseDir, src);
                        if (!resolved.isEmpty()) {
                            Para p = new Para(IMAGE);
                            p.imgSrc = resolved;
                            out.add(p);
                        }
                        continue;
                    }
                    case "hr":
                    case "image":
                        flush(out, buf, inline, blockType);
                        continue;
                    case "h1":
                    case "h2":
                    case "h3":
                    case "h4":
                    case "h5":
                    case "h6": {
                        flush(out, buf, inline, blockType);
                        blockType = "h1".equals(name) ? H1 : ("h2".equals(name) ? H2 : H3);
                        continue;
                    }
                    case "blockquote": {
                        flush(out, buf, inline, blockType);
                        blockType = QUOTE;
                        continue;
                    }
                    case "li": {
                        flush(out, buf, inline, blockType);
                        blockType = BODY;
                        buf.append("· ");
                        continue;
                    }
                    case "p":
                    case "div":
                    case "section":
                    case "article":
                    case "body":
                    case "td":
                    case "th":
                    case "tr":
                    case "dd":
                    case "dt":
                    case "center":
                    case "figure":
                    case "figcaption":
                    case "table": {
                        flush(out, buf, inline, blockType);
                        if (!"div".equals(name) && !"section".equals(name) && !"article".equals(name)) {
                            blockType = BODY;
                        }
                        continue;
                    }
                    case "i":
                    case "em":
                    case "cite":
                        if (!self) inline.push(new int[]{1, buf.length()});
                        continue;
                    case "b":
                    case "strong":
                        if (!self) inline.push(new int[]{2, buf.length()});
                        continue;
                    case "u":
                        if (!self) inline.push(new int[]{3, buf.length()});
                        continue;
                    case "sup":
                        if (!self) inline.push(new int[]{4, buf.length()});
                        continue;
                    case "sub":
                        if (!self) inline.push(new int[]{5, buf.length()});
                        continue;
                    default:
                        continue;
                }
            } else {
                switch (name) {
                    case "p":
                    case "div":
                    case "section":
                    case "article":
                    case "body":
                    case "td":
                    case "th":
                    case "tr":
                    case "dd":
                    case "dt":
                    case "center":
                    case "figure":
                    case "figcaption":
                    case "table":
                    case "h1":
                    case "h2":
                    case "h3":
                    case "h4":
                    case "h5":
                    case "h6":
                    case "blockquote":
                    case "li": {
                        flush(out, buf, inline, blockType);
                        blockType = BODY;
                        continue;
                    }
                    case "i":
                    case "em":
                    case "cite":
                        pop(inline, buf, 1);
                        continue;
                    case "b":
                    case "strong":
                        pop(inline, buf, 2);
                        continue;
                    case "u":
                        pop(inline, buf, 3);
                        continue;
                    case "sup":
                        pop(inline, buf, 4);
                        continue;
                    case "sub":
                        pop(inline, buf, 5);
                        continue;
                    default:
                        continue;
                }
            }
        }
        flush(out, buf, inline, blockType);
        if (out.isEmpty()) {
            // 极端情况：没有任何结构，兜底把纯文本吐出来
            String plain = EpubBook.stripTags(html);
            if (!plain.isEmpty()) {
                Para p = new Para(BODY);
                p.text = plain;
                out.add(p);
            }
        }
        return out;
    }

    /**
     * 有些 epub 源文件里混进了转义坏掉的闭标签，例如
     * {@code <p>　　/p&gt;正文...}，实体解码后段落开头会留下 "/p>"。
     * 只处理段首、只认闭标签形态，正文里正常的尖括号不受影响。
     */
    private static final Pattern STRAY_TAG = Pattern.compile(
            "^/\\s*(p|div|span|h[1-6]|br|li|td|tr|section|article)\\s*>",
            Pattern.CASE_INSENSITIVE);

    private static CharSequence stripStrayTag(CharSequence t) {
        for (int guard = 0; guard < 3; guard++) {
            Matcher m = STRAY_TAG.matcher(t);
            if (!m.find()) break;
            int s = m.end();
            while (s < t.length() && isBlank(t.charAt(s))) s++;
            t = t.subSequence(s, t.length());
        }
        return t;
    }

    private static void flush(List<Para> out, SpannableStringBuilder buf, ArrayDeque<int[]> inline, int type) {
        CharSequence t = trim(buf);
        if (t != null) t = stripStrayTag(t);
        if (t != null && t.length() > 0) {
            Para p = new Para(type);
            p.text = t;
            out.add(p);
        }
        buf.clear();
        inline.clear();
    }

    private static CharSequence trim(SpannableStringBuilder buf) {
        int s = 0, e = buf.length();
        while (s < e && isBlank(buf.charAt(s))) s++;
        while (e > s && isBlank(buf.charAt(e - 1))) e--;
        if (s >= e) return null;
        return new SpannableStringBuilder(buf, s, e);
    }

    private static void pop(ArrayDeque<int[]> inline, SpannableStringBuilder buf, int kind) {
        while (!inline.isEmpty()) {
            int[] top = inline.pop();
            if (top[0] != kind) continue;
            int start = Math.min(top[1], buf.length());
            if (start >= buf.length()) return;
            Object span;
            switch (kind) {
                case 1:
                    span = new StyleSpan(Typeface.ITALIC);
                    break;
                case 2:
                    span = new StyleSpan(Typeface.BOLD);
                    break;
                case 3:
                    span = new UnderlineSpan();
                    break;
                case 4:
                    span = new SuperscriptSpan();
                    break;
                default:
                    span = new SubscriptSpan();
                    break;
            }
            try {
                buf.setSpan(span, start, buf.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (Exception ignored) {
            }
            return;
        }
    }

    private static void appendText(SpannableStringBuilder sb, String s) {
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    String dec = decodeEntity(s.substring(i + 1, semi));
                    if (dec != null) {
                        sb.append(dec);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            if (isBlank(c)) {
                int j = i;
                while (j < n && isBlank(s.charAt(j))) j++;
                char next = j < n ? s.charAt(j) : 0;
                boolean prevCjk = sb.length() > 0 && isCjk(sb.charAt(sb.length() - 1));
                boolean nextCjk = isCjk(next);
                if (!(prevCjk && nextCjk) && sb.length() > 0 && sb.charAt(sb.length() - 1) != ' '
                        && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append(' ');
                }
                i = j;
                continue;
            }
            sb.append(c);
            i++;
        }
    }

    private static String decodeEntity(String ent) {
        if (ent.isEmpty()) return null;
        if (ent.charAt(0) == '#') {
            try {
                int code = ent.length() > 1 && (ent.charAt(1) == 'x' || ent.charAt(1) == 'X')
                        ? Integer.parseInt(ent.substring(2), 16)
                        : Integer.parseInt(ent.substring(1));
                if (code > 0 && code <= 0x10FFFF) return new String(Character.toChars(code));
            } catch (Exception ignored) {
            }
            return null;
        }
        String v = ENTITIES.get(ent.toLowerCase(Locale.ROOT));
        return v;
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u00A0' || c == '\u3000';
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x3000 && c <= 0x303F) || (c >= 0xFF00 && c <= 0xFFEF)
                || (c >= 0x2000 && c <= 0x206F) || (c >= 0x3040 && c <= 0x30FF)
                || (c >= 0xAC00 && c <= 0xD7AF);
    }

    private static boolean startsWithCI(String s, int at, String what) {
        if (at + what.length() > s.length()) return false;
        for (int i = 0; i < what.length(); i++) {
            char a = Character.toLowerCase(s.charAt(at + i));
            char b = Character.toLowerCase(what.charAt(i));
            if (a != b) return false;
        }
        return true;
    }

    private static int skipBlock(String s, int at, String endTag) {
        int e = indexOfCI(s, endTag, at);
        if (e < 0) return s.length();
        int gt = s.indexOf('>', e);
        return gt < 0 ? s.length() : gt + 1;
    }

    private static int skipShortBlock(String s, int at, String endTag) {
        int gt = s.indexOf('>', at);
        if (gt < 0) return s.length();
        int e = indexOfCI(s, endTag, gt);
        if (e < 0 || e - gt > 120) return gt + 1;
        int gt2 = s.indexOf('>', e);
        return gt2 < 0 ? s.length() : gt2 + 1;
    }

    private static int indexOfCI(String s, String what, int from) {
        int n = s.length() - what.length();
        for (int i = Math.max(0, from); i <= n; i++) {
            if (startsWithCI(s, i, what)) return i;
        }
        return -1;
    }

    private static int indexOfWs(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return i;
        }
        return -1;
    }

    private static String attrValue(String attrs, String name) {
        if (attrs == null || attrs.isEmpty()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)" + java.util.regex.Pattern.quote(name) + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
                .matcher(attrs);
        if (m.find()) return m.group(2) != null ? m.group(2) : m.group(3);
        return null;
    }
}
