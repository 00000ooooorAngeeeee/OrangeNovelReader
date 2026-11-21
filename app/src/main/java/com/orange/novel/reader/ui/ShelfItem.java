package com.orange.novel.reader.ui;

import com.orange.novel.reader.db.Book;

import java.util.ArrayList;
import java.util.List;

/** 书架条目：一本书，或者一个分组（文件夹卡片） */
public class ShelfItem {

    public static final int TYPE_BOOK = 0;
    public static final int TYPE_GROUP = 1;

    public final int type;
    public Book book;
    public long groupId;
    public String groupName;
    public int groupCount;
    public final List<String> covers = new ArrayList<>();
    /** 排序用的最近活跃时间：书取 lastread/added，分组取组内最大值 */
    public long sortTs;

    private ShelfItem(int type) {
        this.type = type;
    }

    public static ShelfItem book(Book b) {
        ShelfItem i = new ShelfItem(TYPE_BOOK);
        i.book = b;
        i.sortTs = b.lastReadAt > 0 ? b.lastReadAt : b.addedAt;
        return i;
    }

    public static ShelfItem group(long id, String name, int count, List<String> covers, long sortTs) {
        ShelfItem i = new ShelfItem(TYPE_GROUP);
        i.groupId = id;
        i.groupName = name;
        i.groupCount = count;
        i.sortTs = sortTs;
        if (covers != null) i.covers.addAll(covers);
        return i;
    }
}
