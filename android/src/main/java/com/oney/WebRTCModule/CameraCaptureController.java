package com.oney.WebRTCModule;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
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

    /** True once ZoomAwareCaptureSession has been installed for the current camera session. */
    private boolean wrapperInstalled = false;

    private final CameraEventsHandler cameraEventsHandler = new CameraEventsHandler() {
        @Override
        public void onCameraOpening(String cameraName) {
            super.onCameraOpening(cameraName);
            int cameraIndex = findCameraIndex(cameraName);
            updateActualSize(cameraIndex, cameraName, videoCapturer);
            CameraCaptureController.this.currentDeviceId = cameraIndex == -1 ? null : String.valueOf(cameraIndex);
            CameraCaptureController.this.zoomController.onCameraChanged(cameraName);
            // New camera session — wrapper must be reinstalled
            CameraCaptureController.this.wrapperInstalled = false;
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
     * Sets the zoom level and returns a diagnostic string.
     * Returns null on success; returns a human-readable error message if zoom
     * could not be applied (so the caller can surface it to JS debug panel).
     *
     * Strategy:
     * - First call: installs ZoomAwareCaptureSession as a wrapper around
     *   Camera2Session's internal captureSession. This intercepts ALL future
     *   setRepeatingRequest() calls from WebRTC and auto-injects zoom, so zoom
     *   persists across WebRTC's internal stats polling loop.
     * - Subsequent calls: just update zoomController (wrapper auto-applies).
     * - Falls back to changeCaptureFormat() if reflection fails entirely.
     */
    public String setZoom(float zoomRatio) {
        if (!(videoCapturer instanceof Camera2Capturer)) {
            String msg = "ZOOM_UNSUPPORTED: not a Camera2 capturer";
            Log.w(TAG, msg);
            return msg;
        }

        float clamped = zoomController.setZoom(zoomRatio);
        Log.d(TAG, "setZoom clamped=" + clamped);

        String diagnostic = tryInstallWrapperAndApplyZoom(clamped);
        if (diagnostic != null) {
            Log.e(TAG, "!!!ZOOM_FAIL!!! " + diagnostic);
            System.err.println("!!!ZOOM_FAIL!!! " + diagnostic);
            // Do NOT call changeCaptureFormat — it restarts the camera and kills the session
            return diagnostic;
        }
        return null; // success
    }

    /**
     * Installs ZoomAwareCaptureSession (once per camera session) and triggers
     * one setRepeatingRequest to activate the zoom. Returns null on success or
     * a diagnostic string on failure.
     */
    private String tryInstallWrapperAndApplyZoom(float zoomRatio) {
        Log.e(TAG, ">>>tryInstallWrapper ENTER zoom=" + zoomRatio);
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

            Log.d(TAG, "zoom: camera2Session class=" + camera2Session.getClass().getName());

            // ── 2. Get the captureSession field ─────────────────────────────
            java.lang.reflect.Field captureSessionField =
                    findField(camera2Session.getClass(), "captureSession");
            if (captureSessionField == null) {
                return "REFLECT_FAIL: captureSession field not found in "
                        + camera2Session.getClass().getName();
            }
            captureSessionField.setAccessible(true);
            CameraCaptureSession rawSession =
                    (CameraCaptureSession) captureSessionField.get(camera2Session);
            if (rawSession == null) {
                return "REFLECT_FAIL: captureSession value is null";
            }

            // ── 3. Get surface for fallback use in wrapper ───────────────────
            android.view.Surface surface =
                    (android.view.Surface) getFieldValue(camera2Session, "surface");
            if (surface == null) {
                // Dump field names to help diagnose
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

            // ── 4b. Get the camera thread handler (optional, may be null) ────
            Handler cameraThreadHandler =
                    (Handler) getFieldValue(camera2Session, "cameraThreadHandler");

            // ── 5. Install ZoomAwareCaptureSession wrapper (once per session) ──
            // Keep a reference to the real (delegate) session before wrapping.
            CameraCaptureSession delegateSession = (rawSession instanceof ZoomAwareCaptureSession)
                    ? ((ZoomAwareCaptureSession) rawSession).getDelegate()
                    : rawSession;

            if (!wrapperInstalled || !(rawSession instanceof ZoomAwareCaptureSession)) {
                ZoomAwareCaptureSession wrapper =
                        new ZoomAwareCaptureSession(delegateSession, zoomController, surface);
                captureSessionField.set(camera2Session, wrapper);
                wrapperInstalled = true;
                Log.d(TAG, "zoom: ZoomAwareCaptureSession installed");
            } else {
                Log.d(TAG, "zoom: wrapper already installed, updating zoom level only");
            }

            // ── 6. Apply zoom via setRepeatingRequest on the real delegate ────
            // Call setRepeatingRequest on the DELEGATE directly — this avoids:
            //  a) changeCaptureFormat which restarts the camera and destroys the wrapper
            //  b) calling through the wrapper which would double-inject zoom
            // The request uses TEMPLATE_RECORD + the known surface. Future WebRTC
            // setRepeatingRequest calls flow through the wrapper automatically.
            android.hardware.camera2.CameraDevice cameraDevice = delegateSession.getDevice();
            CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(surface);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio);
            } else {
                Rect cropRegion = zoomController.getCropRegion();
                if (cropRegion != null) {
                    builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
                }
            }

            delegateSession.setRepeatingRequest(builder.build(), null, cameraThreadHandler);
            Log.d(TAG, "zoom: setRepeatingRequest OK zoom=" + zoomRatio);
            return null; // success

        } catch (Exception e) {
            Log.e(TAG, "zoom wrapper install failed: " + e.getMessage(), e);
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
