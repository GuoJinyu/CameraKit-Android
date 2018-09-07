package com.wonderkiln.camerakit;

import android.hardware.Camera;
import android.support.annotation.Nullable;

import java.io.File;

abstract class CameraImpl {

    protected final EventDispatcher mEventDispatcher;
    protected final PreviewImpl mPreview;

    CameraImpl(EventDispatcher eventDispatcher, PreviewImpl preview) {
        mEventDispatcher = eventDispatcher;
        mPreview = preview;
    }

    abstract void start();
    abstract void stop();

    abstract void setDisplayAndDeviceOrientation(int displayOrientation, int deviceOrientation);

    abstract void setFacing(@Facing int facing);
    abstract void setFlash(@Flash int flash);
    abstract void setFocus(@Focus int focus);
    abstract void setMethod(@CaptureMethod int method);

    abstract void setVideoQuality(@VideoQuality int videoQuality);
    abstract void setVideoBitRate(int videoBitRate);
    abstract void setLockVideoAspectRatio(boolean lockVideoAspectRatio);

    abstract void setZoom(float zoomFactor);
    abstract void setZoomDirectly(float zoom);
    abstract void modifyZoom(float modifier);
    abstract void setZoom(boolean isZoomIn);
    abstract void setFocusArea(float x, float y);

    abstract void captureImage(ImageCapturedCallback callback);
    interface ImageCapturedCallback {
        void imageCaptured(byte[] jpeg);
    }

    abstract void captureVideo(File videoFile, VideoCapturedCallback callback);
    interface VideoCapturedCallback {
        void videoCaptured(File file);
    }

    abstract void stopVideo();

    abstract Size getCaptureResolution();
    abstract Size getVideoResolution();
    abstract Size getPreviewResolution();
    abstract boolean isCameraOpened();
    abstract boolean frontCameraOnly();

    @Nullable
    abstract CameraProperties getCameraProperties();

    abstract void setPreviewCallback(Camera.PreviewCallback callback);

    abstract int getPreviewRotation();

    abstract void setRequestedFps(float requestedFps);

    abstract void setPreviewResolution(int width,int height);
    abstract void setCaptureResolution(int width,int height);

    abstract void setTapToAutofocusListener(Camera.AutoFocusCallback callback);

}
