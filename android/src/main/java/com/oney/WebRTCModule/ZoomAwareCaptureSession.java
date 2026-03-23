package com.oney.WebRTCModule;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Wraps a real CameraCaptureSession and intercepts every setRepeatingRequest()
 * call to auto-inject the current zoom level from ZoomController.
 *
 * Problem it solves: WebRTC's Camera2Session calls setRepeatingRequest() roughly
 * every 1 second during stats polling, rebuilding the CaptureRequest from scratch
 * without zoom. This resets any zoom we applied via our own setRepeatingRequest.
 *
 * Solution: Install this wrapper once (via reflection, replacing
 * Camera2Session.captureSession) so that ALL future setRepeatingRequest calls —
 * whether from WebRTC internals or from our zoom code — automatically carry zoom.
 *
 * Zoom injection strategy: mutate the CaptureRequest's internal CameraMetadataNative
 * (mSettings field) in-place via reflection. This avoids rebuilding the request from
 * scratch (which loses WebRTC's surface targets and capture configuration).
 */
public class ZoomAwareCaptureSession extends CameraCaptureSession {

    private static final String TAG = "ZoomAwareCaptureSession";

    /** The real CameraCaptureSession from WebRTC / Camera2 system. */
    private final CameraCaptureSession delegate;

    /** Source of truth for the current zoom level. */
    private final ZoomController zoomController;

    public ZoomAwareCaptureSession(
            CameraCaptureSession delegate,
            ZoomController zoomController,
            Surface fallbackSurface) {
        this.delegate = delegate;
        this.zoomController = zoomController;
        // fallbackSurface kept for API compatibility but no longer used
    }

    public CameraCaptureSession getDelegate() {
        return delegate;
    }

    // -------------------------------------------------------------------------
    // Interception point
    // -------------------------------------------------------------------------

    @Override
    public int setRepeatingRequest(CaptureRequest request, CaptureCallback listener, Handler handler)
            throws CameraAccessException {
        float zoom = zoomController.getCurrentZoom();
        if (zoom > 1.0f) {
            boolean injected = injectZoomIntoRequest(request, zoom);
            if (injected) {
                Log.d(TAG, "setRepeatingRequest: zoom injected zoom=" + zoom);
            } else {
                Log.w(TAG, "injectZoom failed, passing original request (zoom lost)");
            }
        }
        return delegate.setRepeatingRequest(request, listener, handler);
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
    private boolean injectZoomIntoRequest(CaptureRequest request, float zoom) {
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

    // -------------------------------------------------------------------------
    // Delegation — all abstract methods forwarded to delegate
    // -------------------------------------------------------------------------

    @Override
    public CameraDevice getDevice() {
        return delegate.getDevice();
    }

    @Override
    public void abortCaptures() throws CameraAccessException {
        delegate.abortCaptures();
    }

    @Override
    public int capture(CaptureRequest request, CaptureCallback listener, Handler handler)
            throws CameraAccessException {
        return delegate.capture(request, listener, handler);
    }

    @Override
    public int captureBurst(List<CaptureRequest> requests, CaptureCallback listener, Handler handler)
            throws CameraAccessException {
        return delegate.captureBurst(requests, listener, handler);
    }

    @Override
    public int setRepeatingBurst(List<CaptureRequest> requests, CaptureCallback listener, Handler handler)
            throws CameraAccessException {
        return delegate.setRepeatingBurst(requests, listener, handler);
    }

    @Override
    public void stopRepeating() throws CameraAccessException {
        delegate.stopRepeating();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public Surface getInputSurface() {
        return delegate.getInputSurface();
    }

    @Override
    public void prepare(Surface surface) throws CameraAccessException {
        delegate.prepare(surface);
    }

    @Override
    public boolean isReprocessable() {
        return delegate.isReprocessable();
    }
}
