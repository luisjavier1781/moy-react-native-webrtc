package com.oney.WebRTCModule;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;

/**
 * Zoom injection helper for Camera2-based capture.
 *
 * Does NOT extend CameraCaptureSession (which keeps gaining abstract methods
 * across Android SDK versions). Instead, this class is used by
 * CameraCaptureController to inject zoom into CaptureRequests via reflection
 * before they are sent to the real CameraCaptureSession delegate.
 *
 * Usage: Call injectZoomIntoRequest() from within setRepeatingRequest()
 * intercept logic in CameraCaptureController.
 */
public class ZoomAwareCaptureSession {

    private static final String TAG = "ZoomAwareCaptureSession";

    /** The real CameraCaptureSession from WebRTC / Camera2 system. */
    private final CameraCaptureSession delegate;

    /** Source of truth for the current zoom level. */
    private final ZoomController zoomController;

    public ZoomAwareCaptureSession(
            CameraCaptureSession delegate,
            ZoomController zoomController,
            android.view.Surface fallbackSurface) {
        this.delegate = delegate;
        this.zoomController = zoomController;
        // fallbackSurface kept for API compatibility but not used here
    }

    public CameraCaptureSession getDelegate() {
        return delegate;
    }

    // -------------------------------------------------------------------------
    // Zoom injection via CaptureRequest mutation
    // -------------------------------------------------------------------------

    /**
     * Injects zoom into an existing CaptureRequest by mutating its internal
     * CameraMetadataNative (mSettings) via reflection.
     *
     * This avoids rebuilding the request from scratch, which would lose the
     * surface targets and capture configuration that WebRTC set up originally.
     *
     * Returns true if zoom was successfully injected.
     */
    public boolean injectZoomIntoRequest(CaptureRequest request, float zoom) {
        try {
            Field settingsField = findFieldInHierarchy(CaptureRequest.class, "mSettings");
            if (settingsField == null) {
                Log.w(TAG, "mSettings field not found in CaptureRequest hierarchy");
                return false;
            }
            settingsField.setAccessible(true);
            Object nativeSettings = settingsField.get(request);
            if (nativeSettings == null) {
                Log.w(TAG, "mSettings value is null");
                return false;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+: CONTROL_ZOOM_RATIO — direct ratio, no crop math needed
                nativeSettings.getClass()
                        .getMethod("set", CaptureRequest.Key.class, Object.class)
                        .invoke(nativeSettings, CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
            } else {
                // API 24-29: SCALER_CROP_REGION — crop the sensor array
                android.graphics.Rect crop = zoomController.getCropRegion();
                if (crop == null) {
                    Log.w(TAG, "getCropRegion() returned null — zoom not injected");
                    return false;
                }
                nativeSettings.getClass()
                        .getMethod("set", CaptureRequest.Key.class, Object.class)
                        .invoke(nativeSettings, CaptureRequest.SCALER_CROP_REGION, crop);
            }

            return true;
        } catch (Exception e) {
            Log.w(TAG, "injectZoomIntoRequest failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private static Field findFieldInHierarchy(Class<?> clazz, String name) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
}
