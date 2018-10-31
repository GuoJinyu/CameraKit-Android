package com.wonderkiln.camerakit;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.wonderkiln.camerakit.CameraKit.Constants.FLASH_OFF;
import static com.wonderkiln.camerakit.CameraKit.Constants.FOCUS_CONTINUOUS;
import static com.wonderkiln.camerakit.CameraKit.Constants.FOCUS_OFF;
import static com.wonderkiln.camerakit.CameraKit.Constants.FOCUS_TAP;
import static com.wonderkiln.camerakit.CameraKit.Constants.METHOD_STANDARD;
import static com.wonderkiln.camerakit.CameraKit.Constants.METHOD_STILL;

@SuppressWarnings("deprecation")
public class Camera1 extends CameraImpl {

    private static final String TAG = Camera1.class.getSimpleName();

    private static final int FOCUS_AREA_SIZE_DEFAULT = 300;
    private static final int FOCUS_METERING_AREA_WEIGHT_DEFAULT = 1000;
    private static final int DELAY_MILLIS_BEFORE_RESETTING_FOCUS = 3000;
    private static final int PREVIEW_BUFFER_POOL_SIZE = 3;

    private int mCameraId;
    private Camera mCamera;
    private Camera.Parameters mCameraParameters;
    private CameraProperties mCameraProperties;
    private Camera.CameraInfo mCameraInfo;
    private Size mCaptureSize;
    private Size mVideoSize;
    private Size mPreviewSize;
    private MediaRecorder mMediaRecorder;
    private Camera.AutoFocusCallback mAutofocusCallback;
    private boolean capturingImage = false;

    private volatile boolean mShowingPreview;
    private boolean mRecording;
    private int mDisplayOrientation;
    private int mDeviceOrientation;

    @Facing
    private int mFacing;

    @Flash
    private int mFlash;

    @Focus
    private int mFocus;

    @CaptureMethod
    private int mMethod;

    @VideoQuality
    private int mVideoQuality;

    private int mVideoBitRate;

    private boolean mLockVideoAspectRatio;

    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Handler mHandler = new Handler();

    private float mZoom = 1.f;

    private VideoCapturedCallback mVideoCallback;

    private final Object mCameraLock = new Object();

    private byte[][] mBufferBytes;
    private Camera.PreviewCallback mPreviewCallback;

    private float mRequestedFps = 30.0f;

    private List<Camera.Area> curFocusArea;

    Camera1(EventDispatcher eventDispatcher, PreviewImpl preview) {
        super(eventDispatcher, preview);

        preview.setCallback(new PreviewImpl.Callback() {
            @Override
            public void onSurfaceChanged() {
                try {
                    synchronized (mCameraLock) {
                        if (mCamera != null) {
                            if (mShowingPreview) {
                                mCamera.stopPreview();
                                mShowingPreview = false;
                            }

                            setDisplayAndDeviceOrientation();
                            setupPreview();

                            if (!mShowingPreview) {
                                mCamera.startPreview();
                                mShowingPreview = true;
                            }
                        }
                    }
                } catch (Exception e) {
                    //do nothing.
                }
            }
        });

        mCameraInfo = new Camera.CameraInfo();
    }

    // CameraImpl:

