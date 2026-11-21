package com.modu.reader.epub;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把章节标题统一成「第N章 章节名」。
 *
 * <p>真实 epub 的目录有三种典型情况：只有章节名（没有编号）、已经带「第x章」、
 * 以及夹着封面/版权/上架感言这类非正文条目。这里把它们归一化：
 * <ul>
 *   <li>编号优先沿用原文（中文数字一并解析），没有编号的按上一条 +1 补；</li>
 *   <li>非正文条目不编号、不占号，标题原样保留；</li>
 *   <li>原书编号出现重复或回退时（盗版 epub 常见），整本按正文顺序重排 1..N。</li>
 * </ul>
 */
public final class ChapterTitles {

    private ChapterTitles() {
    }

    /** 第X章 / 第X回 / 第X节；数字里可能夹着多余的“第”（第两百第一十九章） */
    private static final Pattern PREFIX = Pattern.compile(
            "^\\s*第\\s*([0-9零〇一二三四五六七八九十百千两第]{1,16})\\s*[章回节]\\s*[、，,。.．:：\\-—－]?\\s*");

    /** 漏写“章”字的：第一千四百七十九 献祭两大天域 */
    private static final Pattern PREFIX_NO_UNIT = Pattern.compile(
            "^\\s*第\\s*([0-9零〇一二三四五六七八九十百千两]{1,10})\\s+(\\S.*)$");

    /** 作者公告：今天一章、今天一更勿等、刚才更新发错了一下 */
    private static final Pattern NOTICE = Pattern.compile(
            "勿等|别等|请假|断更|停更|补更|更新说明|更新通知|发错"
            + "|(今天|明天|今晚|今日|刚才|刚刚|本周|这两天?)[^。！]{0,10}?"
            + "(一更|两更|三更|一章|两章|三章|更新|不更|停更|请假|休息)");

    /** 分卷标题：第一卷 / 第一部 小丑 / 第壹卷 / 第3篇 …，不参与编号 */
    private static final Pattern VOLUME = Pattern.compile(
            "^\\s*第?\\s*[0-9零〇一二三四五六七八九十百千两壹贰叁肆伍陆柒捌玖拾]{1,8}\\s*[卷部篇]");

    /** 前置页 / 夹在正文里的旁白类条目 */
    private static final Pattern NON_BODY = Pattern.compile(
            "封面|书名页|扉页|版权|制作说明|制作信息|内容简介|作品简介|本书简介|作者简介|作者的话"
                    + "|关于本书|关于作者|^目录$|声明|感言|上架|公告|请假|求票|月票|推荐票|致谢"
                    + "|完本|后记|尾声|番外|致尊敬的读者|致读者|读者的话|cover|titlepage|copyright");

    /** 目录页/封面页这类文件名，无论体积多大都不是正文 */
    private static final Pattern NON_CHAPTER_FILE = Pattern.compile(
            "^(nav|toc|contents|content|cover|titlepage|title|copyright|index)$",
            Pattern.CASE_INSENSITIVE);

    private static final char[] CN_DIGIT = {'零', '一', '二', '三', '四', '五', '六', '七', '八', '九'};

