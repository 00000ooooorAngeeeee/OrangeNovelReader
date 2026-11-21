package com.orange.novel.reader.ui;

import android.content.Context;
import android.util.AttributeSet;

/** 固定 3:4.2 比例的封面，让书架栅格对齐。 */
public class CoverImageView extends androidx.appcompat.widget.AppCompatImageView {

    public CoverImageView(Context c) {
        super(c);
    }

    public CoverImageView(Context c, AttributeSet a) {
        super(c, a);
    }

    public CoverImageView(Context c, AttributeSet a, int defStyle) {
        super(c, a, defStyle);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        if (w <= 0) w = 320;
        int h = Math.round(w * 1.40f);
        setMeasuredDimension(w, h);
    }
}
