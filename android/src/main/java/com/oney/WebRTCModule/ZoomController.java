package com.oney.WebRTCModule;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;

/**
 * Controls zoom for Camera2-based capture.
 *
 * API 30+: Uses CONTROL_ZOOM_RATIO — no capture restart needed.
 * API 24-29: Uses SCALER_CROP_REGION — zoom applied via capture restart.
 */
public class ZoomController {
    private static final String TAG = ZoomController.class.getSimpleName();

    private final Context context;

    /** Camera2 camera ID (e.g. "0", "1") — NOT the device-list index. */
    @Nullable
    private String cameraId;

    private float currentZoom = 1.0f;
    private float minZoom = 1.0f;
    private float maxZoom = 1.0f;

    // Cached sensor rect (only needs to be read once per camera)
    @Nullable
    private Rect sensorRect = null;
    private boolean characteristicsLoaded = false;

    public ZoomController(Context context) {
        this.context = context;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Called when the active camera changes (camera name == camera2 ID on most devices).
     * Clears the cached sensor rect so it is re-read for the new camera.
     */
    public void onCameraChanged(@Nullable String cameraId) {
        this.cameraId = cameraId;
        this.sensorRect = null;
        this.characteristicsLoaded = false;
        this.currentZoom = 1.0f;
        loadCharacteristics();
    }

    /** Returns the current camera ID (Camera2 ID string), or null if no camera is active. */
    @Nullable
    public String getCameraId() {
        return cameraId;
    }

    /**
     * Returns [minZoom, maxZoom] for the current camera.
     * If no camera is active, returns [1.0, 1.0].
     */
    public float[] getZoomRange() {
        loadCharacteristics();
        return new float[]{minZoom, maxZoom};
    }

    /** Returns the current zoom level (1.0 = no zoom). */
    public float getCurrentZoom() {
        return currentZoom;
    }

    /**
     * Clamp and store the desired zoom ratio.
     * Returns the clamped value so the caller can trigger a capture restart
     * (API 24-29) or apply CONTROL_ZOOM_RATIO (API 30+).
     */
    public float setZoom(float requestedZoom) {
        loadCharacteristics();
        float clamped = Math.max(minZoom, Math.min(requestedZoom, maxZoom));
        this.currentZoom = clamped;
        Log.d(TAG, "Zoom set to " + clamped + " (requested=" + requestedZoom + ")");
        return clamped;
    }

    /**
     * API 30+: Applies zoom via CONTROL_ZOOM_RATIO to the given builder.
     * Call this inside your CaptureRequest.Builder setup.
     */
    public void applyZoomRatio(CaptureRequest.Builder builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom);
            Log.d(TAG, "Applied CONTROL_ZOOM_RATIO=" + currentZoom);
        }
    }

    /**
     * API 24-29: Returns the SCALER_CROP_REGION rect for the current zoom.
     * Returns null if sensor rect is not available.
     */
    @Nullable
    public Rect getCropRegion() {
        loadCharacteristics();
        if (sensorRect == null) return null;

        int sensorWidth  = sensorRect.width();
        int sensorHeight = sensorRect.height();

        int cropWidth  = Math.round((float) sensorWidth  / currentZoom);
        int cropHeight = Math.round((float) sensorHeight / currentZoom);

        // Center the crop window on the sensor
        int left = (sensorWidth  - cropWidth)  / 2;
        int top  = (sensorHeight - cropHeight) / 2;

        return new Rect(left, top, left + cropWidth, top + cropHeight);
    }

    /** True if CONTROL_ZOOM_RATIO is supported (API 30+). */
    public boolean supportsZoomRatio() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private void loadCharacteristics() {
        if (characteristicsLoaded || cameraId == null) return;
        try {
            CameraManager cameraManager =
                    (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);

            // Sensor active array
            sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

            // Zoom range
            minZoom = 1.0f;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+: ZOOM_RATIO_RANGE
                android.util.Range<Float> zoomRatioRange =
                        chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (zoomRatioRange != null) {
                    minZoom = zoomRatioRange.getLower();
                    maxZoom = zoomRatioRange.getUpper();
                }
            }

            if (maxZoom <= 1.0f) {
                // Fallback: derive max from SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
                Float maxDigital =
                        chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                maxZoom = (maxDigital != null && maxDigital > 1.0f) ? maxDigital : 1.0f;
            }

            characteristicsLoaded = true;
            Log.d(TAG, "Camera " + cameraId + " zoom range: [" + minZoom + ", " + maxZoom + "]");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load camera characteristics for id=" + cameraId, e);
            minZoom = 1.0f;
            maxZoom = 1.0f;
        }
    }
}
