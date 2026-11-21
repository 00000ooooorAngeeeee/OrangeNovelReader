package com.orange.novel.reader.reader;

/** 阅读区排版参数（随屏幕宽度与系统字号生成）。 */
public class ReaderStyle {

    public float density = 3f;
    public int width = 1080;
    public int height = 1800;
    public float textSize = 54f;
    public float lineMult = 1.62f;
    /** 段间距系数（相对字号） */
    public float paraMult = 0.70f;

    public int colorText = 0xFFDCDCDE;
    public int colorTitle = 0xFFF3F3F4;
    public int colorQuote = 0xFFB4B4B8;

    public float dp(float v) {
        return v * density;
    }
}
