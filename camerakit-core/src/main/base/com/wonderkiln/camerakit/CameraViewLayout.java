package com.wonderkiln.camerakit;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.FrameLayout;

import com.wonderkiln.camerakit.core.BuildConfig;

public abstract class CameraViewLayout extends FrameLayout {

    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetector gestureDetector;
    private float zoom = 0;

    public CameraViewLayout(@NonNull Context context) {
        this(context, null);
    }

    public CameraViewLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CameraViewLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        gestureDetector = new GestureDetector(context, onGestureListener);
        scaleGestureDetector = new ScaleGestureDetector(context, onScaleGestureListener);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        if (!"egg".equals(BuildConfig.FLAVOR)) {
            scaleGestureDetector.onTouchEvent(event);
        }
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true;
    }

    protected abstract CameraImpl getCameraImpl();

    protected abstract PreviewImpl getPreviewImpl();

    protected abstract void onZoom(float zoom, boolean start);

    protected abstract void onZoomDirectly(float zoom);


    protected abstract void onTapToFocus(float x, float y);

    protected abstract void onToggleFacing();

    private GestureDetector.SimpleOnGestureListener onGestureListener = new GestureDetector.SimpleOnGestureListener() {

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if ("egg".equals(BuildConfig.FLAVOR)) {
                if (zoom == 0) {
                    zoom = 0.2f;
                } else if (zoom == 0.2f) {
                    zoom = 0.6f;
                } else {
                    zoom = 0;
                }
                onZoomDirectly(zoom);
            } else {
                onToggleFacing();
            }
            return super.onDoubleTap(e);
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            onTapToFocus(e.getX() / (float) getWidth(), e.getY() / (float) getHeight());
            return super.onSingleTapConfirmed(e);
        }
    };

    protected void resetZoomDirectly() {
        zoom = 0;
    }

    private ScaleGestureDetector.OnScaleGestureListener onScaleGestureListener = new ScaleGestureDetector.OnScaleGestureListener() {

        @Override
        public boolean onScale(ScaleGestureDetector scaleGestureDetector) {
            onZoom(scaleGestureDetector.getScaleFactor() * 2, false);
            return true;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector scaleGestureDetector) {
            onZoom(scaleGestureDetector.getScaleFactor() * 2, true);
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector scaleGestureDetector) {
        }

    };

}
