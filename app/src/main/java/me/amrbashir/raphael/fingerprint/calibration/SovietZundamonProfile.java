package me.amrbashir.raphael.fingerprint.calibration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Tested Soviet display controls; maps stock calibration commands without HAL calls. */
final class SovietZundamonProfile implements DisplayProfile {
    // Mapping reference: SOVIET-ANDROID/kernel_xiaomi_raphael,
    // 01e68ce707b170cf6d4e1506a4b49defd1e3c2b3, sde_connector.c/dsi_panel.c.
    static final String BASE = "/sys/devices/platform/soc/soc:qcom,dsi-display-primary";
    static final String KERNEL = "4.14.357Soviet-Zundamon-v1.1";

    String oldBrightness, oldHbm;
    int rawMin, rawMax, uiMax;
    boolean changed;
    boolean calibrationProfile;
    String expectedBrightness, expectedHbm;
    Path inspectedPanel, inspectedTiming;

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.US_ASCII).trim();
    }

    private static void write(String path, String value) throws IOException {
        CalibrationSession.write(path, value);
    }

    static int mapToRaw(int ui, int min, int max, int uiMax) {
        if (ui == 0) return 0;

        min = Math.max(1, min);
        long numerator = (long) (ui - 1) * (max - min);
        int raw = min + (int) ((numerator + (uiMax - 1) / 2) / (uiMax - 1));
        return raw > 2047 ? 4095 : raw; // exact Soviet sde_connector.c guard
    }

    static int uiForRaw(int target, int min, int max, int uiMax) throws IOException {
        if (min < 0 || max < 2047 || max > 65535 || uiMax < 2 || uiMax > 65535 || min >= max) throw new IOException("Unsupported panel brightness limits");
        if (target == 0) return 0;

        for (int ui = 1; ui <= uiMax; ui++)
            if (mapToRaw(ui, min, max, uiMax) == target) return ui;

        throw new IOException("Raw panel brightness " + target + " cannot be represented exactly");
    }

    static SovietZundamonProfile open(int x, int y, int width, int height) throws Exception {
        SovietZundamonProfile profile = new SovietZundamonProfile();
        profile.inspect();
        if (x != 445 || y != 1931 || width != 190 || height != 190) throw new IOException("This fingerprint sensor position is not supported.");
        profile.enableCalibration();

        return profile;
    }

    private void inspect() throws Exception {
        for (String path : new String[] {DisplayProfiles.BRIGHTNESS, BASE + "/hbm"})
            if (!Files.isWritable(Paths.get(path))) throw new IOException("Display controls are unavailable.");

        oldBrightness = read(DisplayProfiles.BRIGHTNESS);
        oldHbm = read(BASE + "/hbm");
        if (!oldHbm.equals("0")) throw new IOException("Close other fingerprint apps and turn off sunlight mode.");

        for (String name : new String[] {"force_fod_ui", "dc_dim"}) {
            String value = read(BASE + "/" + name);
            if (!value.equals("0")) throw new IOException("Turn off forced fingerprint illumination and DC dimming.");
        }

        Path ea = Paths.get(BASE, "msm_fb_ea_enable");
        if (Files.exists(ea) && !read(ea.toString()).equals("0")) throw new IOException("Turn off automatic exposure adjustment.");

        Path panel = PanelTree.activePanel(BASE);
        inspectedPanel = panel;
        rawMin = PanelTree.u32(panel.resolve("qcom,mdss-dsi-bl-min-level"));
        rawMax = PanelTree.u32(panel.resolve("qcom,mdss-dsi-bl-max-level"));
        uiMax = PanelTree.u32(panel.resolve("qcom,mdss-brightness-max-level"));

        int exported = Integer.parseInt(read("/sys/class/backlight/panel0-backlight/max_brightness"));
        if (exported != uiMax) throw new IOException("Exported backlight maximum differs from active device tree");

        int old = Integer.parseInt(oldBrightness);
        if (old < 0 || old > uiMax) throw new IOException("Invalid saved brightness");

        for (int raw : new int[] {1064, 1680, 2047})
            uiForRaw(raw, rawMin, rawMax, uiMax);

        Path timing = PanelTree.commandsNode(panel);
        inspectedTiming = timing;

        for (String name : new String[] {"qcom,mdss-dsi-dispparam-hbm-fod-on-command", "qcom,mdss-dsi-dispparam-hbm-fod-off-command"}) {
            byte[] commands = Files.readAllBytes(timing.resolve(name));
            if (commands.length < 8 || commands.length > 4096) throw new IOException("Missing or invalid FOD HBM command set");
        }

        checkManualBrightness();
    }

    void checkManualBrightness() throws Exception {
        java.lang.Process p = new ProcessBuilder("/system/bin/settings", "get", "system", "screen_brightness_mode").start();
        if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroy();
            throw new IOException("Brightness mode lookup timed out");
        }

        String mode = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)).readLine();
        if (!"0".equals(mode)) throw new IOException("Turn off adaptive brightness before connecting");
    }

    static void verifyCalibrationProfile(Path panel, Path timing, int min, int max, int uiMax) throws Exception {
        if (!panel.getFileName().toString().equals("qcom,mdss_dsi_ss_fhd_ea8076_cmd") || min != 2 || max != 4095 || uiMax != 4095) throw new IOException("This display panel is not supported.");

        String on = CalibrationSession.sha(timing.resolve("qcom,mdss-dsi-dispparam-hbm-fod-on-command").toString());
        String off = CalibrationSession.sha(timing.resolve("qcom,mdss-dsi-dispparam-hbm-fod-off-command").toString());
        if (!on.equals("f242904fba9da23cb1836c891b035ff7184111de0cfac0eff23149d02a89d481") || !off.equals("939098fcb898ec154d2593c58a5cce14f184a15620f1c08b8261305a7a461a62")) throw new IOException("This display panel is not supported.");
    }

    void enableCalibration() throws Exception {
        verifyCalibrationProfile(inspectedPanel, inspectedTiming, rawMin, rawMax, uiMax);
        calibrationProfile = true;
    }

    public void prepare() throws Exception {
        if (!calibrationProfile) throw new IOException("Calibration display profile not verified");

        checkManualBrightness();

        for (String name : new String[] {"hbm", "force_fod_ui", "dc_dim"})
            if (!read(BASE + "/" + name).equals("0")) throw new IOException("Panel state changed since preflight: " + name);

        oldBrightness = read(DisplayProfiles.BRIGHTNESS);
        oldHbm = "0";
        int value = Integer.parseInt(oldBrightness);
        if (value < 0 || value > uiMax) throw new IOException("Invalid saved brightness");

        expectedBrightness = oldBrightness;
        expectedHbm = oldHbm;
    }

    public void apply(String value) throws Exception {
        if (!calibrationProfile) throw new IOException("Calibration display profile not verified");

        switch (value) {
        case "0xF0000":
            hbm("0");
            break;

        case "0x20000":
            hbm("1");
            break;

        case "0x1000000":
            // Stock FOD-backlight ownership has no direct node here.
            // Require manual brightness and non-HBM state instead of
            // sending a stock hexadecimal word to an unrelated node.
            if (!read(BASE + "/hbm").equals("0")) throw new IOException("Unexpected HBM during normal backlight stage");
            break;

        case "0xD00690":
            level(1680);
            break;

        case "0xD007FF":
            level(2047);
            break;

        case "0xD00418":
            level(1064);
            break;

        case "0xD00000":
            level(0);
            break;

        default:
            throw new IOException("Unsupported stock display operation " + value);
        }
    }

    public void verifyState() throws Exception {
        if (!calibrationProfile || expectedBrightness == null || expectedHbm == null) throw new IOException("Calibration display state not initialized");
        if (!expectedBrightness.equals(read(DisplayProfiles.BRIGHTNESS)) || !expectedHbm.equals(read(BASE + "/hbm"))) throw new IOException("Panel brightness or HBM changed during the settling interval");
    }

    void hbm(String value) throws Exception {
        changed = true;
        write(BASE + "/hbm", value);

        String actual = read(BASE + "/hbm");
        if (!value.equals(actual)) throw new IOException("HBM state readback differs from request");
        expectedHbm = value;
    }

    void level(int raw) throws Exception {
        changed = true;
        int ui = uiForRaw(raw, rawMin, rawMax, uiMax);
        write(DisplayProfiles.BRIGHTNESS, Integer.toString(ui));

        String actual = read(DisplayProfiles.BRIGHTNESS);
        if (!actual.equals(Integer.toString(ui))) throw new IOException("Brightness was overridden or not accepted");
        expectedBrightness = Integer.toString(ui);
    }

    public boolean restore() {
        if (!changed) return true;

        boolean ok = true;

        try {
            hbm(oldHbm);
        } catch (Exception e) {
            ok = false;
        }

        try {
            write(DisplayProfiles.BRIGHTNESS, oldBrightness);
            if (!oldBrightness.equals(read(DisplayProfiles.BRIGHTNESS))) throw new IOException("Brightness restore mismatch");
        } catch (Exception e) {
            ok = false;
        }

        return ok;
    }
}