    @Override
    void start() {
        Log.d(TAG, "starting");
        try {
            setFacing(mFacing);
            openCamera();
            if (mCamera != null && mPreview.isReady()) {
                Log.d(TAG, "mCamera != null && mPreview.isReady()");
                setDisplayAndDeviceOrientation();
                setupPreview();
                mCamera.startPreview();
                mShowingPreview = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    void stop() {
        Log.d(TAG, "stopping");
        try {
            mHandler.removeCallbacksAndMessages(null);
            if (mCamera != null) {
                Log.d(TAG, "mCamera != null");
                try {
                    mCamera.stopPreview();
                } catch (Exception e) {
                    notifyErrorListener(e);
                }
            }
            mShowingPreview = false;

            releaseMediaRecorder();
            releaseCamera();
        } catch (Exception e) {
            //do nothing.
        }
    }

    void setDisplayAndDeviceOrientation() {
        setDisplayAndDeviceOrientation(this.mDisplayOrientation, this.mDeviceOrientation);
    }

    @Override
    void setDisplayAndDeviceOrientation(int displayOrientation, int deviceOrientation) {
        this.mDisplayOrientation = displayOrientation;
        this.mDeviceOrientation = deviceOrientation;

        synchronized (mCameraLock) {
            if (isCameraOpened()) {
                try {
                    mCamera.setDisplayOrientation(calculatePreviewRotation());
                } catch (RuntimeException e) {
                    // Camera is released. Ignore. Orientations are still valid in local member fields
                    // so next time camera starts it will have correct configuration.
                }
            }
        }
    }

    @Override
    void setFacing(@Facing int facing) {
        synchronized (mCameraLock) {
            int internalFacing = new ConstantMapper.Facing(facing).map();
            if (internalFacing == -1) {
                return;
            }

            for (int i = 0, count = Camera.getNumberOfCameras(); i < count; i++) {
                Camera.getCameraInfo(i, mCameraInfo);
                if (mCameraInfo.facing == internalFacing) {
                    mCameraId = i;
                    mFacing = facing;
                    break;
                }
            }

            if (mFacing == facing && isCameraOpened()) {
                stop();
                start();
            }
        }
    }

    @Override
    void setFlash(@Flash int flash) {
        synchronized (mCameraLock) {
            if (mCameraParameters != null) {
                List<String> flashes = mCameraParameters.getSupportedFlashModes();
                String internalFlash = new ConstantMapper.Flash(flash).map();
                if (flashes != null && flashes.contains(internalFlash)) {
                    mCameraParameters.setFlashMode(internalFlash);
                    mFlash = flash;
                } else {
                    String currentFlash = new ConstantMapper.Flash(mFlash).map();
                    if (flashes == null || !flashes.contains(currentFlash)) {
                        mCameraParameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                        mFlash = FLASH_OFF;
                    }
                }

                setParameters(mCameraParameters);
            } else {
                mFlash = flash;
            }
        }
    }

    @Override
    void setFocus(@Focus int focus) {
        synchronized (mCameraLock) {
            this.mFocus = focus;
            switch (focus) {
                case FOCUS_CONTINUOUS:
                    if (mCameraParameters != null) {
                        final List<String> modes = mCameraParameters.getSupportedFocusModes();
                        if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                            mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                        } else {
                            setFocus(FOCUS_OFF);
                        }
                    }
                    break;

                case FOCUS_TAP:
                    if (mCameraParameters != null) {
                        final List<String> modes = mCameraParameters.getSupportedFocusModes();
                        if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                            mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                        }
                    }
                    break;

                case FOCUS_OFF:
                    if (mCameraParameters != null) {
                        final List<String> modes = mCameraParameters.getSupportedFocusModes();
                        if (modes.contains(Camera.Parameters.FOCUS_MODE_FIXED)) {
                            mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);
                        } else if (modes.contains(Camera.Parameters.FOCUS_MODE_INFINITY)) {
                            mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
                        } else {
                            mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                        }
                    }
                    break;
            }
        }
    }

    @Override
    void setMethod(@CaptureMethod int method) {
        this.mMethod = method;
    }

    @Override
    void setVideoQuality(int videoQuality) {
        this.mVideoQuality = videoQuality;
    }

    @Override
    void setVideoBitRate(int videoBitRate) {
        this.mVideoBitRate = videoBitRate;
    }

    @Override
    void setZoom(float zoomFactor) {
        Log.d(TAG, zoomFactor + " zoomFactor");
        synchronized (mCameraLock) {
            this.mZoom = zoomFactor;
            if (zoomFactor <= 1) {
                mZoom = 1;
            } else {
                mZoom = zoomFactor;
            }

            if (mCameraParameters != null && mCameraParameters.isZoomSupported()) {
                int zoomPercent = (int) (mZoom * 100);
                mCameraParameters.setZoom(getZoomForPercent(zoomPercent));
                Log.d(TAG, getZoomForPercent(zoomPercent) + " getZoomForPercent(zoomPercent)");
                setParameters(mCameraParameters);
                float maxZoom = mCameraParameters.getZoomRatios().get(mCameraParameters.getZoomRatios().size() - 1) / 100f;
                if (mZoom > maxZoom) mZoom = maxZoom;
                Log.d(TAG, mZoom + " mZoom");

            }
        }
    }

    @Override
    void modifyZoom(float modifier) {
        synchronized (mCameraLock) {
            setZoom(this.mZoom * modifier);
        }
    }

    @Override
    void setZoom(boolean isZoomIn) {
        if (mCamera != null && mCameraParameters != null) {
            if (mCameraParameters.isZoomSupported()) {
                int maxZoom = mCameraParameters.getMaxZoom();
                int zoom = mCameraParameters.getZoom();
                if (isZoomIn && zoom < maxZoom) {
                    zoom++;
                } else if (zoom > 0) {
                    zoom--;
                }
                mCameraParameters.setZoom(zoom);
                setParameters(mCameraParameters);
            } else {
                Log.i(TAG, "zoom not supported");
            }
        }
    }

    private int getZoomForPercent(int zoomPercent) {
        List<Integer> zoomRatios = mCameraParameters.getZoomRatios();
        int lowerIndex = -1;
        int upperIndex = -1;

        for (int i = 0; i < zoomRatios.size(); i++) {
            if (zoomRatios.get(i) < zoomPercent) {
                lowerIndex = i;
            } else if (zoomRatios.get(i) > zoomPercent) {
                upperIndex = i;
                break;
            }
        }

        if (lowerIndex < 0) {
            return 0;
        }

        if (lowerIndex + 1 == upperIndex) {
            return lowerIndex;
        }

        if (upperIndex >= 0) {
            return upperIndex;
        }

        return zoomRatios.size() - 1;
    }

