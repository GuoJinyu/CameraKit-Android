package com.wonderkiln.camerakit;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.widget.FrameLayout;

public abstract class FocusLayout extends FrameLayout {

    public FocusLayout(@NonNull Context context) {
        super(context);
    }

    public FocusLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public FocusLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }


    public abstract void focus(float mx, float my);

    public abstract void focused(boolean focused);

}
