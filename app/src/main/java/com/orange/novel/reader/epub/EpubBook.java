package com.orange.novel.reader.epub;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 一个可随机读取的 EPUB 容器。只解析 container / OPF / 目录，正文按需读取，
 * 所以再大的书打开也很快。
 */
public class EpubBook implements Closeable {

    public static class Chapter {
        public int index;
        public String href;
        public String path;
        public long bytes = 1;
        public long bytesBefore;
        public String title;
        /** 归一化时判定的正文条目：封面/版权/目录页/感言等为 false */
        public boolean body = true;
        /** 所属分卷标题（没有分卷时为 null） */
        public String volume;
    }

    public static class Toc {
        public String title;
        public int chapter = -1;
        public String anchor;
        public int level;
    }

    public final File file;
    public String title = "";
    public String author = "";
    public String language = "";
    public String coverEntry;
    public String opfPath;
    public String opfDir = "";
    public final List<Chapter> chapters = new ArrayList<>();
    public final List<Toc> toc = new ArrayList<>();
    public long totalBytes = 1;
    /** 归一化后最大的章节序号（用于「共 N 章」与末章编号对齐） */
    public int maxChapterNo;

    private ZipFile zip;
    /** 小写路径 -> zip 内真实名字 */
    private final HashMap<String, String> pathIndex = new HashMap<>();

    private EpubBook(File f) {
        this.file = f;
    }

