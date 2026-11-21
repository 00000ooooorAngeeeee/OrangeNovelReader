package com.orange.novel.reader.db;

public class Book {
    public long id;
    public String title = "";
    public String author = "";
    public String path = "";
    public String coverPath;
    public long bytes;
    public int chapterCount;
    public int chapter;
    public int offset;
    public float progress;
    public long addedAt;
    public long lastReadAt;
    public boolean pinned;
    /** 上次读到的章节名 */
    public String chapterTitle = "";
    /** 估算字数 */
    public long words;
    public long groupId;

    public boolean started() {
        return progress > 0.0005f || chapter > 0 || offset > 0;
    }
}
