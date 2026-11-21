package com.orange.novel.reader.util;

import android.content.Context;
import android.net.Uri;

import com.orange.novel.reader.db.Book;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * txt 导入：切成章节后生成一个标准 epub，再交给 {@link EpubImporter}。
 * 这样目录、章节编号归一化、封面、进度统计全都能复用，不必在阅读器里另开一条路径。
 */
public class TxtImporter {

    private TxtImporter() {
    }

    /** 判断是不是要按 txt 处理（扩展名优先，其次看 mime） */
    public static boolean isTxt(Context ctx, Uri uri) {
        String name = fullName(ctx, uri);
        if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".txt")) return true;
        try {
            String type = ctx.getContentResolver().getType(uri);
            return type != null && type.startsWith("text/plain");
        } catch (Exception e) {
            return false;
        }
    }

    private static String fullName(Context ctx, Uri uri) {
        try (android.database.Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    public static Book importUri(Context ctx, Uri uri) throws IOException {
        String name = EpubImporter.nameOf(ctx, uri);
        if (name == null || name.trim().isEmpty()) name = "未命名";
        byte[] data = readAll(ctx, uri);
        if (data.length == 0) throw new IOException("文件为空");

        TxtParser.Parsed parsed = TxtParser.parse(TxtParser.decode(data), name);
        if (parsed.chapters.isEmpty()) throw new IOException("没有解析出章节内容");

        File tmp = new File(ctx.getCacheDir(), "txt2epub_" + System.currentTimeMillis() + ".epub");
        try {
            writeEpub(tmp, parsed, name);
            return EpubImporter.importUri(ctx, Uri.fromFile(tmp), name);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static byte[] readAll(Context ctx, Uri uri) throws IOException {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("无法读取文件");
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ 生成 epub

    private static void writeEpub(File out, TxtParser.Parsed p, String title) throws IOException {
        ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)));
        try {
            // mimetype 必须是第一个条目且不压缩
            byte[] mt = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            ZipEntry m = new ZipEntry("mimetype");
            m.setMethod(ZipEntry.STORED);
            m.setSize(mt.length);
            m.setCompressedSize(mt.length);
            CRC32 crc = new CRC32();
            crc.update(mt);
            m.setCrc(crc.getValue());
            z.putNextEntry(m);
            z.write(mt);
            z.closeEntry();

            put(z, "META-INF/container.xml",
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                            + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
                            + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" "
                            + "media-type=\"application/oebps-package+xml\"/></rootfiles></container>");

            StringBuilder man = new StringBuilder();
            StringBuilder spine = new StringBuilder();
            int n = p.chapters.size();
            for (int i = 0; i < n; i++) {
                man.append("<item id=\"c").append(i).append("\" href=\"text/ch").append(i)
                        .append(".xhtml\" media-type=\"application/xhtml+xml\"/>\n");
                spine.append("<itemref idref=\"c").append(i).append("\"/>\n");
            }
            put(z, "OEBPS/content.opf",
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                            + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"id\">\n"
                            + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
                            + "<dc:title>" + esc(title) + "</dc:title><dc:language>zh</dc:language>\n"
                            + "<dc:identifier id=\"id\">modu-txt-" + System.currentTimeMillis() + "</dc:identifier>\n"
                            + "</metadata>\n<manifest>\n"
                            + "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n"
                            + "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\"/>\n"
                            + man
                            + "</manifest>\n<spine toc=\"ncx\">\n" + spine + "</spine>\n</package>");

            StringBuilder navMap = new StringBuilder();
            StringBuilder navList = new StringBuilder();
            for (int i = 0; i < n; i++) {
                String t = chapterTitle(p.chapters.get(i));
                navMap.append("<navPoint id=\"n").append(i).append("\" playOrder=\"").append(i + 1)
                        .append("\"><navLabel><text>").append(esc(t)).append("</text></navLabel>")
                        .append("<content src=\"text/ch").append(i).append(".xhtml\"/></navPoint>\n");
                navList.append("<li><a href=\"text/ch").append(i).append(".xhtml\">")
                        .append(esc(t)).append("</a></li>\n");
            }
            put(z, "OEBPS/toc.ncx",
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                            + "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n"
                            + "<head><meta name=\"dtb:uid\" content=\"modu-txt\"/></head>\n"
                            + "<docTitle><text>" + esc(title) + "</text></docTitle>\n<navMap>\n"
                            + navMap + "</navMap></ncx>");

            put(z, "OEBPS/nav.xhtml",
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                            + "<html xmlns=\"http://www.w3.org/1999/xhtml\" "
                            + "xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>目录</title></head>\n"
                            + "<body><nav epub:type=\"toc\" id=\"toc\"><ol>\n" + navList
                            + "</ol></nav></body></html>");

            for (int i = 0; i < n; i++) {
                TxtParser.Ch c = p.chapters.get(i);
                String t = chapterTitle(c);
                StringBuilder body = new StringBuilder();
                if (!t.isEmpty()) body.append("<h2>").append(esc(t)).append("</h2>\n");
                for (String line : c.text.split("\n")) {
                    String s = line.trim();
                    if (s.isEmpty()) continue;
                    body.append("<p>").append(esc(s)).append("</p>\n");
                }
                put(z, "OEBPS/text/ch" + i + ".xhtml",
                        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>"
                                + esc(t) + "</title></head><body>\n" + body + "</body></html>");
            }
        } finally {
            z.close();
        }
    }

    private static String chapterTitle(TxtParser.Ch c) {
        if (c.title != null && !c.title.trim().isEmpty()) return c.title.trim();
        return c.num > 0 ? ("第" + c.num + "章") : "";
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
