package me.amrbashir.raphael.fingerprint.calibration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Stock MIUI display commands are passed directly to disp_param. */
final class StockDisplayProfile implements DisplayProfile {
    static final String CONTROL = "/sys/class/drm/card0-DSI-1/disp_param";

    private String oldBrightness;
    private boolean changed;

    static StockDisplayProfile open() throws IOException {
        if (!Files.isWritable(Paths.get(CONTROL)) || !Files.isWritable(Paths.get(DisplayProfiles.BRIGHTNESS))) throw new IOException("Display controls are unavailable.");

        StockDisplayProfile profile = new StockDisplayProfile();
        profile.oldBrightness = new String(Files.readAllBytes(Paths.get(DisplayProfiles.BRIGHTNESS)), StandardCharsets.US_ASCII).trim();
        if (!profile.oldBrightness.matches("[0-9]{1,5}")) throw new IOException("Could not save the current screen brightness.");

        return profile;
    }

    // Stock disp_param owns brightness; no separate HBM preparation or readback is used.
    public void prepare() {}

    public void verifyState() {}

    public void apply(String operation) throws IOException {
        changed = true;
        CalibrationSession.write(CONTROL, operation);
    }

    public boolean restore() {
        if (!changed) return true;

        boolean ok = true;

        for (String operation : new String[] {"0xF0000", "0xD01000", "0x2000000"})
            try {
                CalibrationSession.write(CONTROL, operation);
            } catch (Exception failure) {
                ok = false;
            }

        try {
            CalibrationSession.write(DisplayProfiles.BRIGHTNESS, oldBrightness);
        } catch (Exception failure) {
            ok = false;
        }

        return ok;
    }
}
