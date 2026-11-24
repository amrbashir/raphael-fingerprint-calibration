package me.amrbashir.raphael.fingerprint.calibration;

import java.nio.file.*;

/** Select only known implementations, finishing read-only preflight before returning. */
final class DisplayProfiles {
    static final String BRIGHTNESS = "/sys/class/backlight/panel0-backlight/brightness";

    static DisplayProfile open(int x, int y, int width, int height) throws Exception {
        // Preserve stock-node priority; failed preflight must never try another profile.
        if (Files.exists(Paths.get(StockDisplayProfile.CONTROL))) return StockDisplayProfile.open();

        return BacklightDisplayProfile.open(x, y, width, height);
    }
}
