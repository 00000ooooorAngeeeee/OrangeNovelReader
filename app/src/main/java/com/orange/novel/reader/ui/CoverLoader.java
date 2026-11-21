package com.orange.novel.reader.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 极简封面异步加载（避免引入图片库）。 */
public class CoverLoader {

    private static CoverLoader inst;

    public static CoverLoader get() {
        if (inst == null) inst = new CoverLoader();
        return inst;
    }

    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(32);
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    public void load(ImageView iv, String path) {
        if (path == null) {
            iv.setImageDrawable(null);
            return;
        }
        Bitmap b = cache.get(path);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        iv.setImageDrawable(null);
        iv.setTag(path);
        pool.execute(() -> {
            Bitmap bm = decode(path);
            if (bm != null) cache.put(path, bm);
            main.post(() -> {
                Object tag = iv.getTag();
                if (path.equals(tag) && bm != null && !bm.isRecycled()) iv.setImageBitmap(bm);
            });
        });
    }

    private Bitmap decode(String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 360) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, o2);
        } catch (Throwable t) {
            return null;
        }
    }
}
