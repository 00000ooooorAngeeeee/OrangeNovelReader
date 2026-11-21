package com.orange.novel.reader.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class BookStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "modu.db";
    private static final int DB_VERSION = 6;

    private static BookStore instance;

    public static synchronized BookStore get(Context ctx) {
        if (instance == null) {
            instance = new BookStore(ctx.getApplicationContext());
        }
        return instance;
    }

    private BookStore(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL DEFAULT '',"
                + "author TEXT NOT NULL DEFAULT '',"
                + "path TEXT NOT NULL DEFAULT '',"
                + "cover TEXT,"
                + "bytes INTEGER NOT NULL DEFAULT 0,"
                + "chapters INTEGER NOT NULL DEFAULT 0,"
                + "chapter INTEGER NOT NULL DEFAULT 0,"
                + "offset INTEGER NOT NULL DEFAULT 0,"
                + "progress REAL NOT NULL DEFAULT 0,"
                + "added INTEGER NOT NULL DEFAULT 0,"
                + "lastread INTEGER NOT NULL DEFAULT 0,"
                + "pinned INTEGER NOT NULL DEFAULT 0,"
                + "chapterTitle TEXT NOT NULL DEFAULT '',"
                + "words INTEGER NOT NULL DEFAULT 0,"
                + "group_id INTEGER NOT NULL DEFAULT 0"
                + ")");
        db.execSQL("CREATE TABLE groups ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL DEFAULT '',"
                + "created INTEGER NOT NULL DEFAULT 0"
                + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 摘录功能已移除，清掉旧表
        db.execSQL("DROP TABLE IF EXISTS excerpts");
        if (oldVersion < 6) {
            try {
                db.execSQL("ALTER TABLE books ADD COLUMN chapterTitle TEXT NOT NULL DEFAULT ''");
            } catch (Exception ignored) {
            }
            try {
                db.execSQL("ALTER TABLE books ADD COLUMN words INTEGER NOT NULL DEFAULT 0");
            } catch (Exception ignored) {
            }
            try {
                db.execSQL("ALTER TABLE books ADD COLUMN group_id INTEGER NOT NULL DEFAULT 0");
            } catch (Exception ignored) {
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS groups ("
                    + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name TEXT NOT NULL DEFAULT '',"
                    + "created INTEGER NOT NULL DEFAULT 0"
                    + ")");
        }
        if (oldVersion < 5) {
            try {
                db.execSQL("ALTER TABLE books ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0");
            } catch (Exception ignored) {
                // 列已存在
            }
        }
    }

    /** groupId > 0 查该分组；groupId = 0 查「未分组」的书（已归组的书只出现在分组里） */
    public List<Book> all(long groupId) {
        List<Book> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("books", null,
                "group_id=?", new String[]{String.valueOf(groupId)},
                null, null,
                "pinned DESC, CASE WHEN lastread=0 THEN 1 ELSE 0 END, lastread DESC, added DESC");
        try {
            while (c.moveToNext()) out.add(read(c));
        } finally {
            c.close();
        }
        return out;
    }

    public List<Book> all() {
        List<Book> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("books", null, null, null, null, null,
                "pinned DESC, CASE WHEN lastread=0 THEN 1 ELSE 0 END, lastread DESC, added DESC");
        try {
            while (c.moveToNext()) out.add(read(c));
        } finally {
            c.close();
        }
        return out;
    }

    public Book get(long id) {
        Cursor c = getReadableDatabase().query("books", null, "_id=?", new String[]{String.valueOf(id)},
                null, null, null);
        try {
            if (c.moveToFirst()) return read(c);
        } finally {
            c.close();
        }
        return null;
    }

    public long insert(Book b) {
        ContentValues v = new ContentValues();
        v.put("title", b.title);
        v.put("author", b.author);
        v.put("path", b.path);
        v.put("cover", b.coverPath);
        v.put("bytes", b.bytes);
        v.put("chapters", b.chapterCount);
        v.put("chapter", b.chapter);
        v.put("offset", b.offset);
        v.put("progress", b.progress);
        v.put("added", b.addedAt);
        v.put("lastread", b.lastReadAt);
        long id = getWritableDatabase().insert("books", null, v);
        b.id = id;
        return id;
    }

    public void saveProgress(long id, int chapter, int offset, float progress) {
        ContentValues v = new ContentValues();
        v.put("chapter", chapter);
        v.put("offset", offset);
        v.put("progress", progress);
        v.put("lastread", System.currentTimeMillis());
        getWritableDatabase().update("books", v, "_id=?", new String[]{String.valueOf(id)});
    }

    public void updateChapterCount(long id, int n) {
        ContentValues v = new ContentValues();
        v.put("chapters", n);
        getWritableDatabase().update("books", v, "_id=?", new String[]{String.valueOf(id)});
    }

    public void setGroup(long id, long groupId) {
        ContentValues v = new ContentValues();
        v.put("group_id", groupId);
        getWritableDatabase().update("books", v, "_id=?", new String[]{String.valueOf(id)});
    }

    /** 记录当前读到的章节名，书架显示「已读至 …」用 */
    public void updateReadChapter(long id, String chapterTitle) {
        ContentValues v = new ContentValues();
        v.put("chapterTitle", chapterTitle == null ? "" : chapterTitle);
        getWritableDatabase().update("books", v, "_id=?", new String[]{String.valueOf(id)});
    }

    // ------------------------------------------------------------- 分组

    public List<String[]> groups() {
        List<String[]> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("groups", new String[]{"_id", "name", "created"},
                null, null, null, null, "created ASC, _id ASC");
        try {
            while (c.moveToNext()) {
                out.add(new String[]{String.valueOf(c.getLong(0)), c.getString(1),
                        String.valueOf(c.getLong(2))});
            }
        } finally {
            c.close();
        }
        return out;
    }

    public long addGroup(String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("created", System.currentTimeMillis());
        return getWritableDatabase().insert("groups", null, v);
    }

    public void renameGroup(long id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        getWritableDatabase().update("groups", v, "_id=?", new String[]{String.valueOf(id)});
    }

    public void deleteGroup(long id) {
        getWritableDatabase().delete("groups", "_id=?", new String[]{String.valueOf(id)});
        ContentValues v = new ContentValues();
        v.put("group_id", 0);
        getWritableDatabase().update("books", v, "group_id=?", new String[]{String.valueOf(id)});
    }

    /** 取分组内前 n 本书的封面，用于文件夹卡片拼接 */
    public List<String> groupCovers(long id, int n) {
        List<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("books", new String[]{"cover"}, "group_id=?",
                new String[]{String.valueOf(id)}, null, null, "lastread DESC, added DESC");
        try {
            while (c.moveToNext() && out.size() < n) {
                String cover = c.isNull(0) ? null : c.getString(0);
                if (cover != null && !cover.isEmpty()) out.add(cover);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 每个分组内书籍的最近活跃时间（读过取 lastread，没读过取 added） */
    public java.util.Map<Long, Long> groupTimestamps() {
        java.util.Map<Long, Long> m = new java.util.HashMap<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT group_id, MAX(CASE WHEN lastread>0 THEN lastread ELSE added END) "
                        + "FROM books GROUP BY group_id", null);
        try {
            while (c.moveToNext()) m.put(c.getLong(0), c.getLong(1));
        } finally {
            c.close();
        }
        return m;
    }

    public int countInGroup(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM books WHERE group_id=?", new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public void setPinned(long id, boolean pinned) {
        ContentValues v = new ContentValues();
        v.put("pinned", pinned ? 1 : 0);
        getWritableDatabase().update("books", v, "_id=?", new String[]{String.valueOf(id)});
    }

    public void updateTitle(long id, String title) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        getWritableDatabase().update("books", v, "_id=?", new String[]{String.valueOf(id)});
    }

    public void updateCover(long id, String coverPath) {
        ContentValues v = new ContentValues();
        v.put("cover", coverPath);
        getWritableDatabase().update("books", v, "_id=?", new String[]{String.valueOf(id)});
    }

    public void touch(long id) {
        ContentValues v = new ContentValues();
        v.put("lastread", System.currentTimeMillis());
        getWritableDatabase().update("books", v, "_id=?", new String[]{String.valueOf(id)});
    }

    public void delete(long id) {
        getWritableDatabase().delete("books", "_id=?", new String[]{String.valueOf(id)});
    }

    private static Book read(Cursor c) {
        Book b = new Book();
        b.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        b.title = str(c, "title");
        b.author = str(c, "author");
        b.path = str(c, "path");
        b.coverPath = c.isNull(c.getColumnIndexOrThrow("cover")) ? null : str(c, "cover");
        b.bytes = c.getLong(c.getColumnIndexOrThrow("bytes"));
        b.chapterCount = c.getInt(c.getColumnIndexOrThrow("chapters"));
        b.chapter = c.getInt(c.getColumnIndexOrThrow("chapter"));
        b.offset = c.getInt(c.getColumnIndexOrThrow("offset"));
        b.progress = c.getFloat(c.getColumnIndexOrThrow("progress"));
        b.addedAt = c.getLong(c.getColumnIndexOrThrow("added"));
        b.lastReadAt = c.getLong(c.getColumnIndexOrThrow("lastread"));
        int pi = c.getColumnIndex("pinned");
        b.pinned = pi >= 0 && c.getInt(pi) != 0;
        b.chapterTitle = str(c, "chapterTitle");
        int wi = c.getColumnIndex("words");
        b.words = wi >= 0 ? c.getInt(wi) : 0;
        int gi = c.getColumnIndex("group_id");
        b.groupId = gi >= 0 ? c.getLong(gi) : 0;
        return b;
    }

    private static String str(Cursor c, String col) {
        String s = c.getString(c.getColumnIndexOrThrow(col));
        return s == null ? "" : s;
    }
}
