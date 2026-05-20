package com.spectral.ripeness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.Socket;

/**
 * Foreground service for background spectral data recording.
 * Connects to ripeness_daemon on localhost:8765, appends labels
 * and Android sensor data, writes to JSONL file.
 * Survives activity pauses so the user can use the phone normally.
 *
 * Start: intent with extras "label" and "path"
 * Stop: intent with action ACTION_STOP
 */
public class RecordingService extends Service implements SensorEventListener {

    public static final String ACTION_STOP = "com.spectral.ripeness.STOP_RECORDING";
    private static final String CHANNEL_ID = "spectral_recording";
    private static final int NOTIF_ID = 1;

    private volatile boolean running = false;
    private Thread readerThread;
    private Socket socket;
    private FileOutputStream fileOut;
    private String label = "unlabeled";
    private int sampleCount = 0;

    private SensorManager sensorManager;
    private float ambientLux = -1, accelMag = 0, pressure = -1;
    private boolean isStable = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopRecording();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null) {
            label = intent.getStringExtra("label");
            if (label == null) label = "unlabeled";
            String path = intent.getStringExtra("path");
            if (path == null) path = "/data/local/tmp/bg_recording.jsonl";

            try {
                fileOut = new FileOutputStream(path);
            } catch (Exception e) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        startForeground(NOTIF_ID, buildNotification("Recording: " + label));
        startSensors();
        startRecording();
        return START_STICKY;
    }

    private void startSensors() {
        Sensor light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        Sensor baro = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (light != null) sensorManager.registerListener(this, light, SensorManager.SENSOR_DELAY_UI);
        if (accel != null) sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI);
        if (baro != null) sensorManager.registerListener(this, baro, SensorManager.SENSOR_DELAY_UI);
    }

    private void startRecording() {
        running = true;
        sampleCount = 0;
        readerThread = new Thread(() -> {
            while (running) {
                try {
                    socket = new Socket("127.0.0.1", 8765);
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        String fruit = label.contains("_") ? label.split("_")[0] : label;
                        String stage = label.contains("_") ? label.split("_", 2)[1] : label;
                        String augmented = line.substring(0, line.length() - 1)
                            + ",\"label\":{\"fruit\":\"" + fruit
                            + "\",\"stage\":\"" + stage
                            + "\"},\"als\":" + ambientLux
                            + ",\"stable\":" + isStable
                            + ",\"pressure\":" + pressure + "}\n";
                        if (fileOut != null) {
                            fileOut.write(augmented.getBytes());
                            sampleCount++;
                            if (sampleCount % 50 == 0) {
                                fileOut.flush();
                                updateNotification("Recording: " + label + " #" + sampleCount);
                            }
                        }
                    }
                } catch (Exception e) {
                    if (running) {
                        try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                    }
                }
            }
        });
        readerThread.start();
    }

    private void stopRecording() {
        running = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        try { if (socket != null) socket.close(); } catch (Exception e) {}
        try { if (fileOut != null) { fileOut.flush(); fileOut.close(); } } catch (Exception e) {}
        if (readerThread != null) readerThread.interrupt();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            ambientLux = event.values[0];
        } else if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            float x = event.values[0], y = event.values[1], z = event.values[2];
            accelMag = (float) Math.sqrt(x*x + y*y + z*z);
            isStable = accelMag < 0.3f;
        } else if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
            pressure = event.values[0];
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stopRecording();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Spectral Recording", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Background spectral data recording");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, RecordingService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent launchIntent = new Intent(this, RipenessActivity.class);
        PendingIntent launchPending = PendingIntent.getActivity(this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Fruit Ripeness")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(launchPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class)
            .notify(NOTIF_ID, buildNotification(text));
    }
}
