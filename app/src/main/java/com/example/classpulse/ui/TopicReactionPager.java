package com.example.classpulse.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import androidx.viewpager.widget.ViewPager;

/** Lets reaction pages grow with longer titles and the user's font size. */
public final class TopicReactionPager extends ViewPager {
    public TopicReactionPager(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int pageWidth = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        int height = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(Math.max(0, pageWidth), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            height = Math.max(height, child.getMeasuredHeight());
        }
        height += getPaddingTop() + getPaddingBottom();
        if (height > 0 && height != getMeasuredHeight())
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