    public static EpubBook open(File f) throws Exception {
        EpubBook b = new EpubBook(f);
        b.zip = new ZipFile(f);
        Enumeration<? extends ZipEntry> en = b.zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String name = norm(e.getName());
            if (name.isEmpty()) continue;
            b.pathIndex.put(name.toLowerCase(Locale.ROOT), name);
        }
        try {
            b.checkDrm();
            b.readStructure();
        } catch (Exception e) {
            b.close();
            throw e;
        }
        return b;
    }

    /** 只有正文被加密才算 DRM；仅字体混淆（Adobe/IDPF）的书仍然可以正常读。 */
    private void checkDrm() {
        if (!pathIndex.containsKey("meta-inf/encryption.xml")) return;
        String enc = readText("META-INF/encryption.xml");
        if (enc == null || !enc.contains("EncryptedData")) return;
        String low = enc.toLowerCase(Locale.ROOT);
        boolean onlyFonts = !low.contains(".xhtml") && !low.contains(".html") && !low.contains(".htm");
        if (onlyFonts) return;
        throw new IllegalStateException("这本书带有 DRM 加密，暂不支持阅读");
    }

    // ---------------------------------------------------------------- 结构解析

    private void readStructure() throws Exception {
        String opf = null;
        String container = readText("META-INF/container.xml");
        if (container != null) {
            Matcher m = Pattern.compile("<rootfile[^>]*full-path\\s*=\\s*[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE).matcher(container);
            if (m.find()) opf = m.group(1);
        }
        if (opf == null) {
            // 兜底：直接找包内第一个 opf
            for (String p : pathIndex.values()) {
                if (p.toLowerCase(Locale.ROOT).endsWith(".opf")) {
                    opf = p;
                    break;
                }
            }
        }
        if (opf == null) throw new IllegalStateException("不是有效的 EPUB：缺少 OPF");
        opfPath = norm(opf);
        opfDir = dirOf(opfPath);
        String xml = readText(opfPath);
        if (xml == null) throw new IllegalStateException("不是有效的 EPUB：OPF 读取失败");
        parseOpf(xml);
        if (chapters.isEmpty()) throw new IllegalStateException("EPUB 中没有可阅读的章节");
        buildTitles();
    }

    private static class Item {
        String id, href, type, props;
    }

    private void parseOpf(String xml) {
        List<Item> manifest = new ArrayList<>();
        Map<String, Item> byId = new HashMap<>();
        List<String> spine = new ArrayList<>();
        String coverId = null;
        String spineToc = null;
        List<String> navProps = new ArrayList<>();
        try {
            XmlPullParser p = newParser(xml);
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev != XmlPullParser.START_TAG) continue;
                String name = local(p.getName());
                if ("meta".equals(name)) {
                    String n = attr(p, "name");
                    String c = attr(p, "content");
                    if ("cover".equalsIgnoreCase(n) && c != null) coverId = c.trim();
                } else if ("item".equals(name)) {
                    Item it = new Item();
                    it.id = attr(p, "id");
                    it.href = attr(p, "href");
                    it.type = attr(p, "media-type");
                    it.props = attr(p, "properties");
                    if (it.href != null) {
                        manifest.add(it);
                        if (it.id != null) byId.put(it.id, it);
                        if (it.props != null && it.props.toLowerCase(Locale.ROOT).contains("nav")) navProps.add(it.href);
                    }
                } else if ("spine".equals(name)) {
                    spineToc = attr(p, "toc");
                } else if ("itemref".equals(name)) {
                    String idref = attr(p, "idref");
                    String linear = attr(p, "linear");
                    if (idref != null && !"no".equalsIgnoreCase(linear)) spine.add(idref.trim());
                } else if ("title".equals(name) && title.isEmpty()) {
                    title = readTextNode(p);
                } else if ("creator".equals(name) && author.isEmpty()) {
                    author = readTextNode(p);
                } else if ("language".equals(name) && language.isEmpty()) {
                    language = readTextNode(p);
                }
            }
        } catch (Exception e) {
            manifest.clear();
            byId.clear();
            spine.clear();
            regexParseOpf(xml, manifest, byId, spine);
        }

        for (String id : spine) {
            Item it = byId.get(id);
            if (it == null || it.href == null) continue;
            String type = it.type == null ? "" : it.type.toLowerCase(Locale.ROOT);
            if (!type.isEmpty() && !(type.contains("html") || type.contains("xml"))) continue;
            Chapter c = new Chapter();
            c.index = chapters.size();
            c.href = it.href;
            c.path = resolve(opfDir, it.href);
            String actual = actualPath(c.path);
            if (actual == null) continue;
            c.path = actual;
            c.bytes = Math.max(1, sizeOf(actual));
            chapters.add(c);
        }

        long sum = 0;
        for (Chapter c : chapters) {
            c.bytesBefore = sum;
            sum += c.bytes;
        }
        totalBytes = Math.max(1, sum);

        // 封面
        if (coverId != null) {
            Item it = byId.get(coverId);
            if (it != null && it.href != null) coverEntry = actualPath(resolve(opfDir, it.href));
        }
        if (coverEntry == null) {
            for (Item it : manifest) {
                if (it.props != null && it.props.toLowerCase(Locale.ROOT).contains("cover-image")) {
                    coverEntry = actualPath(resolve(opfDir, it.href));
                    if (coverEntry != null) break;
                }
            }
        }
        if (coverEntry == null) {
            // 常见命名兜底
            for (Item it : manifest) {
                String type = it.type == null ? "" : it.type.toLowerCase(Locale.ROOT);
                if (!type.startsWith("image/")) continue;
                String h = it.href == null ? "" : it.href.toLowerCase(Locale.ROOT);
                if (h.contains("cover")) {
                    coverEntry = actualPath(resolve(opfDir, it.href));
                    if (coverEntry != null) break;
                }
            }
        }

        // 目录
        String ncxHref = null;
        if (spineToc != null && byId.containsKey(spineToc)) ncxHref = byId.get(spineToc).href;
        if (ncxHref == null) {
            for (Item it : manifest) {
                String type = it.type == null ? "" : it.type.toLowerCase(Locale.ROOT);
                if (type.contains("dtbncx") || (it.href != null && it.href.toLowerCase(Locale.ROOT).endsWith(".ncx"))) {
                    ncxHref = it.href;
                    break;
                }
            }
        }
        if (ncxHref != null) {
            String path = actualPath(resolve(opfDir, ncxHref));
            if (path != null) parseNcx(path, readText(path));
        }
        if (toc.isEmpty()) {
            for (String href : navProps) {
                String path = actualPath(resolve(opfDir, href));
                if (path == null) continue;
                parseNav(path, readText(path));
                if (!toc.isEmpty()) break;
            }
        }
    }

    private void regexParseOpf(String xml, List<Item> manifest, Map<String, Item> byId, List<String> spine) {
        Matcher mi = Pattern.compile("<item\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(xml);
        while (mi.find()) {
            Map<String, String> a = attrsOf(mi.group());
            Item it = new Item();
            it.id = a.get("id");
            it.href = a.get("href");
            it.type = a.get("media-type");
            it.props = a.get("properties");
            if (it.href != null) {
                manifest.add(it);
                if (it.id != null) byId.put(it.id, it);
            }
        }
        Matcher mr = Pattern.compile("<itemref\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(xml);
        while (mr.find()) {
            Map<String, String> a = attrsOf(mr.group());
            String idref = a.get("idref");
            String linear = a.get("linear");
            if (idref != null && !"no".equalsIgnoreCase(linear)) spine.add(idref);
        }
    }

    private void parseNcx(String path, String xml) {
        if (xml == null) return;
        String base = dirOf(path);
        List<Toc> stack = new ArrayList<>();
        try {
            XmlPullParser p = newParser(xml);
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = local(p.getName());
                    if ("navPoint".equals(n)) {
                        Toc t = new Toc();
                        t.level = stack.size();
                        stack.add(t);
                    } else if (!stack.isEmpty()) {
                        Toc top = stack.get(stack.size() - 1);
                        if ("text".equals(n) && top.title == null) {
                            top.title = readTextNode(p);
                        } else if ("content".equals(n) && top.anchor == null) {
                            top.anchor = attr(p, "src");
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && "navPoint".equals(local(p.getName())) && !stack.isEmpty()) {
                    Toc t = stack.remove(stack.size() - 1);
                    finishToc(t, base);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void parseNav(String path, String xml) {
        if (xml == null) return;
        String base = dirOf(path);
        int navStart = -1;
        Matcher nav = Pattern.compile("<nav\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(xml);
        while (nav.find()) {
            String tag = nav.group();
            if (tag.toLowerCase(Locale.ROOT).contains("toc")) {
                navStart = nav.end();
                break;
            }
        }
        if (navStart < 0) return;
        int navEnd = xml.toLowerCase(Locale.ROOT).indexOf("</nav>", navStart);
        if (navEnd < 0) navEnd = xml.length();
        String body = xml.substring(navStart, navEnd);

        Matcher m = Pattern.compile("<a\\b[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(body);
        int last = 0;
        int depth = 0;
        while (m.find()) {
            // 累计（而不是重置）层级，否则同一层里只有第一个条目会被缩进
            depth += countNesting(body.substring(last, m.start()));
            last = m.start();
            Toc t = new Toc();
            t.title = stripTags(m.group(2)).trim();
            t.anchor = m.group(1).trim();
            t.level = Math.max(0, Math.min(2, depth - 1));
            if (!t.title.isEmpty()) finishToc(t, base);
        }
    }

    private static int countNesting(String s) {
        String low = s.toLowerCase(Locale.ROOT);
        int depth = 0, i = 0;
        while (true) {
            int a = low.indexOf("<ol", i);
            int b = low.indexOf("</ol", i);
            if (a < 0 && b < 0) break;
            if (a >= 0 && (b < 0 || a < b)) {
                depth++;
                i = a + 3;
            } else {
                depth--;
                i = b + 4;
            }
        }
        return depth;
    }

    private void finishToc(Toc t, String base) {
        if (t.title == null) t.title = "";
        t.title = t.title.replaceAll("\\s+", " ").trim();
        if (t.title.isEmpty() || t.anchor == null) return;
        String href = t.anchor;
        int hash = href.indexOf('#');
        String frag = hash >= 0 ? href.substring(hash + 1) : null;
        if (hash >= 0) href = href.substring(0, hash);
        String path = resolve(base, href);
        int idx = indexOfPath(path);
        if (idx < 0) return;
        t.chapter = idx;
        t.anchor = frag;
        toc.add(t);
    }

    private int indexOfPath(String path) {
        if (path == null) return -1;
        String low = path.toLowerCase(Locale.ROOT);
        for (Chapter c : chapters) {
            if (c.path.toLowerCase(Locale.ROOT).equals(low)) return c.index;
        }
        return -1;
    }

    private void buildTitles() {
        for (Toc t : toc) {
            if (t.chapter < 0 || t.chapter >= chapters.size()) continue;
            Chapter c = chapters.get(t.chapter);
            if (c.title == null && t.title != null && !t.title.isEmpty()) c.title = t.title;
        }
        // 统一成「第N章 章节名」：目录可信时以目录为正文依据
        boolean tocReliable = !chapters.isEmpty() && toc.size() >= chapters.size() * 0.6f;
        maxChapterNo = ChapterTitles.normalize(chapters, title, tocReliable);
        // 只按正文统计字节，封面/目录页/公告不占进度
        long bodySum = 0;
        for (Chapter c : chapters) {
            if (!c.body) {
                c.bytesBefore = bodySum;
                continue;
            }
            c.bytesBefore = bodySum;
            bodySum += c.bytes;
        }
        totalBytes = Math.max(1, bodySum);
        // 目录面板用的是 Toc.title，同步一份，保证两处显示一致
        for (Toc t : toc) {
            if (t.chapter < 0 || t.chapter >= chapters.size()) continue;
            String nt = chapters.get(t.chapter).title;
            if (nt != null && !nt.isEmpty()) t.title = nt;
        }
        // 目录里若有比 spine 更细的划分（同一文件多个锚点），保留在 toc 中供目录面板展示
        if (title == null || title.trim().isEmpty()) {
            String n = file.getName();
            int dot = n.lastIndexOf('.');
            title = dot > 0 ? n.substring(0, dot) : n;
        }
    }

    // ---------------------------------------------------------------- 正文定位

    public boolean isBody(int idx) {
        return idx >= 0 && idx < chapters.size() && chapters.get(idx).body;
    }

    /** 从 from 开始按 dir 方向找最近的正文章节；找不到返回 -1 */
    public int nextBody(int from, int dir) {
        if (dir == 0) return -1;
        for (int i = from; i >= 0 && i < chapters.size(); i += dir) {
            if (chapters.get(i).body) return i;
        }
        return -1;
    }

    public int firstBody() {
        return nextBody(0, 1);
    }

    // ---------------------------------------------------------------- 资源读取

    public byte[] readBytes(String path) {
        String actual = actualPath(path);
        if (actual == null) return null;
        ZipEntry e = zip.getEntry(actual);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(1024, (int) Math.min(e.getSize(), 1 << 20)));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Exception ex) {
            return null;
        }
    }

    public String readText(String path) {
        byte[] data = readBytes(path);
        return data == null ? null : decode(data);
    }

    private long sizeOf(String path) {
        String actual = actualPath(path);
        if (actual == null) return 1;
        ZipEntry e = zip.getEntry(actual);
        return e == null ? 1 : Math.max(1, e.getSize());
    }

    private String actualPath(String path) {
        if (path == null) return null;
        String p = norm(path);
        String a = pathIndex.get(p.toLowerCase(Locale.ROOT));
        if (a != null) return a;
        a = pathIndex.get(("OEBPS/" + p).toLowerCase(Locale.ROOT));
        return a;
    }

    public static String decode(byte[] data) {
        Charset cs = StandardCharsets.UTF_8;
        int off = 0;
        if (data.length > 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            off = 3;
        } else if (data.length > 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            cs = StandardCharsets.UTF_16BE;
            off = 2;
        } else if (data.length > 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            cs = StandardCharsets.UTF_16LE;
            off = 2;
        } else {
            int len = Math.min(data.length, 4096);
            String head = new String(data, 0, len, StandardCharsets.ISO_8859_1);
            Matcher m = Pattern.compile("encoding\\s*=\\s*[\"']([A-Za-z0-9._\\-]+)[\"']").matcher(head);
            if (!m.find()) {
                m = Pattern.compile("charset\\s*=\\s*[\"']?([A-Za-z0-9._\\-]+)").matcher(head);
            }
            if (m.find()) {
                try {
                    cs = Charset.forName(m.group(1));
                } catch (Exception ignored) {
                    cs = StandardCharsets.UTF_8;
                }
            }
        }
        String s = new String(data, off, data.length - off, cs);
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    @Override
    public void close() {
        try {
            if (zip != null) zip.close();
        } catch (Exception ignored) {
        }
        zip = null;
    }

    // ---------------------------------------------------------------- 工具

    public static String norm(String name) {
        String s = name.replace('\\', '/').trim();
        while (s.startsWith("./")) s = s.substring(2);
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    public static String dirOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    public static String joinPath(String baseDir, String rel) {
        String full = baseDir == null || baseDir.isEmpty() ? rel : baseDir + "/" + rel;
        String[] parts = full.split("/");
        ArrayList<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty() || ".".equals(p)) continue;
            if ("..".equals(p)) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
            } else {
                out.add(p);
            }
        }
        return String.join("/", out);
    }

    /** 把 href 解析为包内路径 */
    public static String resolve(String baseDir, String href) {
        if (href == null) return "";
        String h = href.trim();
        int hash = h.indexOf('#');
        if (hash >= 0) h = h.substring(0, hash);
        if (h.isEmpty()) return "";
        String low = h.toLowerCase(Locale.ROOT);
        if (low.startsWith("http://") || low.startsWith("https://") || low.startsWith("data:")) return "";
        h = urlDecode(h);
        if (h.startsWith("/")) return norm(h);
        return joinPath(baseDir, h);
    }

    private static String urlDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '%' && i + 2 < s.length()) {
                    int v = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                    bo.write(v);
                    i += 2;
                } else {
                    byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                    bo.write(b, 0, b.length);
                }
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static XmlPullParser newParser(String xml) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new StringReader(xml));
        return p;
    }

    private static String local(String name) {
        int i = name.indexOf(':');
        return i < 0 ? name : name.substring(i + 1);
    }

    private static String attr(XmlPullParser p, String name) {
        return p.getAttributeValue(null, name);
    }

    private static String readTextNode(XmlPullParser p) {
        try {
            int depth = p.getDepth();
            StringBuilder sb = null;
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                int t = p.getEventType();
                if (t == XmlPullParser.TEXT) {
                    if (sb == null) sb = new StringBuilder();
                    sb.append(p.getText());
                } else if (t == XmlPullParser.END_TAG && p.getDepth() == depth) {
                    break;
                }
            }
            return sb == null ? "" : sb.toString().replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static Map<String, String> attrsOf(String tag) {
        Map<String, String> m = new HashMap<>();
        Matcher mm = Pattern.compile("([a-zA-Z_:][\\w:.-]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").matcher(tag);
        while (mm.find()) {
            String k = mm.group(1).toLowerCase(Locale.ROOT);
            String v = mm.group(3) != null ? mm.group(3) : mm.group(4);
            m.put(k, v);
        }
        return m;
    }

    public static String stripTags(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
        return s.replaceAll("\\s+", " ").trim();
    }
}
