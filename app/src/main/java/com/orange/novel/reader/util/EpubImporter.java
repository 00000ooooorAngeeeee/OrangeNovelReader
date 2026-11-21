package com.orange.novel.reader.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.orange.novel.reader.db.Book;
import com.orange.novel.reader.db.BookStore;
import com.orange.novel.reader.epub.EpubBook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 导入：把 uri 指向的 epub 复制进应用私有目录，解析元数据并生成封面。 */
public class EpubImporter {

    public static Book importUri(Context ctx, Uri uri) throws IOException {
        return importUri(ctx, uri, null);
    }

    /** @param forcedTitle 非空时用它当书架标题（txt 转出来的临时 epub 用文件名） */
    public static Book importUri(Context ctx, Uri uri, String forcedTitle) throws IOException {
        File books = dir(ctx, "books");
        File covers = dir(ctx, "covers");
        long ts = System.currentTimeMillis();

        File tmp = new File(books, "tmp_" + ts + ".epub");
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("无法读取文件");
            try (OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (tmp.length() == 0) {
            tmp.delete();
            throw new IOException("文件为空");
        }

        EpubBook ep;
        try {
            ep = EpubBook.open(tmp);
        } catch (Exception e) {
            tmp.delete();
            String msg = e.getMessage();
            if (msg != null && msg.contains("DRM")) throw new IOException(msg);
            throw new IOException("这本书打不开，请确认是 .epub 格式且文件完整");
        }

        Book b = new Book();
        // 书架显示文件名（去掉扩展名），而不是 epub 内部的元数据书名：
        // 很多书的 dc:title 是站点名/拼音/乱码，文件名才是用户认得出的那一个
        String fromFile = forcedTitle != null ? forcedTitle : stripExt(displayName(ctx, uri));
        if (fromFile != null && !fromFile.isEmpty()) {
            b.title = fromFile;
        } else {
            b.title = ep.title == null || ep.title.isEmpty() ? "未命名" : ep.title;
        }
        b.author = ep.author == null ? "" : ep.author;
        b.bytes = ep.totalBytes;
        // 字数估算：UTF-8 中文约 3 字节/字，正文与标签大致 3.5:1
        b.words = Math.max(1, Math.round(ep.totalBytes / 3.5f));
        int bodyCount = 0;
        for (EpubBook.Chapter c : ep.chapters) {
            if (c.body) bodyCount++;
        }
        b.chapterCount = bodyCount > 0 ? bodyCount : ep.chapters.size();
        b.addedAt = ts;
        b.lastReadAt = 0;

        File dest = new File(books, "b_" + ts + ".epub");
        if (!tmp.renameTo(dest)) {
            // 罕见情况下 rename 失败，退化为复制
            try (InputStream i2 = new java.io.FileInputStream(tmp);
                 OutputStream o2 = new FileOutputStream(dest)) {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = i2.read(buf)) > 0) o2.write(buf, 0, n);
            }
            tmp.delete();
        }
        b.path = dest.getAbsolutePath();

        String coverPath = null;
        if (ep.coverEntry != null) {
            byte[] data = ep.readBytes(ep.coverEntry);
            Bitmap bm = decode(data, 640);
            if (bm != null) {
                File cf = new File(covers, "c_" + ts + ".jpg");
                if (saveJpeg(bm, cf)) coverPath = cf.getAbsolutePath();
                bm.recycle();
            }
        }
        if (coverPath == null) {
            Bitmap bm = CoverUtil.generate(b.title, 480, 672);
            File cf = new File(covers, "c_" + ts + ".jpg");
            if (saveJpeg(bm, cf)) coverPath = cf.getAbsolutePath();
            bm.recycle();
        }
        b.coverPath = coverPath;

        try {
            ep.close();
        } catch (Exception ignored) {
        }
        BookStore.get(ctx).insert(b);
        return b;
    }

    /** 从 SAF uri 里取原始文件名（如 "实例.epub"） */
    /** 提供给 txt 导入复用的文件名工具 */
    public static String nameOf(Context ctx, Uri uri) {
        return stripExt(displayName(ctx, uri));
    }

    private static String displayName(Context ctx, Uri uri) {
        String name = null;
        try (android.database.Cursor c = ctx.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.trim().isEmpty()) {
            // file:// 之类没有 DISPLAY_NAME 的情况退化为路径末段
            String seg = uri.getLastPathSegment();
            if (seg != null) {
                int slash = seg.lastIndexOf('/');
                if (slash >= 0) seg = seg.substring(slash + 1);
                if (seg.indexOf('.') > 0) name = seg; // 纯数字 id 之类不当作文件名
            }
        }
        return name;
    }

    /** 去掉 .epub 扩展名（大小写不敏感），其余不动 */
    private static String stripExt(String name) {
        if (name == null) return null;
        String s = name.trim();
        String low = s.toLowerCase(java.util.Locale.ROOT);
        for (String ext : new String[]{".epub", ".txt"}) {
            if (low.endsWith(ext)) {
                s = s.substring(0, s.length() - ext.length()).trim();
                break;
            }
        }
        return s;
    }

    private static File dir(Context ctx, String name) {
        File d = new File(ctx.getFilesDir(), name);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static Bitmap decode(byte[] data, int maxW) {
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxW) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean saveJpeg(Bitmap bm, File f) {
        try (OutputStream out = new FileOutputStream(f)) {
            return bm.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } catch (Exception e) {
            return false;
        }
    }

    public static void deleteBookFiles(Book b) {
        try {
            if (b.path != null) new File(b.path).delete();
            if (b.coverPath != null) new File(b.coverPath).delete();
        } catch (Exception ignored) {
        }
    }
}
