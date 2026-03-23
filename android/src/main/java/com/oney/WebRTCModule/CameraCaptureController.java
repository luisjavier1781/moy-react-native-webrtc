package com.oney.WebRTCModule;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;

import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;

import org.webrtc.Camera1Capturer;
import org.webrtc.Camera1Helper;
import org.webrtc.Camera2Capturer;
import org.webrtc.Camera2Helper;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.Size;
import org.webrtc.VideoCapturer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CameraCaptureController extends AbstractVideoCaptureController {
    private static final String TAG = CameraCaptureController.class.getSimpleName();

    private boolean isFrontFacing;

    @Nullable
    private String currentDeviceId;

    private final ZoomController zoomController;
    private final Context context;
    private final CameraEnumerator cameraEnumerator;
    private ReadableMap constraints;

    // ── Camera control state ──────────────────────────────────────────────────
    /** Exposure compensation in EV steps. 0 = default. Range queried from CameraCharacteristics. */
    private int exposureCompensation = 0;
    /** AWB mode. Default = AUTO. Matches CaptureRequest.CONTROL_AWB_MODE_* constants. */
    private int whiteBalanceMode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
    /** Whether video stabilization is enabled. */
    private boolean stabilizationEnabled = false;

    /** Handler thread for periodic zoom re-application. */
    @Nullable
    private HandlerThread zoomHandlerThread;
    @Nullable
    private Handler zoomHandler;
    /** Runnable that periodically re-sends a zoom request to the camera. */
    private final Runnable zoomKeepaliveRunnable = new Runnable() {
        @Override
        public void run() {
            float zoom = zoomController.getCurrentZoom();
            if (zoom > 1.0f) {
                applyZoomDirect(zoom);
                if (zoomHandler != null) {
                    zoomHandler.postDelayed(this, 600);
                }
            }
            // If zoom == 1.0 the loop stops; it will be restarted by setZoom() if needed
        }
    };

    private final CameraEventsHandler cameraEventsHandler = new CameraEventsHandler() {
        @Override
        public void onCameraOpening(String cameraName) {
            super.onCameraOpening(cameraName);
            int cameraIndex = findCameraIndex(cameraName);
            updateActualSize(cameraIndex, cameraName, videoCapturer);
            CameraCaptureController.this.currentDeviceId = cameraIndex == -1 ? null : String.valueOf(cameraIndex);
            CameraCaptureController.this.zoomController.onCameraChanged(cameraName);
        }
    };

    public CameraCaptureController(Context context, CameraEnumerator cameraEnumerator, ReadableMap constraints) {
        super(constraints.getInt("width"), constraints.getInt("height"), constraints.getInt("frameRate"));
        this.context = context;
        this.cameraEnumerator = cameraEnumerator;
        this.constraints = constraints;
        this.zoomController = new ZoomController(context);
    }

    @Nullable
    @Override
    public String getDeviceId() {
        return currentDeviceId;
    }

    private int findCameraIndex(String cameraName) {
        String[] deviceNames = cameraEnumerator.getDeviceNames();
        for (int i = 0; i < deviceNames.length; i++) {
            if (Objects.equals(deviceNames[i], cameraName)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public WritableMap getSettings() {
        WritableMap settings = super.getSettings();
        settings.putString("facingMode", isFrontFacing ? "user" : "environment");
        return settings;
    }

    @Override
    public void applyConstraints(ReadableMap constraints, @Nullable Consumer<Exception> onFinishedCallback) {
        ReadableMap oldConstraints = this.constraints;
        int oldTargetWidth = this.targetWidth;
        int oldTargetHeight = this.targetHeight;
        int oldTargetFps = this.targetFps;

        Runnable saveConstraints = () -> {
            this.constraints = constraints;
            this.targetWidth = constraints.getInt("width");
            this.targetHeight = constraints.getInt("height");
            this.targetFps = constraints.getInt("frameRate");
        };

        if (videoCapturer == null) {
            saveConstraints.run();
            if (onFinishedCallback != null) {
                onFinishedCallback.accept(null);
            }
            return;
        }

        String[] deviceNames = cameraEnumerator.getDeviceNames();
        final String deviceId = ReactBridgeUtil.getMapStrValue(constraints, "deviceId");
        final String facingMode = ReactBridgeUtil.getMapStrValue(constraints, "facingMode");
        int cameraIndex = -1;
        String cameraName = null;

        if (deviceId != null) {
            try {
                cameraIndex = Integer.parseInt(deviceId);
                cameraName = deviceNames[cameraIndex];
            } catch (Exception e) {
                Log.d(TAG, "failed to find device with id: " + deviceId);
            }
        }

        if (cameraName == null) {
            cameraIndex = -1;
            final boolean isFrontFacing = facingMode == null || facingMode.equals("user");
            for (String name : deviceNames) {
                cameraIndex++;
                if (cameraEnumerator.isFrontFacing(name) == isFrontFacing) {
                    cameraName = name;
                    break;
                }
            }
        }

        if (cameraName == null) {
            if (onFinishedCallback != null) {
                onFinishedCallback.accept(new Exception("OverconstrainedError: could not find camera with deviceId: "
                        + deviceId + " or facingMode: " + facingMode));
            }
            return;
        }

        final int finalCameraIndex = cameraIndex;
        final String finalCameraName = cameraName;
        boolean shouldSwitchCamera = false;
        try {
            int currentCameraIndex = Integer.parseInt(currentDeviceId);
            shouldSwitchCamera = cameraIndex != currentCameraIndex;
        } catch (Exception e) {
            shouldSwitchCamera = true;
            Log.d(TAG, "Forcing camera switch, couldn't parse current device id: " + currentDeviceId);
        }

        CameraVideoCapturer capturer = (CameraVideoCapturer) videoCapturer;
        Runnable changeFormatIfNeededAndFinish = () -> {
            saveConstraints.run();
            if (targetWidth != oldTargetWidth || targetHeight != oldTargetHeight || targetFps != oldTargetFps) {
                updateActualSize(finalCameraIndex, finalCameraName, videoCapturer);
                capturer.changeCaptureFormat(targetWidth, targetHeight, targetFps);
            }
            if (onFinishedCallback != null) {
                onFinishedCallback.accept(null);
            }
        };

        if (shouldSwitchCamera) {
            capturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
                @Override
                public void onCameraSwitchDone(boolean isFrontCamera) {
                    CameraCaptureController.this.isFrontFacing = isFrontCamera;
                    changeFormatIfNeededAndFinish.run();
                }

                @Override
                public void onCameraSwitchError(String s) {
                    Exception e = new Exception("Error switching camera: " + s);
                    Log.e(TAG, "OnCameraSwitchError", e);
                    if (onFinishedCallback != null) {
                        onFinishedCallback.accept(e);
                    }
                }
            }, cameraName);
        } else {
            changeFormatIfNeededAndFinish.run();
        }
    }

    @Override
    protected VideoCapturer createVideoCapturer() {
        String deviceId = ReactBridgeUtil.getMapStrValue(this.constraints, "deviceId");
        String facingMode = ReactBridgeUtil.getMapStrValue(this.constraints, "facingMode");

        CreateCapturerResult result = createVideoCapturer(deviceId, facingMode);
        if (result == null) {
            return null;
        }

        updateActualSize(result.cameraIndex, result.cameraName, result.videoCapturer);
        return result.videoCapturer;
    }

    private void updateActualSize(int cameraIndex, String cameraName, VideoCapturer videoCapturer) {
        Size actualSize = null;
        if (videoCapturer instanceof Camera1Capturer) {
            actualSize = Camera1Helper.findClosestCaptureFormat(cameraIndex, targetWidth, targetHeight);
        } else if (videoCapturer instanceof Camera2Capturer) {
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            actualSize = Camera2Helper.findClosestCaptureFormat(cameraManager, cameraName, targetWidth, targetHeight);
        }

        if (actualSize != null) {
            actualWidth = actualSize.width;
            actualHeight = actualSize.height;
        }
    }

    @Nullable
    private CreateCapturerResult createVideoCapturer(String deviceId, String facingMode) {
        String[] deviceNames = cameraEnumerator.getDeviceNames();
        List<String> failedDevices = new ArrayList<>();

        String cameraName = null;
        int cameraIndex = -1;
        try {
            cameraIndex = Integer.parseInt(deviceId);
            cameraName = deviceNames[cameraIndex];
        } catch (Exception e) {
            Log.d(TAG, "failed to find device with id: " + deviceId);
        }

        if (cameraName != null) {
            VideoCapturer videoCapturer = cameraEnumerator.createCapturer(cameraName, cameraEventsHandler);
            String message = "Create user-specified camera " + cameraName;
            if (videoCapturer != null) {
                Log.d(TAG, message + " succeeded");
                this.isFrontFacing = cameraEnumerator.isFrontFacing(cameraName);
                this.currentDeviceId = String.valueOf(cameraIndex);
                return new CreateCapturerResult(cameraIndex, cameraName, videoCapturer);
            } else {
                Log.d(TAG, message + " failed");
                failedDevices.add(cameraName);
            }
        }

        final boolean isFrontFacing = facingMode == null || facingMode.equals("user");
        cameraIndex = -1;
        for (String name : deviceNames) {
            cameraIndex++;
            if (failedDevices.contains(name)) continue;
            if (cameraEnumerator.isFrontFacing(name) != isFrontFacing) continue;
            VideoCapturer videoCapturer = cameraEnumerator.createCapturer(name, cameraEventsHandler);
            String message = "Create camera " + name;
            if (videoCapturer != null) {
                Log.d(TAG, message + " succeeded");
                this.isFrontFacing = cameraEnumerator.isFrontFacing(name);
                this.currentDeviceId = String.valueOf(cameraIndex);
                return new CreateCapturerResult(cameraIndex, name, videoCapturer);
            } else {
                Log.d(TAG, message + " failed");
                failedDevices.add(name);
            }
        }

        cameraIndex = -1;
        for (String name : deviceNames) {
            cameraIndex++;
            if (!failedDevices.contains(name)) {
                VideoCapturer videoCapturer = cameraEnumerator.createCapturer(name, cameraEventsHandler);
                String message = "Create fallback camera " + name;
                if (videoCapturer != null) {
                    Log.d(TAG, message + " succeeded");
                    this.isFrontFacing = cameraEnumerator.isFrontFacing(name);
                    this.currentDeviceId = String.valueOf(cameraIndex);
                    return new CreateCapturerResult(cameraIndex, name, videoCapturer);
                } else {
                    Log.d(TAG, message + " failed");
                    failedDevices.add(name);
                }
            }
        }

        currentDeviceId = null;
        Log.w(TAG, "Unable to identify a suitable camera.");
        return null;
    }

    private static class CreateCapturerResult {
        public final int cameraIndex;
        public final String cameraName;
        public final VideoCapturer videoCapturer;

        public CreateCapturerResult(int cameraIndex, String cameraName, VideoCapturer videoCapturer) {
            this.cameraIndex = cameraIndex;
            this.cameraName = cameraName;
            this.videoCapturer = videoCapturer;
        }
    }

    // -------------------------------------------------------------------------
    // Zoom API
    // -------------------------------------------------------------------------

    public float[] getZoomRange() {
        return zoomController.getZoomRange();
    }

    /**
     * Sets exposure compensation in EV steps (e.g. -2, -1, 0, 1, 2).
     * The valid range is device-dependent; query it via getCameraCapabilities().
     * Returns null on success or a diagnostic string on failure.
     */
    public String setExposure(int compensation) {
        this.exposureCompensation = compensation;
        return applyZoomDirect(zoomController.getCurrentZoom());
    }

    /**
     * Sets the AWB (auto white balance) mode.
     * @param mode one of: "auto", "daylight", "cloudy", "fluorescent", "incandescent", "shade"
     * Returns null on success or a diagnostic string on failure.
     */
    public String setWhiteBalance(String mode) {
        this.whiteBalanceMode = wbStringToAndroidMode(mode);
        return applyZoomDirect(zoomController.getCurrentZoom());
    }

    /**
     * Enables or disables digital video stabilization.
     * Returns null on success or a diagnostic string on failure.
     */
    public String setStabilization(boolean enabled) {
        this.stabilizationEnabled = enabled;
        return applyZoomDirect(zoomController.getCurrentZoom());
    }

    /**
     * Returns camera capabilities: exposure range, available WB modes, stabilization support.
     * Returns null if the camera is not open or characteristics cannot be read.
     */
    @Nullable
    public com.facebook.react.bridge.WritableMap getCameraCapabilities() {
        if (zoomController == null) return null;
        String cameraId = zoomController.getCameraId();
        if (cameraId == null) return null;
        try {
            CameraManager cameraManager =
                    (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            android.hardware.camera2.CameraCharacteristics chars =
                    cameraManager.getCameraCharacteristics(cameraId);

            com.facebook.react.bridge.WritableMap result =
                    com.facebook.react.bridge.Arguments.createMap();

            // Exposure compensation range
            android.util.Range<Integer> aeRange =
                    chars.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if (aeRange != null) {
                result.putInt("exposureMin", aeRange.getLower());
                result.putInt("exposureMax", aeRange.getUpper());
            } else {
                result.putInt("exposureMin", 0);
                result.putInt("exposureMax", 0);
            }

            // Available AWB modes → mapped to human-readable strings
            int[] awbModes = chars.get(
                    android.hardware.camera2.CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
            com.facebook.react.bridge.WritableArray wbArray =
                    com.facebook.react.bridge.Arguments.createArray();
            if (awbModes != null) {
                for (int m : awbModes) {
                    String name = wbAndroidModeToString(m);
                    if (name != null) wbArray.pushString(name);
                }
            }
            result.putArray("wbModes", wbArray);

            // Video stabilization support
            int[] stabModes = chars.get(
                    android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            boolean hasStabilization = false;
            if (stabModes != null) {
                for (int m : stabModes) {
                    if (m == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
                        hasStabilization = true;
                        break;
                    }
                }
            }
            result.putBoolean("hasStabilization", hasStabilization);

            return result;
        } catch (Exception e) {
            Log.e(TAG, "getCameraCapabilities failed: " + e.getMessage(), e);
            return null;
        }
    }

    // ── WB mode helpers ───────────────────────────────────────────────────────

    private static int wbStringToAndroidMode(String mode) {
        if (mode == null) return CaptureRequest.CONTROL_AWB_MODE_AUTO;
        switch (mode) {
            case "daylight":     return CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT;
            case "cloudy":       return CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT;
            case "fluorescent":  return CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT;
            case "incandescent": return CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT;
            case "shade":        return CaptureRequest.CONTROL_AWB_MODE_SHADE;
            default:             return CaptureRequest.CONTROL_AWB_MODE_AUTO;
        }
    }

    @Nullable
    private static String wbAndroidModeToString(int mode) {
        switch (mode) {
            case CaptureRequest.CONTROL_AWB_MODE_AUTO:             return "auto";
            case CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT:         return "daylight";
            case CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT:  return "cloudy";
            case CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT:      return "fluorescent";
            case CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT:     return "incandescent";
            case CaptureRequest.CONTROL_AWB_MODE_SHADE:            return "shade";
            default:                                                return null; // skip unknown/internal modes
        }
    }

    /**
     * Sets the zoom level and returns a diagnostic string.
     * Returns null on success; returns a human-readable error message if zoom
     * could not be applied (so the caller can surface it to JS debug panel).
     *
     * Strategy:
     * - Immediately applies zoom via a direct setRepeatingRequest call.
     * - If zoom > 1.0: starts a keepalive loop (every 600ms) to re-apply zoom,
     *   because WebRTC's internal stats polling resets zoom every ~1 second.
     * - If zoom == 1.0: stops the keepalive loop.
     */
    public String setZoom(float zoomRatio) {
        if (!(videoCapturer instanceof Camera2Capturer)) {
            String msg = "ZOOM_UNSUPPORTED: not a Camera2 capturer";
            Log.w(TAG, msg);
            return msg;
        }

        float clamped = zoomController.setZoom(zoomRatio);
        Log.d(TAG, "setZoom clamped=" + clamped);

        String diagnostic = applyZoomDirect(clamped);
        if (diagnostic != null) {
            Log.e(TAG, "!!!ZOOM_FAIL!!! " + diagnostic);
            return diagnostic;
        }

        if (clamped > 1.0f) {
            startZoomKeepalive();
        } else {
            stopZoomKeepalive();
        }

        return null; // success
    }

    /** Starts a periodic zoom keepalive to counteract WebRTC's stats-polling zoom reset. */
    private void startZoomKeepalive() {
        if (zoomHandler == null) {
            zoomHandlerThread = new HandlerThread("ZoomKeepalive");
            zoomHandlerThread.start();
            zoomHandler = new Handler(zoomHandlerThread.getLooper());
        }
        // Remove any pending callbacks before posting a new one
        zoomHandler.removeCallbacks(zoomKeepaliveRunnable);
        zoomHandler.postDelayed(zoomKeepaliveRunnable, 600);
        Log.d(TAG, "zoom: keepalive started");
    }

    /** Stops the periodic zoom keepalive. */
    private void stopZoomKeepalive() {
        if (zoomHandler != null) {
            zoomHandler.removeCallbacks(zoomKeepaliveRunnable);
        }
        Log.d(TAG, "zoom: keepalive stopped");
    }

    /**
     * Directly applies zoom by calling setRepeatingRequest on the current
     * camera session via reflection. Returns null on success or a diagnostic
     * string on failure.
     */
    private String applyZoomDirect(float zoomRatio) {
        Log.d(TAG, ">>>applyZoomDirect zoom=" + zoomRatio);
        try {
            // ── 1. Get Camera2Session from CameraCapturer ────────────────────
            Object stateLock = getFieldValue(videoCapturer, "stateLock");
            Object camera2Session;
            if (stateLock != null) {
                synchronized (stateLock) {
                    camera2Session = getFieldValue(videoCapturer, "currentSession");
                }
            } else {
                camera2Session = getFieldValue(videoCapturer, "currentSession");
            }
            if (camera2Session == null) {
                return "REFLECT_FAIL: currentSession is null (camera not open yet?)";
            }

            // ── 2. Get the captureSession ────────────────────────────────────
            java.lang.reflect.Field captureSessionField =
                    findField(camera2Session.getClass(), "captureSession");
            if (captureSessionField == null) {
                return "REFLECT_FAIL: captureSession field not found in "
                        + camera2Session.getClass().getName();
            }
            captureSessionField.setAccessible(true);
            CameraCaptureSession captureSession =
                    (CameraCaptureSession) captureSessionField.get(camera2Session);
            if (captureSession == null) {
                return "REFLECT_FAIL: captureSession value is null";
            }

            // ── 3. Get the surface ───────────────────────────────────────────
            android.view.Surface surface =
                    (android.view.Surface) getFieldValue(camera2Session, "surface");
            if (surface == null) {
                StringBuilder fieldNames = new StringBuilder();
                Class<?> cls = camera2Session.getClass();
                while (cls != null) {
                    for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                        fieldNames.append(f.getType().getSimpleName())
                                .append(":").append(f.getName()).append(" ");
                    }
                    cls = cls.getSuperclass();
                }
                return "REFLECT_FAIL: surface field not found. Fields: "
                        + fieldNames.toString().trim();
            }

            // ── 4. Get the camera thread handler (optional) ──────────────────
            Handler cameraThreadHandler =
                    (Handler) getFieldValue(camera2Session, "cameraThreadHandler");

            // ── 5. Build a request with zoom and send it ─────────────────────
            android.hardware.camera2.CameraDevice cameraDevice = captureSession.getDevice();
            CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(surface);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            // ── Zoom ─────────────────────────────────────────────────────────
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio);
            } else {
                Rect cropRegion = zoomController.getCropRegion();
                if (cropRegion != null) {
                    builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
                }
            }

            // ── Exposure compensation (only when non-zero) ────────────────────
            if (exposureCompensation != 0) {
                try {
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                            exposureCompensation);
                } catch (Exception e) {
                    Log.w(TAG, "exposure compensation not supported: " + e.getMessage());
                }
            }

            // ── White balance (only when not AUTO) ────────────────────────────
            if (whiteBalanceMode != CaptureRequest.CONTROL_AWB_MODE_AUTO) {
                try {
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, whiteBalanceMode);
                } catch (Exception e) {
                    Log.w(TAG, "white balance mode not supported: " + e.getMessage());
                }
            }

            // ── Video stabilization ───────────────────────────────────────────
            if (stabilizationEnabled) {
                try {
                    builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
                } catch (Exception e) {
                    Log.w(TAG, "video stabilization not supported: " + e.getMessage());
                }
            }

            captureSession.setRepeatingRequest(builder.build(), null, cameraThreadHandler);
            Log.d(TAG, "zoom: setRepeatingRequest OK zoom=" + zoomRatio);
            return null; // success

        } catch (Exception e) {
            Log.e(TAG, "applyZoomDirect failed: " + e.getMessage(), e);
            return "REFLECT_EXCEPTION: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    @Nullable
    private static Object getFieldValue(Object target, String fieldName) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                Log.e(TAG, "getFieldValue(" + fieldName + ") error: " + e.getMessage());
                return null;
            }
        }
        Log.w(TAG, "Field not found: " + fieldName + " in " + target.getClass().getName());
        return null;
    }

    /** Like getFieldValue but returns the Field object itself (for set access). */
    @Nullable
    private static Field findField(Class<?> clazz, String fieldName) {
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
