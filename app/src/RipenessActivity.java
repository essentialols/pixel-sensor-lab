package com.spectral.ripeness;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.util.TypedValue;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import org.json.JSONObject;

/**
 * Fruit Ripeness Analyzer
 *
 * Connects to ripeness_daemon on localhost:8765, displays live
 * spectral data + ripeness indices.
 */
public class RipenessActivity extends Activity {

    private TextView tvStatus, tvNDVI, tvRG, tvNIRVIS, tvRipeness;
    private TextView tvRed, tvGreen, tvBlue, tvIR, tvCLR1, tvCLR2;
    private ProgressBar pbRed, pbGreen, pbBlue, pbIR;
    private TextView tvToF, tvLux;
    private View colorSwatch;
    private Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean connected = false;
    private Socket socket;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);
        root.setBackgroundColor(Color.parseColor("#1a1a2e"));

        // Title
        TextView title = makeLabel("Fruit Ripeness Analyzer", 24, "#e94560");
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        tvStatus = makeLabel("Connecting to daemon...", 14, "#888888");
        tvStatus.setGravity(Gravity.CENTER);
        root.addView(tvStatus);

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

        // ToF + Lux
        root.addView(makeLabel("940nm + Lux", 16, "#e94560"));
        tvToF = makeLabel("ToF: ---", 14, "#ffaa00");
        root.addView(tvToF);
        tvLux = makeLabel("Lux: ---", 14, "#ffffff");
        root.addView(tvLux);

        setContentView(root);
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
            });
        } catch (Exception e) {
            // skip malformed lines
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
    protected void onDestroy() {
        super.onDestroy();
        connected = false;
        try { if (socket != null) socket.close(); } catch (Exception e) {}
    }
}
