package me.amrbashir.raphael.fingerprint.calibration;

import android.os.Process;
import android.util.Base64;
import android.util.Log;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed root calibration entry point, loaded from the companion APK. */
public final class CalibrationSession {
    static final String LIB = "/vendor/lib64/vendor.goodix.hardware.biometrics.fingerprint@2.1.so";
    static final String EXPECTED = "a5f006214e25bd16aa6678a1f0952ebdc0d439474a8a4c5b6416f008953cc294";

    final BlockingQueue<String> input = new LinkedBlockingQueue<String>();
    final BlockingQueue<String> drawings = new LinkedBlockingQueue<String>();
    final AtomicBoolean cancelled = new AtomicBoolean();
    final ExecutorService ipc = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "factory-ipc");
        t.setDaemon(true);
        return t;
    });

    GoodixFactory factory;
    boolean attached, started, exited;
    int dpi;
    DisplayProfile displayProfile;

    public static void main(String[] args) {
        CalibrationSession session = new CalibrationSession();
        int status = 1;

        try {
            if (args.length != 0 || Process.myUid() != 0) throw new IOException("Enable root access for Raphael Fingerprint Calibration in KernelSU.");

            // Kernel releases this lock even if the process dies; no PID or report files are needed.
            try (RandomAccessFile file = new RandomAccessFile("/data/adb/raphael-calibration.lock", "rw"); FileLock lock = file.getChannel().tryLock()) {
                if (lock == null) throw new IOException("Another calibration is already running.");
                status = session.execute();
            }
        } catch (Exception failure) {
            emit("ERROR " + message(failure));
        } finally {
            session.ipc.shutdownNow();
        }

        System.exit(status);
    }

    static void emit(String event) {
        System.out.println(event);
    }

    static String message(Exception failure) {
        if (failure instanceof FileNotFoundException || failure instanceof FileSystemException) return "The required sensor or display controls are unavailable.";
        if (failure instanceof NumberFormatException) return "This device configuration is not supported.";

        String text = failure.getMessage();
        return text == null ? "Calibration could not complete. Reboot before trying again." : text.replace('\n', ' ').replace('\r', ' ');
    }

    int execute() {
        String result = "SUCCESS";
        int status = 0;

        try {
            reader();
            run();
        } catch (InterruptedException cancelled) {
            result = started ? "STOPPED Reboot before trying again." : "STOPPED Calibration did not start.";
            status = 1;
        } catch (Exception failure) {
            String detail = message(failure);
            if (started && !detail.contains("Reboot")) detail += " Reboot before trying again.";
            result = "ERROR " + detail;
            status = 1;
        } finally {
            if (!cleanup()) {
                result = "ERROR Restart your phone before using the fingerprint sensor.";
                status = 1;
            }
        }

        emit(result);
        return status;
    }

    void reader() {
        Thread thread = new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                String line;

                while ((line = reader.readLine()) != null) {
                    if (line.equals("CANCEL")) {
                        cancelled.set(true);
                        return;
                    }

                    if (line.length() > 512) throw new IOException("Invalid app command");
                    if (line.startsWith("DRAWN ")) drawings.offer(line); else input.offer(line);
                }
            } catch (IOException ignored) {
            } finally {
                cancelled.set(true);
            }
        }, "ui-input");

        thread.setDaemon(true);
        thread.start();
    }

    void check() throws InterruptedException {
        if (cancelled.get()) throw new InterruptedException();
    }

    String receive(long seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);

        while (System.nanoTime() < deadline) {
            check();
            String value = input.poll(100, TimeUnit.MILLISECONDS);
            if (value != null) return value;
        }

        throw new TimeoutException("Calibration timed out. Reopen the app to try again.");
    }

    <T> T call(Callable<T> fn, boolean cancellable) throws Exception {
        Future<T> future = ipc.submit(fn);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);

        while (System.nanoTime() < deadline) {
            if (cancellable) check();

            try {
                return future.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException waiting) {
            } catch (ExecutionException failure) {
                throw new IOException("Could not communicate with the fingerprint sensor.");
            }
        }

        throw new TimeoutException("The fingerprint sensor did not respond. Reboot before trying again.");
    }

    static String prop(String name) throws Exception {
        java.lang.Process process = new ProcessBuilder("/system/bin/getprop", name).start();
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroy();
            throw new IOException("Could not read device configuration.");
        }

        String value = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).readLine();
        return value == null ? "" : value.trim();
    }

    static String sha(String path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Paths.get(path)));
        StringBuilder value = new StringBuilder();

        for (byte b : digest)
            value.append(String.format("%02x", b & 255));

        return value.toString();
    }

    void run() throws Exception {
        String[] hello = receive(30).split(" ");
        if (hello.length != 4 || !hello[0].equals("HELLO")) throw new IOException("Could not connect to the app.");

        int width = Integer.parseInt(hello[1]), height = Integer.parseInt(hello[2]);
        dpi = Integer.parseInt(hello[3]);
        if (width != 1080 || height != 2340 || dpi < 200 || dpi > 700) throw new IOException("Use the phone's original display resolution in portrait.");

        String device = prop("ro.product.device");
        if (!device.equals("raphael") && !device.equals("raphaelin")) throw new IOException("This app requires a Raphael device.");
        if (!EXPECTED.equals(sha(LIB))) throw new IOException("Enable the mounting metamodule and reboot.");

        String xy = prop("persist.vendor.sys.fp.fod.location.X_Y"), size = prop("persist.vendor.sys.fp.fod.size.width_height");
        int x, y, w, h;

        if (xy.matches("[0-9]+,[0-9]+") && size.matches("[0-9]+,[0-9]+")) {
            String[] a = xy.split(","), b = size.split(",");
            x = Integer.parseInt(a[0]);
            y = Integer.parseInt(a[1]);
            w = Integer.parseInt(b[0]);
            h = Integer.parseInt(b[1]);
        } else {
            String location = prop("persist.vendor.fingerprint.sensor_location");
            if (!location.matches("[0-9]+[|][0-9]+[|][0-9]+")) throw new IOException("The fingerprint sensor position is unavailable.");

            String[] a = location.split("\\|");
            int radius = Integer.parseInt(a[2]);
            x = Integer.parseInt(a[0]) - radius;
            y = Integer.parseInt(a[1]) - radius;
            w = h = 2 * radius;
        }

        if (w < 100 || w > 300 || h < 100 || h > 300 || x < 0 || y < 1000 || x + w > width || y + h > height) throw new IOException("The fingerprint sensor position is invalid.");
        emit("GEOMETRY " + x + " " + y + " " + w + " " + h);

        displayProfile = DisplayProfiles.open(x, y, w, h);

        factory = call(() -> new GoodixFactory(), true);
        attached = true;
        call(() -> {
            factory.attach();
            return null;
        }, true);

        factory.callback.arm(1549);
        if (call(() -> factory.send(1549, new byte[0]), true) != 0) throw new IOException("The fingerprint sensor is unavailable.");
        GoodixFactory.checkConfig(await(1549));

        call(() -> {
            factory.detach();
            return null;
        }, true);
        attached = false;

        emit("READY");
        while (true) {
            String[] request = receive(300).split(" ", 3);
            if (request[0].equals("DISPLAY") && request.length == 2) {
                if (displayProfile != null) displayProfile.apply(request[1]);
            } else if (request[0].equals("PICTURE") && request.length == 2) {
                String mode = request[1];
                if (!mode.equals("SPOT") && !mode.equals("CHART")) throw new IOException("Invalid calibration picture");
                emit("PICTURE " + mode);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                String drawn = null;
                while (System.nanoTime() < deadline) {
                    check();
                    drawn = drawings.poll(100, TimeUnit.MILLISECONDS);
                    if (drawn != null) break;
                }
                if (!("DRAWN " + mode).equals(drawn)) throw new IOException("The calibration image was not displayed.");
            } else if (request[0].equals("TEST") && request.length == 3) {
                int command = Integer.parseInt(request[1]);
                byte[] params = request[2].equals("-") ? new byte[0] : Base64.decode(request[2], Base64.NO_WRAP);
                if (!GoodixFactory.allowed(command) || params.length > 64) throw new IOException("Invalid factory command");
                if (command == 1571 && !started) {
                    emit("FINISHED");
                    continue;
                }
                if (!started) {
                    if (displayProfile == null) displayProfile = DisplayProfiles.open(x, y, w, h);
                    displayProfile.prepare();
                    attached = true;
                    call(() -> { factory.attach(); return null; }, true);
                    started = true;
                    exited = false;
                }
                displayProfile.verifyState();
                Log.d("GoodixRootCalibration", "testCmd " + command);
                if (command != 1571) factory.callback.arm(command);
                if (call(() -> factory.send(command, params), true) != 0) throw new IOException("Factory command was rejected: " + command);
                if (command == 1571) {
                    exited = true;
                    if (!cleanup()) throw new IOException("Restart your phone before using the fingerprint sensor.");
                    started = false;
                    attached = false;
                    displayProfile = null;
                    drawings.clear();
                    emit("FINISHED");
                } else {
                    ArrayList<Byte> response = await(command);
                    byte[] bytes = new byte[response.size()];
                    for (int i = 0; i < bytes.length; i++) bytes[i] = response.get(i);
                    emit("RESULT " + command + " " + Base64.encodeToString(bytes, Base64.NO_WRAP));
                }
            } else {
                throw new IOException("Invalid calibration request");
            }
        }
    }

    ArrayList<Byte> await(int command) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);

        while (System.nanoTime() < deadline) {
            check();
            if (factory.callback.failure != null) throw new IOException("The fingerprint sensor returned an invalid response.");
            if (factory.callback.payload != null) return factory.callback.payload;
            Thread.sleep(30);
        }

        throw new TimeoutException("The fingerprint sensor did not respond. Reboot before trying again.");
    }

    static void write(String path, String value) throws IOException {
        try (FileOutputStream file = new FileOutputStream(path)) {
            file.write(value.getBytes(StandardCharsets.US_ASCII));
        }
    }

    boolean cleanup() {
        boolean ok = true;
        // Restore the display before any potentially blocked Binder transaction.
        if (displayProfile != null) ok = displayProfile.restore();

        if (started && !exited && factory != null) {
            try {
                if (call(() -> factory.send(1566, new byte[0]), false) != 0) ok = false;
            } catch (Exception failure) {
                ok = false;
            }

            try {
                if (call(() -> factory.send(1571, new byte[0]), false) != 0) ok = false;
            } catch (Exception failure) {
                ok = false;
            }
        }

        if (attached && factory != null)
            try {
                call(() -> {
                    factory.detach();
                    return null;
                }, false);
            } catch (Exception failure) {
                ok = false;
            }

        return ok;
    }
}
