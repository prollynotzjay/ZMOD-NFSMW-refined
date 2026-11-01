package com.nfsmw.androidevolved;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;
import android.view.WindowManager;

/**
 * Gyro steering: hold the phone like a wheel and turn it. Uses the game rotation vector (gyroscope and
 * accelerometer fused, no compass, so it does not drift or jump near metal) and measures how far the phone's
 * long edge is tilted from level. That angle is calibrated to the playing position, smoothed, given a small
 * dead zone and a gentle response curve, and reported as -1 (full left) .. +1 (full right), like a stick.
 */
final class GyroSteering implements SensorEventListener {
    interface Output { void steer(float value); }

    private final Context context;
    private final SensorManager sensors;
    private final Sensor sensor;
    private final Output output;
    private final float[] matrix = new float[9];
    private boolean active, calibrate = true, invert;
    private float neutral, filtered;
    private float range = 30f;           // degrees of turn for full lock
    private static final float DEAD_ZONE = 2.5f, SMOOTHING = .35f, CURVE = 1.25f;

    GyroSteering(Context context, Output output) {
        this.context = context;
        this.output = output;
        sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor found = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (found == null && sensors != null) found = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        sensor = found;
    }

    boolean available() { return sensor != null; }

    void configure(float rangeDegrees, boolean invertDirection) {
        range = Math.max(10f, Math.min(70f, rangeDegrees));
        invert = invertDirection;
    }

    /** Takes the current phone angle as "straight ahead". */
    void recalibrate() { calibrate = true; }

    void setActive(boolean on) {
        if (on == active) return;
        active = on;
        if (sensors == null) return;
        sensors.unregisterListener(this);
        if (on && sensor != null) {
            calibrate = true;
            sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        } else {
            output.steer(0f);
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override public void onSensorChanged(SensorEvent event) {
        SensorManager.getRotationMatrixFromVector(matrix, event.values);
        // matrix[7]: how far the device's long axis (y) points up. Turning the phone like a wheel in
        // landscape raises or lowers it.
        float up = Math.max(-1f, Math.min(1f, matrix[7]));
        float angle = (float) Math.toDegrees(Math.asin(up));
        @SuppressWarnings("deprecation")
        int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        if (rotation == Surface.ROTATION_270) angle = -angle;
        if (calibrate) { neutral = angle; filtered = angle; calibrate = false; }
        filtered += SMOOTHING * (angle - filtered);
        float turn = filtered - neutral;
        if (invert) turn = -turn;
        float magnitude = Math.abs(turn);
        float value = magnitude <= DEAD_ZONE ? 0f
                : Math.min(1f, (magnitude - DEAD_ZONE) / (range - DEAD_ZONE));
        value = (float) Math.pow(value, CURVE);
        output.steer(turn < 0 ? -value : value);
    }
}
