package com.modu.reader.epub;

public class EpubBook {
    public static class Chapter {
        public int index;
        public String path;
        public String title;
        public long bytes;
        public boolean body = true;
        public String volume;
    }
}