    /**
     * @param tocReliable 目录是否能覆盖大部分 spine（覆盖不了时只能按文件名当章节）
     */
    public static void normalize(List<EpubBook.Chapter> chapters, String bookTitle, boolean tocReliable) {
        if (chapters == null || chapters.isEmpty()) return;
        final int n = chapters.size();
        final String[] names = new String[n];
        final int[] nums = new int[n];
        final boolean[] body = new boolean[n];
        final int[] seg = new int[n];          // 所属卷段（-1 表示还没遇到卷）
        boolean started = false;
        int segId = -1;
        String curVolume = null;

        for (int i = 0; i < n; i++) {
            EpubBook.Chapter ch = chapters.get(i);
            ch.body = false;
            String original = clean(ch.title);
            boolean fromFile = original.isEmpty();
            String raw = fromFile ? clean(nameFromPath(ch.path)) : original;
            int num = parseNum(raw);
            String name = strip(raw);
            boolean nonBody = isNonBody(raw, bookTitle);
            if (fromFile && NON_CHAPTER_FILE.matcher(raw).find()) {
                // nav.xhtml/toc.xhtml 有时有上百 KB，只按体积判会把目录页当成第一章
                nonBody = true;
            } else if (fromFile && tocReliable && ch.bytes < 4000) {
                // 目录可信时，目录里没有条目的 spine 项默认不算正文；
                // 但体积明显是正文（有的书目录漏收了几章）仍然要编号
                nonBody = true;
            }
            if (num > 0) nonBody = false; // 明确带编号的一定是正文

            // 卷标题：既不是章节，也要作为分段的起点
            if (VOLUME.matcher(raw).find() && num <= 0) {
                segId++;
                curVolume = raw;
                ch.volume = raw;
                if (ch.title == null || ch.title.trim().isEmpty()) ch.title = raw;
                continue;
            }

            if (!started) {
                if (nonBody) {
                    if (fromFile) ch.title = frontLabel(raw);
                    continue;                    // 正文之前的封面/版权等：保留原样
                }
                started = true;
            } else if (nonBody) {
                if (fromFile) ch.title = frontLabel(raw);
                continue;                        // 中途的上架感言、公告：保留原样，不占号
            }
            body[i] = true;
            nums[i] = num;
            names[i] = name;
            seg[i] = segId;
            ch.body = true;
            ch.volume = curVolume;
        }

        // 分卷编号：每一卷内部编号递增，就保留各卷自己的编号（各卷从 1 重新开始）
        boolean perVolume = segId >= 1;
        if (perVolume) {
            int lastSeg = Integer.MIN_VALUE, last = 0;
            for (int i = 0; i < n; i++) {
                if (!body[i] || nums[i] <= 0) continue;
                if (seg[i] != lastSeg) {
                    lastSeg = seg[i];
                    last = 0;
                }
                if (last > 0 && nums[i] <= last) {
                    perVolume = false;
                    break;
                }
                last = nums[i];
            }
        }

        // 原编号是否可信：全部有编号且严格递增
        boolean anyNumber = false, increasing = true;
        int lastSeen = 0;
        for (int i = 0; i < n; i++) {
            if (!body[i] || nums[i] <= 0) continue;
            anyNumber = true;
            if (lastSeen > 0 && nums[i] <= lastSeen) increasing = false;
            lastSeen = nums[i];
        }
        final boolean preserve = anyNumber && increasing;

        int counter = 0, lastNum = 0, fillSeg = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            if (!body[i]) continue;
            int num;
            if (perVolume) {
                if (seg[i] != fillSeg) {
                    fillSeg = seg[i];
                    lastNum = 0;
                }
                num = nums[i] > 0 ? nums[i] : lastNum + 1;
            } else if (preserve) {
                num = nums[i] > 0 ? nums[i] : lastNum + 1;
            } else {
                num = ++counter;
            }
            lastNum = num;
            String name = names[i] == null ? "" : names[i].trim();
            chapters.get(i).title = name.isEmpty()
                    ? ("第" + num + "章")
                    : ("第" + num + "章 " + name);
        }
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    private static boolean isNonBody(String title, String bookTitle) {
        if (title == null || title.isEmpty()) return false;
        if (bookTitle != null && !bookTitle.isEmpty() && title.equals(bookTitle.trim())) return true;
        if (VOLUME.matcher(title).find()) return true;
        if (NOTICE.matcher(title).find()) return true;
        return NON_BODY.matcher(title).find();
    }

    /** 返回 {编号, 名字起始下标}；解析不出编号时返回 null */
    private static int[] matchPrefix(String title) {
        if (title == null || title.isEmpty()) return null;
        Matcher m = PREFIX.matcher(title);
        if (m.find()) {
            int v = toInt(m.group(1).replace("第", ""));
            if (v > 0 && v < 100000) return new int[]{v, m.end()};
        }
        Matcher m2 = PREFIX_NO_UNIT.matcher(title);
        if (m2.find()) {
            int v = toInt(m2.group(1));
            if (v > 0 && v < 100000) return new int[]{v, m2.start(2)};
        }
        return null;
    }

    /** 取出编号，取不到返回 -1 */
    private static int parseNum(String title) {
        int[] r = matchPrefix(title);
        return r == null ? -1 : r[0];
    }

    /** 去掉「第x章」前缀（含后置标点） */
    private static String strip(String title) {
        if (title == null) return "";
        int[] r = matchPrefix(title);
        return r == null ? title.trim() : title.substring(r[1]).trim();
    }

    /** 无标题的前置页给个中文名，避免页脚出现孤零零的 "cover" */
    private static String frontLabel(String name) {
        String low = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (low.contains("cover")) return "封面";
        if (low.contains("title")) return "扉页";
        if (low.contains("copyright")) return "版权";
        if (low.equals("toc") || low.contains("nav")) return "目录";
        return name;
    }

    private static String nameFromPath(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        if (slash >= 0) p = p.substring(slash + 1);
        int dot = p.lastIndexOf('.');
        return dot > 0 ? p.substring(0, dot) : p;
    }

    /** 中文/阿拉伯数字转 int，兼容「第二百四十五章」和「第一零零零章」两种写法 */
    public static int toInt(String s) {
        if (s == null || s.isEmpty()) return -1;
        s = s.trim();
        if (s.matches("[0-9]+")) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        boolean placeValue = s.indexOf('十') >= 0 || s.indexOf('百') >= 0
                || s.indexOf('千') >= 0 || s.indexOf('万') >= 0;
        if (!placeValue) {
            // 逐位写法：一零零零 = 1000
            int v = 0;
            for (int i = 0; i < s.length(); i++) {
                int d = digitOf(s.charAt(i));
                if (d < 0) return -1;
                v = v * 10 + d;
            }
            return v;
        }
        int total = 0, section = 0, num = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int d = digitOf(c);
            if (d >= 0) {
                num = d;
            } else if (c == '十') {
                section += (num == 0 ? 1 : num) * 10;
                num = 0;
            } else if (c == '百') {
                section += (num == 0 ? 1 : num) * 100;
                num = 0;
            } else if (c == '千') {
                section += (num == 0 ? 1 : num) * 1000;
                num = 0;
            } else if (c == '万') {
                total += (section + num) * 10000;
                section = 0;
                num = 0;
            } else {
                return -1;
            }
        }
        return total + section + num;
    }

    private static int digitOf(char c) {
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

    /** 仅供调试输出用 */
    static String debugOf(int num) {
        return String.format(Locale.ROOT, "%d", num);
    }
}
