package com.orange.novel.reader.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.Uri;

import com.orange.novel.reader.db.Book;
import com.orange.novel.reader.epub.EpubBook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 封面的解码 / 裁切 / 落盘。所有方法都可能抛异常，调用方负责放到后台线程。 */
public class CoverManager {

    /** 与 CoverBox 一致：高 / 宽 = 1.40 */
    public static final float RATIO = 1.40f;
    public static final int OUT_W = 640;

    /** 从相册/文件 uri 采样解码，长边不超过 maxDim，避免大图 OOM */
    public static Bitmap decodeUri(Context ctx, Uri uri, int maxDim) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("无法读取图片");
        try {
            BitmapFactory.decodeStream(in, null, bounds);
        } finally {
            close(in);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("这不是一张能识别的图片");

        int sample = 1;
        int longSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (longSide / (sample * 2) >= maxDim) sample *= 2;

        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        Bitmap bm;
        in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("无法读取图片");
        try {
            bm = BitmapFactory.decodeStream(in, null, opt);
        } finally {
            close(in);
        }
        if (bm == null) throw new IOException("图片解码失败");
        return bm;
    }

    public static Bitmap decodeBytes(byte[] data, int maxDim) {
        if (data == null || data.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0) return null;
        int sample = 1;
        int longSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (longSide / (sample * 2) >= maxDim) sample *= 2;
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opt);
    }

    /** 居中裁成 h/w = hOverW 的竖向比例，只切不拉伸 */
    public static Bitmap centerCrop(Bitmap src, float hOverW) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        int cw = w, ch = h;
        if (h / (float) w > hOverW) {
            // 比目标更"竖"，压高度
            ch = Math.max(1, Math.round(w * hOverW));
        } else {
            // 比目标更"扁"，压宽度
            cw = Math.max(1, Math.round(h / hOverW));
        }
        if (cw <= 0 || ch <= 0) return src;
        int x = (w - cw) / 2;
        int y = (h - ch) / 2;
        if (cw == w && ch == h) return src;
        Bitmap out = Bitmap.createBitmap(src, x, y, cw, ch);
        if (out != src) src.recycle();
        return out;
    }

    /** 缩放成标准尺寸并写成新文件；返回绝对路径。文件名唯一，避开 LruCache 里的旧图 */
    public static String save(Context ctx, Bitmap bm) throws IOException {
        File dir = new File(ctx.getFilesDir(), "covers");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建封面目录");
        // 按原图实际比例缩放，避免拉伸
        int outH = Math.max(1, Math.round(OUT_W * (bm.getHeight() / (float) bm.getWidth())));
        Bitmap scaled = Bitmap.createScaledBitmap(bm, OUT_W, outH, true);
        // 带透明通道的图压成 JPEG 会变黑，先垫一层底色
        if (scaled.hasAlpha()) {
            Bitmap flat = Bitmap.createBitmap(OUT_W, outH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(flat);
            c.drawColor(0xFF1C1C21);
            c.drawBitmap(scaled, 0, 0, null);
            if (scaled != bm) scaled.recycle();
            scaled = flat;
        }
        File f = new File(dir, "c_" + System.currentTimeMillis() + "_u.jpg");
        OutputStream os = null;
        try {
            os = new FileOutputStream(f);
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 90, os)) {
                throw new IOException("保存封面失败");
            }
        } finally {
            close(os);
            if (scaled != bm) scaled.recycle();
        }
        return f.getAbsolutePath();
    }

    /** 从 epub 里重新读原封面；没有则返回 null */
    public static Bitmap readEpubCover(Book b) throws IOException {
        if (b == null || b.path == null) return null;
        EpubBook ep;
        try {
            ep = EpubBook.open(new File(b.path));
        } catch (Exception e) {
            throw new IOException("无法读取这本书的封面");
        }
        try {
            if (ep.coverEntry == null) return null;
            return decodeBytes(ep.readBytes(ep.coverEntry), 1600);
        } finally {
            try {
                ep.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 用书名生成一张默认封面 */
    public static Bitmap generate(String title) {
        return CoverUtil.generate(title, OUT_W, Math.round(OUT_W * RATIO));
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
