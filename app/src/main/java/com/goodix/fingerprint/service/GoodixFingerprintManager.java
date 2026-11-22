package com.goodix.fingerprint.service;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import com.goodix.fingerprint.utils.ShenzhenTestResultParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import me.amrbashir.raphael.fingerprint.calibration.CalibrationPatternView;
import me.amrbashir.raphael.fingerprint.calibration.R;

/** Replaces Xiaomi's framework service with the module's root HIDL/display broker. */
public final class GoodixFingerprintManager {
    private static final String TAG = "GoodixFingerprintManager";
    private static GoodixFingerprintManager instance;

    public interface TestCmdCallback {
        void onTestCmd(int command, HashMap<Integer, Object> result);
        void onSessionClosed();
    }

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CalibrationPatternView pattern;
    private final Button start;
    private final TextView tips;
    private TestCmdCallback callback;
    private BufferedWriter commands;
    private boolean closing;
    private boolean testRunning;
    private long runId, exitingRunId;

    private GoodixFingerprintManager(Activity activity) {
        this.activity = activity;
        start = activity.findViewById(R.id.start_test_btn);
        tips = activity.findViewById(R.id.tips_txt);
        start.setEnabled(false);
        pattern = new CalibrationPatternView(activity, mode -> send("DRAWN " + mode));
        ViewGroup content = activity.findViewById(android.R.id.content);
        content.addView(pattern, new ViewGroup.LayoutParams(-1, -1));
        content.post(this::connect);
    }

    public static GoodixFingerprintManager getFingerprintManager(Activity activity) {
        instance = new GoodixFingerprintManager(activity);
        return instance;
    }

    public static GoodixFingerprintManager current() {
        return instance;
    }

    public void registerTestCmdCallback(TestCmdCallback callback) {
        this.callback = callback;
    }

    public void unregisterTestCmdCallback(TestCmdCallback callback) {
        if (this.callback == callback) this.callback = null;
        close();
        if (instance == this) instance = null;
    }

    public void showSensorViewWindow(boolean show) {
        pattern.showSensor(show);
    }

    public void setSensorAreaToBackgroundColor() {
        pattern.invalidate();
    }

    public void setSPMTMode(boolean enabled) {
        // Leaving the calibration screen must restore the hardware, even while a callback is pending.
        if (!enabled && testRunning) fail("Calibration stopped. Reopen the app to try again.");
    }

    public void showChartView(boolean show) {
        send("PICTURE " + (show ? "CHART" : "SPOT"));
    }

    public void setHBMMode(boolean enabled) {
        writeBacklight(enabled ? "0x20000" : "0xF0000");
    }

    public void setScreenBrightnessR15(int brightness) {
        writeBacklight("0x1000000");
        writeBacklight(String.format("0xD0%04X", brightness));
    }

    public void writeBacklight(String operation) {
        // The root broker restores these controls itself when the Activity exits.
        if (operation.equals("0xD01000") || operation.equals("0x2000000")) return;
        send("DISPLAY " + operation);
    }

    public void testCmd(int command) {
        testCmd(command, null);
    }

    public void testCmd(int command, byte[] parameters) {
        Log.d(TAG, "testCmd " + command);
        if (command == 1569) { runId++; testRunning = true; }
        if (command == 1571) exitingRunId = runId;
        String encoded = parameters == null || parameters.length == 0 ? "-" : Base64.encodeToString(parameters, Base64.NO_WRAP);
        send("TEST " + command + " " + encoded);
    }

    private void connect() {
        if (closing) return;
        DisplayMetrics display = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(display);

        new Thread(() -> {
            try {
                String command = "CLASSPATH=/data/adb/modules/raphael_fingerprint_calibration/raphael-fingerprint-calibration.apk " +
                    "/system/bin/app_process /system/bin me.amrbashir.raphael.fingerprint.calibration.CalibrationSession";
                Process process = new ProcessBuilder("su", "-c", command).redirectError(new File("/dev/null")).start();
                try (BufferedWriter output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                     BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    synchronized (this) {
                        commands = output;
                        send(closing ? "CANCEL" : "HELLO " + display.widthPixels + " " + display.heightPixels + " " + display.densityDpi);
                    }
                    String line;
                    while ((line = input.readLine()) != null) {
                        String event = line;
                        main.post(() -> handleEvent(event));
                    }
                    process.waitFor();
                }
            } catch (Exception failure) {
                Log.e(TAG, "Root calibration connection failed", failure);
            } finally {
                synchronized (this) { commands = null; }
                main.post(() -> {
                    if (!closing) fail("Root calibration helper exited. Reopen the app to try again.");
                });
            }
        }, "goodix-calibration").start();
    }

    private void handleEvent(String event) {
        if (closing || activity.isDestroyed()) return;
        try {
            if (event.startsWith("GEOMETRY ")) {
                String[] values = event.split(" ");
                pattern.setGeometry(Integer.parseInt(values[1]), Integer.parseInt(values[2]), Integer.parseInt(values[3]), Integer.parseInt(values[4]));
            } else if (event.equals("READY")) {
                start.setEnabled(true);
            } else if (event.startsWith("PICTURE ")) {
                pattern.showPicture(event.substring(8));
            } else if (event.startsWith("RESULT ")) {
                String[] result = event.split(" ", 3);
                int command = Integer.parseInt(result[1]);
                byte[] payload = Base64.decode(result[2], Base64.NO_WRAP);
                HashMap<Integer, Object> parsed = ShenzhenTestResultParser.parse(payload);
                if (callback != null) callback.onTestCmd(command, parsed);
            } else if (event.equals("FINISHED")) {
                if (exitingRunId == runId) testRunning = false;
            } else if (event.startsWith("ERROR ") || event.startsWith("STOPPED ")) {
                fail(event.substring(event.indexOf(' ') + 1));
            }
        } catch (RuntimeException failure) {
            Log.e(TAG, "Cannot handle calibration response", failure);
            fail("Calibration response could not be read. Reboot before trying again.");
        }
    }

    private void fail(String message) {
        Log.e(TAG, message);
        tips.setText(message);
        close();
    }

    private synchronized void send(String command) {
        if (closing && !command.equals("CANCEL")) return;
        try {
            if (commands != null) {
                commands.write(command);
                commands.newLine();
                commands.flush();
            }
        } catch (IOException failure) {
            Log.e(TAG, "Cannot send calibration command", failure);
        }
    }

    private synchronized void close() {
        if (closing) return;
        closing = true;
        if (callback != null) callback.onSessionClosed();
        send("CANCEL");
    }
}
