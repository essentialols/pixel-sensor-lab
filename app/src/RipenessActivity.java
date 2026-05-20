package com.spectral.ripeness;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

public class RipenessActivity extends Activity
        implements TextureView.SurfaceTextureListener, SensorEventListener {

    private TextView tvStatus, tvRipeness, tvLux, tvEnv;
    private TextView tvNDVI, tvRG, tvNIRVIS;
    private View colorSwatch, readyIndicator;
    private SpectrumBarView spectrumBars;
    private Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean connected = false;
    private Socket socket;

    // Camera2
    private TextureView textureView;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Handler cameraHandler;
    private HandlerThread cameraThread;

    // Android sensors
    private SensorManager sensorManager;
    private Sensor lightSensor, proxSensor, accelSensor, pressureSensor;
    private float ambientLux = -1, proximity = -1, accelMag = 0, pressure = -1;
    private boolean isStable = false;

    // Torch
    private boolean torchOn = false;
    private Button torchBtn;

    // Recording
    private boolean recording = false;
    private FileOutputStream recordStream;
    private String recordLabel = "";
    private int recordCount = 0;
    private Button recordBtn;
    private TextView tvRecordInfo;
    private long recordStartTime = 0;

    // Custom view for spectral bar chart
    static class SpectrumBarView extends View {
        private Paint paint = new Paint();
        private float[] values = new float[6];
        private float maxVal = 1;
        private String[] labels = {"R", "G", "B", "IR", "C1", "C2"};
        private int[] colors = {0xFFFF4444, 0xFF44FF44, 0xFF4488FF, 0xFFFF8800, 0xFFCCCCCC, 0xFFAAAA88};

        public SpectrumBarView(Context ctx) { super(ctx); paint.setAntiAlias(true); }

        public void setValues(float r, float g, float b, float ir, float c1, float c2) {
            values[0] = r; values[1] = g; values[2] = b; values[3] = ir; values[4] = c1; values[5] = c2;
            maxVal = Math.max(1f, Math.max(ir, Math.max(c2, Math.max(r, Math.max(g, Math.max(b, c1))))));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;

            float barW = (w - 14f) / 6f;
            float labelH = 36f;

            for (int i = 0; i < 6; i++) {
                float x = 2 + i * (barW + 2);
                float barH = (h - labelH - 4) * (values[i] / maxVal);
                float y = h - labelH - barH;

                paint.setColor(colors[i]);
                paint.setAlpha(200);
                canvas.drawRect(x, y, x + barW, h - labelH, paint);

                paint.setColor(colors[i]);
                paint.setAlpha(255);
                paint.setTextSize(24f);
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(labels[i], x + barW / 2, h - 8, paint);

                paint.setTextSize(18f);
                paint.setColor(0xFFFFFFFF);
                String valStr = values[i] > 1000000 ? String.format("%.1fM", values[i] / 1e6) :
                                values[i] > 1000 ? String.format("%.0fK", values[i] / 1e3) :
                                String.format("%.0f", values[i]);
                canvas.drawText(valStr, x + barW / 2, y - 4, paint);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0D1117);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 32, 24, 24);

        // Header row: title + ready indicator
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        readyIndicator = new View(this);
        readyIndicator.setBackgroundColor(0xFF444444);
        header.addView(readyIndicator, new LinearLayout.LayoutParams(24, 24));
        TextView title = makeLabel("  Fruit Ripeness", 20, "#58A6FF");
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(header);

        tvStatus = makeLabel("Connecting...", 12, "#8B949E");
        root.addView(tvStatus);

        // Camera preview (compact)
        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(this);
        LinearLayout.LayoutParams camP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 300);
        camP.setMargins(0, 8, 0, 0);
        root.addView(textureView, camP);

        // Record row
        LinearLayout recRow = new LinearLayout(this);
        recRow.setOrientation(LinearLayout.HORIZONTAL);
        recRow.setGravity(Gravity.CENTER_VERTICAL);
        recordBtn = new Button(this);
        recordBtn.setText("REC");
        recordBtn.setTextColor(0xFFFFFFFF);
        recordBtn.setBackgroundColor(0xFF21262D);
        recordBtn.setOnClickListener(v -> toggleRecording());
        recRow.addView(recordBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        torchBtn = new Button(this);
        torchBtn.setText("LIGHT");
        torchBtn.setTextColor(0xFFFFFFFF);
        torchBtn.setBackgroundColor(0xFF21262D);
        torchBtn.setOnClickListener(v -> toggleTorch());
        recRow.addView(torchBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button snapBtn = new Button(this);
        snapBtn.setText("SNAP");
        snapBtn.setTextColor(0xFFFFFFFF);
        snapBtn.setBackgroundColor(0xFF21262D);
        snapBtn.setOnClickListener(v -> captureFrame());
        recRow.addView(snapBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        tvRecordInfo = makeLabel(" ", 12, "#F85149");
        recRow.addView(tvRecordInfo, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        root.addView(recRow);

        // Ripeness verdict (large, central)
        tvRipeness = makeLabel("---", 28, "#3FB950");
        tvRipeness.setGravity(Gravity.CENTER);
        tvRipeness.setPadding(0, 8, 0, 8);
        root.addView(tvRipeness);

        // Color swatch + lux
        LinearLayout swatchRow = new LinearLayout(this);
        swatchRow.setOrientation(LinearLayout.HORIZONTAL);
        colorSwatch = new View(this);
        colorSwatch.setBackgroundColor(Color.DKGRAY);
        swatchRow.addView(colorSwatch, new LinearLayout.LayoutParams(0, 60, 1));
        tvLux = makeLabel("---", 14, "#C9D1D9");
        tvLux.setGravity(Gravity.CENTER);
        swatchRow.addView(tvLux, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(swatchRow);

        // Spectral bar chart
        root.addView(makeLabel("Spectral Channels", 14, "#58A6FF"));
        spectrumBars = new SpectrumBarView(this);
        LinearLayout.LayoutParams barP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 180);
        barP.setMargins(0, 4, 0, 8);
        root.addView(spectrumBars, barP);

        // Indices row (compact horizontal)
        LinearLayout idxRow = new LinearLayout(this);
        idxRow.setOrientation(LinearLayout.HORIZONTAL);
        tvNDVI = makeLabel("NDVI: ---", 13, "#C9D1D9");
        idxRow.addView(tvNDVI, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        tvRG = makeLabel("R/G: ---", 13, "#C9D1D9");
        idxRow.addView(tvRG, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        tvNIRVIS = makeLabel("NIR: ---", 13, "#C9D1D9");
        idxRow.addView(tvNIRVIS, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(idxRow);

        // Environment row
        tvEnv = makeLabel("ALS: --- | Dist: --- | ---", 12, "#8B949E");
        root.addView(tvEnv);

        scroll.addView(root);
        setContentView(scroll);

        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        proxSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);

        startDaemonConnection();
    }

    private TextView makeLabel(String text, int sp, String color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(Color.parseColor(color));
        tv.setPadding(0, 2, 0, 2);
        return tv;
    }

    private void startDaemonConnection() {
        new Thread(() -> {
            while (!connected && !isFinishing()) {
                try {
                    socket = new Socket("127.0.0.1", 8765);
                    connected = true;
                    handler.post(() -> tvStatus.setText("Connected"));
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null && !isFinishing()) {
                        processJson(line);
                    }
                } catch (Exception e) {
                    handler.post(() -> tvStatus.setText("Waiting for daemon..."));
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

            final float r = (float) raw.getDouble("R");
            final float g = (float) raw.getDouble("G");
            final float b = (float) raw.getDouble("B");
            final float ir = (float) raw.getDouble("IR");
            final float c1 = (float) raw.getDouble("CLR1");
            final float c2 = (float) raw.getDouble("CLR2");
            final double gain = obj.getDouble("gain");
            final double lux = obj.optDouble("lux", (g / gain) / 109.58);
            final long seq = obj.optLong("seq", 0);

            final double ndvi = idx.getDouble("NDVI");
            final double rg = idx.getDouble("RG");
            final double nirvis = idx.getDouble("NIR_VIS");

            final boolean hasTof = obj.has("tof");
            final int tofDist = hasTof ? obj.getJSONObject("tof").optInt("dist_mm", -1) : -1;
            final int tofPhotons = hasTof ? obj.getJSONObject("tof").optInt("photons", 0) : 0;

            final String ripeness = classifyRipeness(ndvi, rg, nirvis);

            // Measurement readiness: stable + good distance (30-150mm)
            final boolean distOk = tofDist > 30 && tofDist < 150;
            final boolean ready = isStable && distOk;

            handler.post(() -> {
                spectrumBars.setValues(r, g, b, ir, c1, c2);

                tvNDVI.setText(String.format("NDVI:%.3f", ndvi));
                tvRG.setText(String.format("R/G:%.3f", rg));
                tvNIRVIS.setText(String.format("NIR:%.2f", nirvis));

                tvLux.setText(String.format("%.0f lux  g=%.0f", lux, gain));
                tvRipeness.setText(ripeness);

                // Color swatch from spectral fractions
                float vis = r + g + b;
                if (vis > 0) {
                    int cr = clamp((int)(255 * r / vis * 2.5f), 0, 255);
                    int cg = clamp((int)(255 * g / vis * 2.5f), 0, 255);
                    int cb = clamp((int)(255 * b / vis * 2.5f), 0, 255);
                    colorSwatch.setBackgroundColor(Color.rgb(cr, cg, cb));
                }

                // Ready indicator
                readyIndicator.setBackgroundColor(ready ? 0xFF3FB950 : isStable ? 0xFFD29922 : 0xFF444444);

                // Environment line
                String distStr = tofDist > 0 ? tofDist + "mm" : "---";
                String photStr = tofPhotons > 0 ? tofPhotons + "ph" : "";
                String stability = isStable ? "STABLE" : String.format("%.1f", accelMag);
                tvEnv.setText(String.format("ALS:%.0f  Dist:%s %s  %s  #%d",
                    ambientLux, distStr, photStr, stability, seq));

                if (recording) {
                    long elapsed = (System.currentTimeMillis() - recordStartTime) / 1000;
                    tvRecordInfo.setText(String.format("REC %s  %ds  #%d", recordLabel, elapsed, recordCount));
                }
            });

            // Save to file if recording
            if (recording && recordStream != null) {
                try {
                    String augmented = json.substring(0, json.length() - 1)
                        + ",\"label\":{\"fruit\":\"" + recordLabel.split("_")[0]
                        + "\",\"stage\":\"" + (recordLabel.contains("_") ? recordLabel.split("_", 2)[1] : recordLabel)
                        + "\"},\"als\":" + ambientLux
                        + ",\"stable\":" + isStable
                        + ",\"pressure\":" + pressure + "}\n";
                    recordStream.write(augmented.getBytes());
                    recordCount++;
                } catch (Exception ex) {}
            }
        } catch (Exception e) {}
    }

    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private String classifyRipeness(double ndvi, double rg, double nirvis) {
        if (rg > 1.2) return "OVERRIPE";
        if (rg > 1.0 && ndvi < 0.7) return "RIPE";
        if (rg > 0.95) return "TURNING";
        if (ndvi > 0.85) return "UNRIPE";
        return String.format("R/G=%.3f", rg);
    }

    private void toggleTorch() {
        try {
            torchOn = !torchOn;
            cameraManager.setTorchMode("0", torchOn);
            torchBtn.setText(torchOn ? "LIGHT ON" : "LIGHT");
            torchBtn.setBackgroundColor(torchOn ? 0xFFD29922 : 0xFF21262D);
        } catch (Exception e) {
            tvStatus.setText("Torch error: " + e.getMessage());
        }
    }

    private void toggleRecording() {
        if (recording) {
            recording = false;
            recordBtn.setText("REC");
            recordBtn.setBackgroundColor(0xFF21262D);
            tvRecordInfo.setText(String.format("Saved %d samples", recordCount));
            try { if (recordStream != null) { recordStream.close(); recordStream = null; } }
            catch (Exception e) {}
        } else {
            EditText input = new EditText(this);
            input.setHint("banana_green");
            new AlertDialog.Builder(this)
                .setTitle("Label")
                .setMessage("fruit_stage (e.g., banana_green)")
                .setView(input)
                .setPositiveButton("Start", (d, w) -> {
                    recordLabel = input.getText().toString().trim();
                    if (recordLabel.isEmpty()) recordLabel = "unlabeled";
                    String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                    String path = "/data/local/tmp/fruit_" + recordLabel + "_" + ts + ".jsonl";
                    try {
                        recordStream = new FileOutputStream(path);
                        recordCount = 0;
                        recordStartTime = System.currentTimeMillis();
                        recording = true;
                        recordBtn.setText("STOP");
                        recordBtn.setBackgroundColor(0xFFF85149);
                        tvRecordInfo.setText("REC " + recordLabel);
                        tvStatus.setText("Recording: " + path);
                    } catch (Exception e) {
                        tvStatus.setText("Error: " + e.getMessage());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        }
    }

    // SensorEventListener
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
        if (accelSensor != null)
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI);
        if (pressureSensor != null)
            sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_UI);
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
        try { if (socket != null) socket.close(); } catch (Exception e) {}
    }

    // TextureView.SurfaceTextureListener
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        openCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return false; }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    private void openCamera() {
        try {
            cameraThread = new HandlerThread("CameraBackground");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        } catch (Exception e) {}
    }

    private void createCameraPreview(SurfaceTexture surfaceTexture) {
        try {
            surfaceTexture.setDefaultBufferSize(640, 480);
            Surface surface = new Surface(surfaceTexture);
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);
            surfaces.add(imageReader.getSurface());
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    startPreview(surfaceTexture);
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {}
            }, cameraHandler);
        } catch (Exception e) {}
    }

    private void startPreview(SurfaceTexture surfaceTexture) {
        try {
            surfaceTexture.setDefaultBufferSize(640, 480);
            Surface surface = new Surface(surfaceTexture);
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureSession.setRepeatingRequest(builder.build(), null, cameraHandler);
        } catch (Exception e) {}
    }

    private void captureFrame() {
        if (cameraDevice == null || imageReader == null) return;
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(imageReader.getSurface());
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    saveImageAsJpeg(image);
                    image.close();
                }
            }, cameraHandler);
            captureSession.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {}, cameraHandler);
        } catch (Exception e) {}
    }

    private void saveImageAsJpeg(Image image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String path = "/data/local/tmp/ripeness_snap_" + ts + ".jpg";
            FileOutputStream fos = new FileOutputStream(path);
            fos.write(bytes);
            fos.close();
            handler.post(() -> tvStatus.setText("Saved: " + path));
        } catch (Exception e) {}
    }

    private void closeCamera() {
        try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception e) {}
        try { if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; } } catch (Exception e) {}
        try { if (imageReader != null) { imageReader.close(); imageReader = null; } } catch (Exception e) {}
        if (cameraThread != null) { cameraThread.quitSafely(); cameraThread = null; }
    }
}
