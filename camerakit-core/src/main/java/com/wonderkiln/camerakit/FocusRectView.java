package com.wonderkiln.camerakit;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.View;

public class FocusRectView extends View {

    private final Paint paint;
    private Rect frame;
    private int lineWidth;
    private int cornerLength;

    public FocusRectView(Context context) {
        super(context);
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        setAlpha(0);
    }

    public FocusRectView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        setAlpha(0);
    }

    public FocusRectView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        setAlpha(0);
    }

    @SuppressLint("DrawAllocation")
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        frame = new Rect(getLeft(),getTop(),getRight(),getBottom());
        lineWidth = getWidth()/50;
        cornerLength = getWidth()/3;
        //↖️
        canvas.drawRect(frame.left, frame.top, frame.left + lineWidth, frame.top + cornerLength, paint);
        canvas.drawRect(frame.left, frame.top, frame.left + cornerLength, frame.top + lineWidth, paint);
        //↗️
        canvas.drawRect(frame.right - cornerLength, frame.top, frame.right, frame.top + lineWidth, paint);
        canvas.drawRect(frame.right - lineWidth, frame.top, frame.right, frame.top + cornerLength, paint);
        //↙️
        canvas.drawRect(frame.left, frame.bottom - cornerLength, frame.left + lineWidth, frame.bottom, paint);
        canvas.drawRect(frame.left, frame.bottom - lineWidth, frame.left + cornerLength, frame.bottom, paint);
        //↘️
        canvas.drawRect(frame.right - lineWidth, frame.bottom - cornerLength, frame.right, frame.bottom, paint);
        canvas.drawRect(frame.right - cornerLength, frame.bottom - lineWidth, frame.right, frame.bottom, paint);
    }

    public void setColor(int color) {
        paint.setColor(color);
    }
}
