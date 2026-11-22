package me.amrbashir.raphael.fingerprint.calibration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Select only known implementations, finishing read-only preflight before returning. */
final class DisplayProfiles {
    static final String BRIGHTNESS = "/sys/class/backlight/panel0-backlight/brightness";

    static DisplayProfile open(int x, int y, int width, int height) throws Exception {
        // Preserve stock-node priority; failed preflight must never try another profile.
        if (Files.exists(Paths.get(StockDisplayProfile.CONTROL))) return StockDisplayProfile.open();

        String kernel = new String(Files.readAllBytes(Paths.get("/proc/sys/kernel/osrelease")), StandardCharsets.US_ASCII).trim();

        // Register future kernels here only after validating their display implementation.
        switch (kernel) {
        case SovietZundamonProfile.KERNEL:
            return SovietZundamonProfile.open(x, y, width, height);

        default:
            throw new IOException("This display kernel is not supported.");
        }
    }
}