    @Override
    void setFocusArea(float x, float y) {
        try {
            synchronized (mCameraLock) {
                if (mCamera != null && mShowingPreview && mCameraParameters != null) {
//                    Camera.Parameters parameters = getCameraParameters();
//                    if (parameters == null) return;

                    String focusMode = mCameraParameters.getFocusMode();
                    Rect rect = calculateFocusArea(x, y);
                    List<Camera.Area> meteringAreas = new ArrayList<>();
                    meteringAreas.add(new Camera.Area(rect, getFocusMeteringAreaWeight()));
                    curFocusArea = meteringAreas;
                    if (mCameraParameters.getMaxNumFocusAreas() != 0 && focusMode != null &&
                            (focusMode.equals(Camera.Parameters.FOCUS_MODE_AUTO) ||
                                    focusMode.equals(Camera.Parameters.FOCUS_MODE_MACRO) ||
                                    focusMode.equals(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) ||
                                    focusMode.equals(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))
                            ) {
                        mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                        mCameraParameters.setFocusAreas(meteringAreas);
                        if (mCameraParameters.getMaxNumMeteringAreas() > 0) {
                            mCameraParameters.setMeteringAreas(meteringAreas);
                        }
                        if (!mCameraParameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                            return; //cannot autoFocus
                        }
                        setParameters(mCameraParameters);
                        mCamera.autoFocus(new Camera.AutoFocusCallback() {
                            @Override
                            public void onAutoFocus(boolean success, Camera camera) {
                                resetFocus(success, camera);
                            }
                        });
                    } else if (mCameraParameters.getMaxNumMeteringAreas() > 0) {
                        if (!mCameraParameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                            return; //cannot autoFocus
                        }
                        mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                        mCameraParameters.setFocusAreas(meteringAreas);
                        mCameraParameters.setMeteringAreas(meteringAreas);

                        setParameters(mCameraParameters);
                        mCamera.autoFocus(new Camera.AutoFocusCallback() {
                            @Override
                            public void onAutoFocus(boolean success, Camera camera) {
                                resetFocus(success, camera);
                            }
                        });
                    } else {
                        mCamera.autoFocus(new Camera.AutoFocusCallback() {
                            @Override
                            public void onAutoFocus(boolean success, Camera camera) {
                                if (mAutofocusCallback != null) {
                                    mAutofocusCallback.onAutoFocus(success, camera);
                                }
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            //do nothing.
        }
    }

    @Override
    void setZoomDirectly(float zoom) {
        if (mCameraParameters != null && mCameraParameters.isZoomSupported()) {
            int zoomReal = (int) (mCameraParameters.getMaxZoom() * zoom);
            if (zoomReal > mCameraParameters.getMaxZoom()) {
                zoomReal = mCameraParameters.getMaxZoom();
            }
            mCameraParameters.setZoom(zoomReal);
            setParameters(mCameraParameters);
        }
    }

    @Override
    void setLockVideoAspectRatio(boolean lockVideoAspectRatio) {
        this.mLockVideoAspectRatio = lockVideoAspectRatio;
    }

    @Override
    void captureImage(final ImageCapturedCallback callback) {
        if (!mShowingPreview) {
            Log.d(TAG, "mShowingPreview:" + mShowingPreview);
            //If camera hasn't start preview, then return.
            callback.imageCaptured(null);
            return;
        }
        try {
            switch (mMethod) {
                case METHOD_STANDARD:
                    Log.d(TAG, "METHOD_STANDARD");
                    synchronized (mCameraLock) {
                        // Null check required for camera here as is briefly null when View is detached
                        Log.d(TAG, "mCameraLock");
                        if (!capturingImage && mCamera != null && mCameraParameters != null) {

                            // Set boolean to wait for image callback
                            Log.w(TAG, "capturingImage to be true");
                            capturingImage = true;

                            // Set the captureRotation right before taking a picture so it's accurate
                            int captureRotation = calculateCaptureRotation();
//                            mCameraParameters = getCameraParameters();
//                            if (mCameraParameters == null) return;
                            mCameraParameters.setRotation(captureRotation);
                            setParameters(mCameraParameters);
                            Log.d(TAG, "ready for takePicture");
                            mCamera.takePicture(null, null, null,
                                    new Camera.PictureCallback() {
                                        @Override
                                        public void onPictureTaken(byte[] data, Camera camera) {
                                            Log.d(TAG, "imageCaptured");
                                            callback.imageCaptured(data);
                                            // Reset capturing state to allow photos to be taken
                                            Log.w(TAG, "capturingImage to be false");
                                            capturingImage = false;
// for egg, do not need restart here
//                                            synchronized (mCameraLock) {
//                                                if (isCameraOpened()) {
//                                                    try {
//                                                        stop();
//                                                        start();
//                                                    } catch (Exception e) {
//                                                        notifyErrorListener(e);
//                                                    }
//                                                }
//                                            }
                                        }
                                    });
                            //}
                        } else {
                            Log.w(TAG, "Unable, waiting for picture to be taken");
                            if (capturingImage) {
                                Log.w(TAG, "capturingImage:" + capturingImage);
                            }
                            if (mCamera == null) {
                                Log.w(TAG, "mCamera:null");
                            }
                            callback.imageCaptured(null);
                        }
                        break;
                    }
                case METHOD_STILL:
                    synchronized (mCameraLock) {
                        mCamera.setOneShotPreviewCallback(new Camera.PreviewCallback() {
                            @Override
                            public void onPreviewFrame(byte[] data, Camera camera) {
                                Camera.Parameters parameters = camera.getParameters();
                                int width = parameters.getPreviewSize().width;
                                int height = parameters.getPreviewSize().height;
                                int rotation = calculateCaptureRotation();

                                YuvOperator yuvOperator = new YuvOperator(data, width, height);
                                yuvOperator.rotate(rotation);
                                data = yuvOperator.getYuvData();

                                int yuvOutputWidth = width;
                                int yuvOutputHeight = height;
                                if (rotation == 90 || rotation == 270) {
                                    yuvOutputWidth = height;
                                    yuvOutputHeight = width;
                                }

                                YuvImage yuvImage = new YuvImage(data, parameters.getPreviewFormat(), yuvOutputWidth, yuvOutputHeight, null);
                                ByteArrayOutputStream out = new ByteArrayOutputStream();
                                yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 100, out);
                                callback.imageCaptured(out.toByteArray());
                            }
                        });
                        break;
                    }
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (e.getMessage().equals("takePicture failed")) {
                Log.w(TAG, "takePicture failed,capturingImage to be true");
                capturingImage = false;
            }
            callback.imageCaptured(null);
        }
    }

    @Override
    void captureVideo(File videoFile, VideoCapturedCallback callback) {
        synchronized (mCameraLock) {
            try {
                if (prepareMediaRecorder(videoFile)) {
                    mMediaRecorder.start();
                    mRecording = true;
                    this.mVideoCallback = callback;
                } else {
                    releaseMediaRecorder();
                }
            } catch (IOException e) {
                releaseMediaRecorder();
            } catch (RuntimeException e) {
                releaseMediaRecorder();
            }
        }
    }

    @Override
    void stopVideo() {
        synchronized (mCameraLock) {
            if (mRecording) {
                File videoFile = getVideoFile();

                try {
                    mMediaRecorder.stop();
                    if (this.mVideoCallback != null) {
                        mVideoCallback.videoCaptured(videoFile);
                        mVideoCallback = null;
                    }
                } catch (RuntimeException e) {
                    videoFile.delete();
                }

                releaseMediaRecorder();
                mRecording = false;
            }

            stop();
            start();
        }
    }

    @Override
    Size getCaptureResolution() {
        if (mCaptureSize == null && mCameraParameters != null) {
//            if ("egg".equals(BuildConfig.FLAVOR)) {
//                // for egg
//                mCaptureSize = new Size(2592, 1944);
//                return mCaptureSize;
//            }
            TreeSet<Size> sizes = new TreeSet<>();
            for (Camera.Size size : mCameraParameters.getSupportedPictureSizes()) {
                sizes.add(new Size(size.width, size.height));
            }

            TreeSet<AspectRatio> aspectRatios = findCommonAspectRatios(
                    mCameraParameters.getSupportedPreviewSizes(),
                    mCameraParameters.getSupportedPictureSizes()
            );
            AspectRatio targetRatio = aspectRatios.size() > 0 ? aspectRatios.last() : null;

            Iterator<Size> descendingSizes = sizes.descendingIterator();
            Size size;
            final Size previewSize = getPreviewResolution();
            while (descendingSizes.hasNext()) {
                size = descendingSizes.next();
                if (targetRatio == null) {
                    mCaptureSize = size;
                    break;
                }
                if (targetRatio.matches(size)) {
                    //return 'capture size' that closest to 'preview size'
                    if (size.getWidth() >= previewSize.getHeight()
                            && size.getHeight() >= previewSize.getWidth()) {
                        mCaptureSize = size;
                    } else {
                        if (mCaptureSize == null) {
                            mCaptureSize = size;
                        }
                        break;
                    }
                }
            }
        }

        return mCaptureSize;
    }

    @Override
    Size getVideoResolution() {
        if (mVideoSize == null && mCameraParameters != null) {
            if (mCameraParameters.getSupportedVideoSizes() == null) {
                mVideoSize = getCaptureResolution();
                return mVideoSize;
            }

            TreeSet<Size> sizes = new TreeSet<>();
            for (Camera.Size size : mCameraParameters.getSupportedVideoSizes()) {
                sizes.add(new Size(size.width, size.height));
            }

            TreeSet<AspectRatio> aspectRatios = findCommonAspectRatios(
                    mCameraParameters.getSupportedPreviewSizes(),
                    mCameraParameters.getSupportedVideoSizes()
            );
            AspectRatio targetRatio = aspectRatios.size() > 0 ? aspectRatios.last() : null;

            Iterator<Size> descendingSizes = sizes.descendingIterator();
            Size size;
            while (descendingSizes.hasNext() && mVideoSize == null) {
                size = descendingSizes.next();
                if (targetRatio == null || targetRatio.matches(size)) {
                    mVideoSize = size;
                    break;
                }
            }
        }

        return mVideoSize;
    }

    @Override
    Size getPreviewResolution() {
        if (mPreviewSize == null && mCameraParameters != null) {
//            if ("egg".equals(BuildConfig.FLAVOR)) {
//                // for egg
//                mPreviewSize = new Size(1920, 1080);
//                return mPreviewSize;
//            }
            TreeSet<Size> sizes = new TreeSet<>();
            for (Camera.Size size : mCameraParameters.getSupportedPreviewSizes()) {
                sizes.add(new Size(size.width, size.height));
            }

            TreeSet<AspectRatio> aspectRatios = findCommonAspectRatios(
                    mCameraParameters.getSupportedPreviewSizes(),
                    mCameraParameters.getSupportedPictureSizes()
            );

            AspectRatio targetRatio = null;

            if (mLockVideoAspectRatio) {
                TreeSet<AspectRatio> videoAspectRatios = findCommonAspectRatios(
                        mCameraParameters.getSupportedPreviewSizes(),
                        mCameraParameters.getSupportedPictureSizes()
                );

                Iterator<AspectRatio> descendingIterator = aspectRatios.descendingIterator();
                while (targetRatio == null && descendingIterator.hasNext()) {
                    AspectRatio ratio = descendingIterator.next();
                    if (videoAspectRatios.contains(ratio)) {
                        targetRatio = ratio;
                    }
                }
            }

            if (targetRatio == null) {
                targetRatio = aspectRatios.size() > 0 ? aspectRatios.last() : null;
            }

            Iterator<Size> descendingSizes = sizes.descendingIterator();
            Size size;
            while (descendingSizes.hasNext()) {
                size = descendingSizes.next();
                if (targetRatio == null) {
                    mPreviewSize = size;
                    break;
                }
                if (targetRatio.matches(size)) {
                    //return 'preview size' that closest to screen size.
                    if (size.getWidth() >= CameraKit.Internal.screenHeight
                            && size.getHeight() >= CameraKit.Internal.screenWidth) {
                        mPreviewSize = size;
                    } else {
                        if (mPreviewSize == null) {
                            mPreviewSize = size;
                        }
                        break;
                    }
                }
            }
        }

        boolean invertPreviewSizes = (mCameraInfo.orientation + mDeviceOrientation) % 180 == 90;
        if (mPreviewSize != null && invertPreviewSizes) {
            return new Size(mPreviewSize.getHeight(), mPreviewSize.getWidth());
        }

        return mPreviewSize;
    }

    @Override
    boolean isCameraOpened() {
        return mCamera != null;
    }

    @Override
    boolean frontCameraOnly() {
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        Camera.getCameraInfo(0, cameraInfo);
        boolean isFrontCameraOnly = (Camera.getNumberOfCameras() == 1 && cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT);
        return isFrontCameraOnly;
    }

    @Nullable
    @Override
    CameraProperties getCameraProperties() {
        return mCameraProperties;
    }

    @Override
    void setPreviewCallback(Camera.PreviewCallback callback) {
        mPreviewCallback = callback;
    }

    @Override
    int getPreviewRotation() {
        return calculatePreviewRotation();
    }

    @Override
    public void setRequestedFps(float requestedFps) {
        mRequestedFps = requestedFps;
    }

    @Override
    void setPreviewResolution(int width, int height) {
        mPreviewSize = new Size(width, height);
    }

    @Override
    void setCaptureResolution(int width, int height) {
        mCaptureSize = new Size(width, height);
    }

    // Internal:

    private void openCamera() {
        synchronized (mCameraLock) {
            if (mCamera != null) {
                releaseCamera();
            }
            Log.d(TAG, "mCamera to be open in openCamera()");
            mCamera = Camera.open(mCameraId);
            mCameraParameters = mCamera.getParameters();

            collectCameraProperties();
            adjustCameraParameters();

            if (Build.VERSION.SDK_INT >= 16) {
                mCamera.setAutoFocusMoveCallback(new Camera.AutoFocusMoveCallback() {
                    @Override
                    public void onAutoFocusMoving(boolean b, Camera camera) {
                        CameraKitEvent event = new CameraKitEvent(CameraKitEvent.TYPE_FOCUS_MOVED);
                        event.getData().putBoolean("started", b);
                        mEventDispatcher.dispatch(event);
                    }
                });
            }

            mEventDispatcher.dispatch(new CameraKitEvent(CameraKitEvent.TYPE_CAMERA_OPEN));
        }
    }

    private void setupPreview() {
        synchronized (mCameraLock) {
            try {
                mCamera.reconnect();
                mCamera.setPreviewDisplay(mPreview.getSurfaceHolder());
                setupPreviewCallback();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void setupPreviewCallback() {
        if (mBufferBytes == null) {
            final int size = mPreviewSize.getWidth() * mPreviewSize.getHeight()
                    * ImageFormat.getBitsPerPixel(mCameraParameters.getPreviewFormat()) / 8;
            mBufferBytes = new byte[PREVIEW_BUFFER_POOL_SIZE][size];
        }
        for (byte[] buffer : mBufferBytes) {
            mCamera.addCallbackBuffer(buffer);
        }
        mCamera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(final byte[] data, final Camera camera) {
                if (mPreviewCallback != null) {
                    mPreviewCallback.onPreviewFrame(data, camera);
                }
                if (mCamera != null) {
                    mCamera.addCallbackBuffer(data);
                }
            }
        });
    }

    private void releaseCamera() {
        synchronized (mCameraLock) {
            if (mCamera != null) {
                mCamera.lock();
                mCamera.release();
                Log.d(TAG, "mCamera to be null in releaseCamera()");
                mCamera = null;
                mCameraParameters = null;
//                mPreviewSize = null;
//                mCaptureSize = null;
                mVideoSize = null;

                mEventDispatcher.dispatch(new CameraKitEvent(CameraKitEvent.TYPE_CAMERA_CLOSE));
            }
        }
    }

    private void setParameters(Camera.Parameters params) {
        try {
            if (mCamera != null)
                mCamera.setParameters(params);
        } catch (Exception e) {
            notifyErrorListener(e);
        }
    }

    private int calculatePreviewRotation() {
        if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            int result = (mCameraInfo.orientation + mDisplayOrientation) % 360;
            return (360 - result) % 360;
        } else {
            return (mCameraInfo.orientation - mDisplayOrientation + 360) % 360;
        }
    }

    private int calculateCaptureRotation() {
        int captureRotation = 0;
        if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            captureRotation = (mCameraInfo.orientation + mDisplayOrientation) % 360;
        } else {  // back-facing camera
            captureRotation = (mCameraInfo.orientation - mDisplayOrientation + 360) % 360;
        }

        // Accommodate for any extra device rotation relative to fixed screen orientations
        // (e.g. activity fixed in portrait, but user took photo/video in landscape)
        if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            captureRotation = ((captureRotation - (mDisplayOrientation - mDeviceOrientation)) + 360) % 360;
        } else {  // back-facing camera
            captureRotation = (captureRotation + (mDisplayOrientation - mDeviceOrientation) + 360) % 360;
        }

        return captureRotation;
    }

    private void notifyErrorListener(@NonNull final String details) {
        CameraKitError error = new CameraKitError();
        error.setMessage(details);
        mEventDispatcher.dispatch(error);
    }

    private void notifyErrorListener(@NonNull final Exception e) {
        CameraKitError error = new CameraKitError(e);
        mEventDispatcher.dispatch(error);
    }

    private Camera.Parameters getCameraParameters() {
        if (mCamera != null) {
            try {
                return mCamera.getParameters();
            } catch (Exception e) {
                return null;
            }
        }

        return null;
    }

    private void adjustCameraParameters() {
        synchronized (mCameraLock) {
            if (mShowingPreview) {
                mCamera.stopPreview();
            }

            adjustCameraParameters(0);

            if (mShowingPreview) {
                mCamera.startPreview();
            }
        }
    }

    private void adjustCameraParameters(int currentTry) {
        boolean invertPreviewSizes = (mCameraInfo.orientation + mDeviceOrientation) % 180 == 90;
        boolean haveToReadjust = false;
        Camera.Parameters resolutionLess = mCamera.getParameters();

        if (getPreviewResolution() != null) {
            if (mDeviceOrientation == 0 || mDeviceOrientation == 180) {
                mPreview.setPreviewParameters(
                        getPreviewResolution().getWidth(),
                        getPreviewResolution().getHeight(),
                        mCameraParameters.getPreviewFormat()
                );
            } else {
                mPreview.setPreviewParameters(
                        getPreviewResolution().getHeight(),
                        getPreviewResolution().getWidth(),
                        mCameraParameters.getPreviewFormat()
                );
            }

            mCameraParameters.setPreviewSize(
                    invertPreviewSizes ? getPreviewResolution().getHeight() : getPreviewResolution().getWidth(),
                    invertPreviewSizes ? getPreviewResolution().getWidth() : getPreviewResolution().getHeight()
            );


//            final int[] previewFpsRange = selectPreviewFpsRange(mCamera, mRequestedFps);
//            if (previewFpsRange != null) {
//                mCameraParameters.setPreviewFpsRange(
//                        previewFpsRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
//                        previewFpsRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);
//            }

            try {
                mCamera.setParameters(mCameraParameters);
                resolutionLess = mCameraParameters;
            } catch (Exception e) {
                notifyErrorListener(e);
                // Some phones can't set parameters that camerakit has chosen, so fallback to defaults
                mCameraParameters = resolutionLess;
            }
        } else {
            haveToReadjust = true;
        }

        if (getCaptureResolution() != null) {
            mCameraParameters.setPictureSize(
                    getCaptureResolution().getWidth(),
                    getCaptureResolution().getHeight()
            );

            try {
                mCamera.setParameters(mCameraParameters);
                resolutionLess = mCameraParameters;
            } catch (Exception e) {
                notifyErrorListener(e);
                //Some phones can't set parameters that camerakit has chosen, so fallback to defaults
                mCameraParameters = resolutionLess;
            }
        } else {
            haveToReadjust = true;
        }

        int rotation = calculateCaptureRotation();
        mCameraParameters.setRotation(rotation);

        setFocus(mFocus);

        try {
            setFlash(mFlash);
        } catch (Exception e) {
            notifyErrorListener(e);
        }

        if (mCameraParameters.isZoomSupported()) {
            setZoom(mZoom);
        }

//        mCameraParameters.setPreviewSize(1920, 1080);
//        mCameraParameters.setPictureSize(1920, 1080);

        setParameters(mCameraParameters);

        if (haveToReadjust && currentTry < 100) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            notifyErrorListener(String.format("retryAdjustParam Failed, attempt #: %d", currentTry));
            adjustCameraParameters(currentTry + 1);
        }
    }

    private void collectCameraProperties() {
        mCameraProperties = new CameraProperties(mCameraParameters.getVerticalViewAngle(),
                mCameraParameters.getHorizontalViewAngle());
    }

    private TreeSet<AspectRatio> findCommonAspectRatios(List<Camera.Size> previewSizes, List<Camera.Size> pictureSizes) {
        Set<AspectRatio> previewAspectRatios = new HashSet<>();
        //Log.d("acker", "预览size");
        for (Camera.Size size : previewSizes) {
            //Log.d("acker", size.width + "x" + size.height);
            AspectRatio deviceRatio = AspectRatio.of(CameraKit.Internal.screenHeight, CameraKit.Internal.screenWidth);
            AspectRatio previewRatio = AspectRatio.of(size.width, size.height);
            if (deviceRatio.equals(previewRatio)) {
                previewAspectRatios.add(previewRatio);
            }
        }

        Set<AspectRatio> captureAspectRatios = new HashSet<>();
        //Log.d("acker", "照片size");
        for (Camera.Size size : pictureSizes) {
            //Log.d("acker", size.width + "x" + size.height);
            captureAspectRatios.add(AspectRatio.of(size.width, size.height));
        }

        TreeSet<AspectRatio> output = new TreeSet<>();
        if (previewAspectRatios.size() == 0) {
            // if no common aspect ratios
            Camera.Size maxSize = previewSizes.get(0);
            AspectRatio maxPreviewAspectRatio = AspectRatio.of(maxSize.width, maxSize.height);
            for (AspectRatio aspectRatio : captureAspectRatios) {
                if (aspectRatio.equals(maxPreviewAspectRatio)) {
                    output.add(aspectRatio);
                }
            }
        } else {
            // if common aspect ratios exist
            for (AspectRatio aspectRatio : previewAspectRatios) {
                if (captureAspectRatios.contains(aspectRatio)) {
                    output.add(aspectRatio);
                }
            }
        }

        return output;
    }

    private boolean prepareMediaRecorder(File videoFile) throws IOException {
        synchronized (mCameraLock) {
            mCamera.unlock();

            mMediaRecorder = new MediaRecorder();
            mMediaRecorder.setCamera(mCamera);

            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

            CamcorderProfile profile = getCamcorderProfile(mVideoQuality);
            mMediaRecorder.setProfile(profile);

            if (videoFile == null) videoFile = getVideoFile();
            if (videoFile == null) {
                return false;
            }

            mMediaRecorder.setOutputFile(videoFile.getPath());
            mMediaRecorder.setPreviewDisplay(mPreview.getSurface());
            mMediaRecorder.setOrientationHint(calculateCaptureRotation());

            try {
                mMediaRecorder.prepare();
            } catch (IllegalStateException e) {
                releaseMediaRecorder();
                return false;
            } catch (IOException e) {
                releaseMediaRecorder();
                return false;
            }

            return true;
        }
    }

    private void releaseMediaRecorder() {
        synchronized (mCameraLock) {
            if (mMediaRecorder != null) {
                mMediaRecorder.reset();
                mMediaRecorder.release();
                mMediaRecorder = null;
                mCamera.lock();
            }
        }
    }

    private File getVideoFile() {
        if (!Environment.getExternalStorageState().equalsIgnoreCase(Environment.MEDIA_MOUNTED)) {
            return null;
        }

        File mediaStorageDir = new File(Environment.getExternalStorageDirectory(), "Camera");

        if (!mediaStorageDir.exists()) {
            if (!mediaStorageDir.mkdirs()) {
                return null;
            }
        }

        return new File(mediaStorageDir.getPath() + File.separator + "video.mp4");
    }

    private CamcorderProfile getCamcorderProfile(@VideoQuality int videoQuality) {
        CamcorderProfile camcorderProfile = null;
        switch (videoQuality) {
            case CameraKit.Constants.VIDEO_QUALITY_QVGA:
                if (CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_QVGA)) {
                    camcorderProfile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_QVGA);
                } else {
                    camcorderProfile = getCamcorderProfile(CameraKit.Constants.VIDEO_QUALITY_LOWEST);
                }
                break;

            case CameraKit.Constants.VIDEO_QUALITY_480P:
                if (CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_480P)) {
                    camcorderProfile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_480P);
                } else {
                    camcorderProfile = getCamcorderProfile(CameraKit.Constants.VIDEO_QUALITY_QVGA);
                }
                break;

            case CameraKit.Constants.VIDEO_QUALITY_720P:
                if (CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_720P)) {
                    camcorderProfile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_720P);
                } else {
                    camcorderProfile = getCamcorderProfile(CameraKit.Constants.VIDEO_QUALITY_480P);
                }
                break;

            case CameraKit.Constants.VIDEO_QUALITY_1080P:
                if (CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_1080P)) {
                    camcorderProfile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_1080P);
                } else {
                    camcorderProfile = getCamcorderProfile(CameraKit.Constants.VIDEO_QUALITY_720P);
                }
                break;

            case CameraKit.Constants.VIDEO_QUALITY_2160P:
                try {
                    camcorderProfile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_2160P);
                } catch (Exception e) {
                    camcorderProfile = getCamcorderProfile(CameraKit.Constants.VIDEO_QUALITY_HIGHEST);
                }
                break;

            case CameraKit.Constants.VIDEO_QUALITY_HIGHEST:
                camcorderProfile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_HIGH);
                break;

            case CameraKit.Constants.VIDEO_QUALITY_LOWEST:
                camcorderProfile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_LOW);
                break;
        }

        if (camcorderProfile != null && mVideoBitRate != 0) {
            camcorderProfile.videoBitRate = mVideoBitRate;
        }

        return camcorderProfile;
    }

    @Override
    void setTapToAutofocusListener(Camera.AutoFocusCallback callback) {
        if (this.mFocus != FOCUS_TAP) {
            throw new IllegalArgumentException("Please set the camera to FOCUS_TAP.");
        }

        this.mAutofocusCallback = callback;
    }

    private int getFocusAreaSize() {
        return FOCUS_AREA_SIZE_DEFAULT;
    }

    private int getFocusMeteringAreaWeight() {
        return FOCUS_METERING_AREA_WEIGHT_DEFAULT;
    }

    private void resetFocus(final boolean success, final Camera camera) {
        mHandler.removeCallbacksAndMessages(null);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mAutofocusCallback != null) {
                    mAutofocusCallback.onAutoFocus(success, mCamera);
                }
            }
        });
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (mCameraLock) {
                    if (mCamera != null && mCameraParameters != null) {
                        mCamera.cancelAutoFocus();
//                        Camera.Parameters parameters = getCameraParameters();
//                        if (parameters == null) return;

                        if (!mCameraParameters.getFocusMode().equals(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                            mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                            mCameraParameters.setFocusAreas(null);
                            mCameraParameters.setMeteringAreas(null);
                            setParameters(mCameraParameters);
                        }
                    }
                }
            }
        }, DELAY_MILLIS_BEFORE_RESETTING_FOCUS);
    }

    private Rect calculateFocusArea(float x, float y) {
        int padding = getFocusAreaSize() / 2;
        int centerX = (int) (x * 2000);
        int centerY = (int) (y * 2000);

        int left = centerX - padding;
        int top = centerY - padding;
        int right = centerX + padding;
        int bottom = centerY + padding;

        if (left < 0) left = 0;
        if (right > 2000) right = 2000;
        if (top < 0) top = 0;
        if (bottom > 2000) bottom = 2000;

        return new Rect(left - 1000, top - 1000, right - 1000, bottom - 1000);
    }

    /**
     * Selects the most suitable preview frames per second range, given the desired frames per
     * second.
     *
     * @param camera            the camera to select a frames per second range from
     * @param desiredPreviewFps the desired frames per second for the camera preview frames
     * @return the selected preview frames per second range
     */
    private static int[] selectPreviewFpsRange(Camera camera, float desiredPreviewFps) {
        // The camera API uses integers scaled by a factor of 1000 instead of floating-point frame
        // rates.
        int desiredPreviewFpsScaled = (int) (desiredPreviewFps * 1000.0f);

        // The method for selecting the best range is to minimize the sum of the differences between
        // the desired value and the upper and lower bounds of the range.  This may select a range
        // that the desired value is outside of, but this is often preferred.  For example, if the
        // desired frame rate is 29.97, the range (30, 30) is probably more desirable than the
        // range (15, 30).
        int[] selectedFpsRange = null;
        int minDiff = Integer.MAX_VALUE;
        List<int[]> previewFpsRangeList = camera.getParameters().getSupportedPreviewFpsRange();
        for (int[] range : previewFpsRangeList) {
            int deltaMin = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MIN_INDEX];
            int deltaMax = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MAX_INDEX];
            int diff = Math.abs(deltaMin) + Math.abs(deltaMax);
            if (diff < minDiff) {
                selectedFpsRange = range;
                minDiff = diff;
            }
        }
        return selectedFpsRange;
    }

}
