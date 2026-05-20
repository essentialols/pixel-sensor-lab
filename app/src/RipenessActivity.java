package com.spectral.ripeness;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.widget.EditText;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.widget.ScrollView;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.util.TypedValue;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Fruit Ripeness Analyzer
 *
 * Connects to ripeness_daemon on localhost:8765, displays live
 * spectral data + ripeness indices.
 */
public class RipenessActivity extends Activity
        implements TextureView.SurfaceTextureListener, SensorEventListener {

    private TextView tvStatus, tvNDVI, tvRG, tvNIRVIS, tvRipeness;
    private TextView tvRed, tvGreen, tvBlue, tvIR, tvCLR1, tvCLR2;
    private ProgressBar pbRed, pbGreen, pbBlue, pbIR;
    private TextView tvToF, tvLux, tvAmbientALS;
    private View colorSwatch;
    private Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean connected = false;
    private SensorManager sensorManager;
    private Sensor lightSensor, proxSensor, accelSensor, pressureSensor;
    private float ambientLux = -1, proximity = -1;
    private float accelMag = 0, pressure = -1, temperature = -1;
    private boolean isStable = false;

    // Recording mode
    private boolean recording = false;
    private FileOutputStream recordStream;
    private String recordLabel = "";
    private int recordCount = 0;
    private Button recordBtn;
    private TextView tvRecordStatus;
    private Socket socket;

    // Camera2 API fields
    private TextureView textureView;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Handler cameraHandler;
    private HandlerThread cameraThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);
        root.setBackgroundColor(Color.parseColor("#1a1a2e"));

        // Wrap in ScrollView for long content
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#1a1a2e"));

        // Title
        TextView title = makeLabel("Fruit Ripeness Analyzer", 24, "#e94560");
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        tvStatus = makeLabel("Connecting to daemon...", 14, "#888888");
        tvStatus.setGravity(Gravity.CENTER);
        root.addView(tvStatus);

        // Camera preview (above color swatch)
        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(this);
        LinearLayout.LayoutParams cameraParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 400);
        cameraParams.setMargins(0, 16, 0, 16);
        root.addView(textureView, cameraParams);

        // Capture button
        Button captureBtn = new Button(this);
        captureBtn.setText("Capture Frame");
        captureBtn.setOnClickListener(v -> captureFrame());
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(0, 0, 0, 16);
        root.addView(captureBtn, btnParams);

        // Record button + status
        LinearLayout recordRow = new LinearLayout(this);
        recordRow.setOrientation(LinearLayout.HORIZONTAL);
        recordBtn = new Button(this);
        recordBtn.setText("Record: OFF");
        recordBtn.setOnClickListener(v -> toggleRecording());
        recordRow.addView(recordBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        tvRecordStatus = new TextView(this);
        tvRecordStatus.setText(" ");
        tvRecordStatus.setTextColor(Color.parseColor("#ff4444"));
        tvRecordStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        recordRow.addView(tvRecordStatus, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(recordRow);

        // Color swatch
        colorSwatch = new View(this);
        colorSwatch.setMinimumHeight(80);
        colorSwatch.setBackgroundColor(Color.DKGRAY);
        LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 80);
        swatchParams.setMargins(0, 24, 0, 24);
        root.addView(colorSwatch, swatchParams);

        // Ripeness verdict
        tvRipeness = makeLabel("---", 32, "#00ff88");
        tvRipeness.setGravity(Gravity.CENTER);
        root.addView(tvRipeness);

        // Spectral indices
        root.addView(makeLabel("Spectral Indices", 16, "#e94560"));
        tvNDVI = makeLabel("NDVI: ---", 18, "#ffffff");
        root.addView(tvNDVI);
        tvRG = makeLabel("R/G: ---", 18, "#ffffff");
        root.addView(tvRG);
        tvNIRVIS = makeLabel("NIR/VIS: ---", 18, "#ffffff");
        root.addView(tvNIRVIS);

        // Raw channels
        root.addView(makeLabel("Raw Channels", 16, "#e94560"));
        tvRed = makeLabel("R: ---", 14, "#ff4444");
        root.addView(tvRed);
        tvGreen = makeLabel("G: ---", 14, "#44ff44");
        root.addView(tvGreen);
        tvBlue = makeLabel("B: ---", 14, "#4444ff");
        root.addView(tvBlue);
        tvIR = makeLabel("IR: ---", 14, "#ff8800");
        root.addView(tvIR);
        tvCLR1 = makeLabel("CLR1: ---", 14, "#cccccc");
        root.addView(tvCLR1);
        tvCLR2 = makeLabel("CLR2: ---", 14, "#aaaaaa");
        root.addView(tvCLR2);

        // ToF + Lux + Ambient
        root.addView(makeLabel("940nm + Environment", 16, "#e94560"));
        tvToF = makeLabel("ToF: ---", 14, "#ffaa00");
        root.addView(tvToF);
        tvLux = makeLabel("Lux (spectral est): ---", 14, "#ffffff");
        root.addView(tvLux);
        tvAmbientALS = makeLabel("Ambient ALS: ---", 14, "#88ccff");
        root.addView(tvAmbientALS);

        scroll.addView(root);
        setContentView(scroll);

        // Initialize camera manager
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        // Initialize Android sensors via SensorManager
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        proxSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (lightSensor != null)
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_UI);
        if (proxSensor != null)
            sensorManager.registerListener(this, proxSensor, SensorManager.SENSOR_DELAY_UI);
        if (accelSensor != null)
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI);
        if (pressureSensor != null)
            sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_UI);

        startDaemonConnection();
    }

    private TextView makeLabel(String text, int sizeSp, String color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(Color.parseColor(color));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 4, 0, 4);
        tv.setLayoutParams(p);
        return tv;
    }

    private void startDaemonConnection() {
        new Thread(() -> {
            while (!connected && !isFinishing()) {
                try {
                    socket = new Socket("127.0.0.1", 8765);
                    connected = true;
                    handler.post(() -> tvStatus.setText("Connected to daemon"));

                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null && !isFinishing()) {
                        processJson(line);
                    }
                } catch (Exception e) {
                    handler.post(() -> tvStatus.setText("Waiting for daemon (port 8765)..."));
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                }
            }
        }).start();
    }

    private void processJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONObject raw = obj.getJSONObject("raw");
            JSONObject idx = obj.getJSONObject("idx");

            final double r = raw.getDouble("R");
            final double g = raw.getDouble("G");
            final double b = raw.getDouble("B");
            final double ir = raw.getDouble("IR");
            final double c1 = raw.getDouble("CLR1");
            final double c2 = raw.getDouble("CLR2");
            final double gain = obj.getDouble("gain");

            final double ndvi = idx.getDouble("NDVI");
            final double rg = idx.getDouble("RG");
            final double bg = idx.getDouble("BG");
            final double nirvis = idx.getDouble("NIR_VIS");
            final double clr = idx.getDouble("CLR");
            final double ci = idx.getDouble("CI");

            // Estimate lux from green channel
            final double lux = (g / gain) / 109.58;

            // ToF data if available
            final boolean hasTof = obj.has("tof");
            final int tofPhotons = hasTof ? obj.getJSONObject("tof").getInt("photons") : 0;
            final int tofDist = hasTof ? obj.getJSONObject("tof").getInt("dist_mm") : -1;

            // Simple ripeness classification based on literature indices
            final String ripeness = classifyRipeness(ndvi, rg, nirvis);

            handler.post(() -> {
                tvNDVI.setText(String.format("NDVI (chlorophyll): %.4f", ndvi));
                tvRG.setText(String.format("R/G (color shift):  %.4f", rg));
                tvNIRVIS.setText(String.format("NIR/VIS (water):    %.4f", nirvis));

                tvRed.setText(String.format("Red:   %,.0f", r));
                tvGreen.setText(String.format("Green: %,.0f", g));
                tvBlue.setText(String.format("Blue:  %,.0f", b));
                tvIR.setText(String.format("IR:    %,.0f", ir));
                tvCLR1.setText(String.format("CLR1:  %,.0f", c1));
                tvCLR2.setText(String.format("CLR2:  %,.0f", c2));

                tvLux.setText(String.format("Lux: %.0f  (gain=%.0f)", lux, gain));

                if (hasTof) {
                    tvToF.setText(String.format("940nm: %,d photons  dist: %dmm", tofPhotons, tofDist));
                }

                tvRipeness.setText(ripeness);

                // Color swatch from spectral ratios
                double vis = r + g + b;
                int cr = (int)Math.min(255, 255 * r / vis * 3);
                int cg = (int)Math.min(255, 255 * g / vis * 3);
                int cb = (int)Math.min(255, 255 * b / vis * 3);
                colorSwatch.setBackgroundColor(Color.rgb(cr, cg, cb));

                if (recording) {
                    tvRecordStatus.setText(String.format("REC %s #%d", recordLabel, recordCount));
                }
            });

            // Save to file if recording
            if (recording && recordStream != null) {
                try {
                    String augmented = json.substring(0, json.length() - 1)
                        + ",\"label\":{\"fruit\":\"" + recordLabel.split("_")[0]
                        + "\",\"stage\":\"" + (recordLabel.contains("_") ? recordLabel.split("_", 2)[1] : recordLabel)
                        + "\"},\"als\":" + ambientLux
                        + ",\"stable\":" + isStable + "}\n";
                    recordStream.write(augmented.getBytes());
                    recordCount++;
                } catch (Exception ex) {}
            }
        } catch (Exception e) {
            // skip malformed lines
        }
    }

    private void toggleRecording() {
        if (recording) {
            recording = false;
            recordBtn.setText("Record: OFF");
            tvRecordStatus.setText(String.format("Saved %d samples", recordCount));
            try { if (recordStream != null) { recordStream.close(); recordStream = null; } }
            catch (Exception e) {}
        } else {
            EditText input = new EditText(this);
            input.setHint("banana_green");
            new AlertDialog.Builder(this)
                .setTitle("Label for recording")
                .setMessage("Format: fruit_stage (e.g., banana_green, tomato_red)")
                .setView(input)
                .setPositiveButton("Start", (d, w) -> {
                    recordLabel = input.getText().toString().trim();
                    if (recordLabel.isEmpty()) recordLabel = "unlabeled";
                    String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                    String path = "/data/local/tmp/fruit_" + recordLabel + "_" + ts + ".jsonl";
                    try {
                        recordStream = new FileOutputStream(path);
                        recordCount = 0;
                        recording = true;
                        recordBtn.setText("Record: ON");
                        tvRecordStatus.setText("REC " + recordLabel);
                        tvStatus.setText("Recording to " + path);
                    } catch (Exception e) {
                        tvStatus.setText("Record failed: " + e.getMessage());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        }
    }

    private String classifyRipeness(double ndvi, double rg, double nirvis) {
        // Heuristic based on literature indices (calibrate with real fruit data)
        // These thresholds are for ambient light baseline, not fruit yet
        // Real classification needs training data from fruit captures
        if (rg > 1.2) return "OVERRIPE (high red)";
        if (rg > 1.0 && ndvi < 0.7) return "RIPE";
        if (rg > 0.95) return "TURNING";
        if (ndvi > 0.85) return "UNRIPE (high chlorophyll)";
        return String.format("AMBIENT (R/G=%.3f)", rg);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            ambientLux = event.values[0];
        } else if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            proximity = event.values[0];
        } else if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            float x = event.values[0], y = event.values[1], z = event.values[2];
            accelMag = (float) Math.sqrt(x*x + y*y + z*z);
            isStable = accelMag < 0.3f;
        } else if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
            pressure = event.values[0];
        }
        handler.post(() -> {
            String stability = isStable ? "STABLE" : String.format("MOVING (%.1f)", accelMag);
            String envLine = String.format("ALS:%.0flux Prox:%.0f %s", ambientLux, proximity, stability);
            if (pressure > 0) envLine += String.format(" %.0fhPa", pressure);
            tvAmbientALS.setText(envLine);
        });
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    protected void onResume() {
        super.onResume();
        openCamera();
        if (lightSensor != null)
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_UI);
        if (proxSensor != null)
            sensorManager.registerListener(this, proxSensor, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    protected void onPause() {
        closeCamera();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        connected = false;
        closeCamera();
        try { if (socket != null) socket.close(); } catch (Exception e) {}
    }

    // TextureView.SurfaceTextureListener implementation
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        createCameraPreview(surface);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    private void openCamera() {
        try {
            cameraThread = new HandlerThread("CameraBackground");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        } catch (Exception e) {
            tvStatus.setText("Camera error: " + e.getMessage());
        }
    }

    private void createCameraPreview(SurfaceTexture surfaceTexture) {
        try {
            // Set up ImageReader for frame captures (640x480)
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.NV21, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Image img = reader.acquireLatestImage();
                if (img != null) img.close();
            }, cameraHandler);

            // Open rear camera (ID "0" on Pixel 7 Pro)
            String cameraId = "0";
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    startPreview(surfaceTexture);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    handler.post(() -> tvStatus.setText("Camera error: " + error));
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            handler.post(() -> tvStatus.setText("Camera access denied"));
        }
    }

    private void startPreview(SurfaceTexture surfaceTexture) {
        try {
            Surface previewSurface = new Surface(surfaceTexture);
            CaptureRequest.Builder previewBuilder = cameraDevice.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);

            cameraDevice.createCaptureSession(
                new ArrayList<Surface>() {{ add(previewSurface); add(imageReader.getSurface()); }},
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            session.setRepeatingRequest(previewBuilder.build(), null, cameraHandler);
                        } catch (CameraAccessException e) {
                            tvStatus.setText("Preview start error");
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        tvStatus.setText("Preview config failed");
                    }
                }, cameraHandler);
        } catch (CameraAccessException e) {
            handler.post(() -> tvStatus.setText("Preview error"));
        }
    }

    private void captureFrame() {
        if (cameraDevice == null || captureSession == null) {
            tvStatus.setText("Camera not ready");
            return;
        }

        try {
            // Capture a single frame to JPEG
            CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());

            captureSession.capture(captureBuilder.build(),
                new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(CameraCaptureSession session,
                            CaptureRequest request, android.hardware.camera2.TotalCaptureResult result) {
                        // Read captured frame
                        Image image = imageReader.acquireLatestImage();
                        if (image != null) {
                            saveImageAsJpeg(image);
                            image.close();
                        }
                    }
                }, cameraHandler);
        } catch (CameraAccessException e) {
            tvStatus.setText("Capture failed");
        }
    }

    private void saveImageAsJpeg(Image image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File file = new File("/data/local/tmp/ripeness_capture_" + timestamp + ".jpg");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(data);
            fos.close();

            handler.post(() -> tvStatus.setText("Captured: " + file.getName()));
        } catch (Exception e) {
            handler.post(() -> tvStatus.setText("Save failed: " + e.getMessage()));
        }
    }

    private void closeCamera() {
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
                captureSession.close();
            } catch (CameraAccessException e) {}
            captureSession = null;
        }

        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join();
            } catch (InterruptedException e) {}
            cameraThread = null;
        }
        cameraHandler = null;
    }
}
