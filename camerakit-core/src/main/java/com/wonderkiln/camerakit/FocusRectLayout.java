package com.wonderkiln.camerakit;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Color;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.LayoutInflater;

import com.wonderkiln.camerakit.core.R;

public class FocusRectLayout extends FocusLayout {

    private FocusRectView mFocusRectView;

    public FocusRectLayout(@NonNull Context context) {
        this(context, null);
    }

    public FocusRectLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater.from(getContext()).inflate(R.layout.layout_focus_rect, this);
        mFocusRectView = findViewById(R.id.view_focus_rect);
    }

    @Override
    public void focus(float mx, float my) {
        mx *= getWidth();
        my *= getHeight();
        int x = (int) (mx - mFocusRectView.getWidth() / 2);
        int y = (int) (my - mFocusRectView.getWidth() / 2);
        mFocusRectView.setColor(Color.WHITE);
        mFocusRectView.invalidate();
        mFocusRectView.setTranslationX(x);
        mFocusRectView.setTranslationY(y);

        mFocusRectView.animate().setListener(null).cancel();

        mFocusRectView.setScaleX(1.36f);
        mFocusRectView.setScaleY(1.36f);
        mFocusRectView.setAlpha(1f);

        mFocusRectView.animate().scaleX(1).scaleY(1).setStartDelay(0).setDuration(330)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                    }
                }).start();
    }

    @Override
    public void focused(boolean focused) {
        int color = focused ? 0xFF08D7E7 : 0xFF08D7E7;
        mFocusRectView.setColor(color);
        mFocusRectView.invalidate();
        mFocusRectView.animate().alpha(0).setStartDelay(2000).setDuration(800).setListener(null).start();
    }

}
