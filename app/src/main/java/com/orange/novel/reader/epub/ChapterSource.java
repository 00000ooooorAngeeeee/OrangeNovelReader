package com.orange.novel.reader.epub;

import java.util.ArrayList;
import java.util.List;

/** 一章解析后的段落集合（与排版无关，可缓存）。 */
public class ChapterSource {
    public final List<HtmlBlocks.Para> paras = new ArrayList<>();
    public int charCount;

    public static ChapterSource build(String html, String baseDir) {
        ChapterSource s = new ChapterSource();
        List<HtmlBlocks.Para> list = HtmlBlocks.parse(html, baseDir);
        s.paras.addAll(list);
        int n = 0;
        for (HtmlBlocks.Para p : list) {
            if (p.text != null) n += p.text.length();
        }
        s.charCount = n;
        return s;
    }
}
