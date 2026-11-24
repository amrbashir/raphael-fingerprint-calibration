package me.amrbashir.raphael.fingerprint.calibration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Shared decimal HBM/backlight controls for the supported panel and brightness families. */
final class BacklightDisplayProfile implements DisplayProfile {
    static final String BASE = "/sys/devices/platform/soc/soc:qcom,dsi-display-primary";

    enum Mapping { LINEAR_2047, OFFSET_4095 }

    String oldBrightness, oldHbm;
    Mapping mapping;
    int uiMax;
    boolean changed;
    String expectedBrightness, expectedHbm;
    final List<String> interferenceControls = new ArrayList<>();

    private BacklightDisplayProfile() {}

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.US_ASCII).trim();
    }

    private static void write(String path, String value) throws IOException {
        CalibrationSession.write(path, value);
    }

    static Mapping mappingFor(int min, int max, int uiMax) throws IOException {
        if (min == 2 && max == uiMax) {
            if (max == 2047) return Mapping.LINEAR_2047;
            // This family assumes the inspected offset-and-rounding conversion.
            // DT limits and sysfs readback cannot independently verify that algorithm.
            if (max == 4095) return Mapping.OFFSET_4095;
        }

        throw new IOException("This display's brightness limits are not supported.");
    }

    static int mapToRaw(Mapping mapping, int ui) {
        if (ui == 0) return 0;
        if (mapping == Mapping.LINEAR_2047) return ui;

        long numerator = (long) (ui - 1) * (4095 - 2);
        int raw = 2 + (int) ((numerator + (4095 - 1) / 2) / (4095 - 1));
        return raw > 2047 ? 4095 : raw;
    }

    static int uiForRaw(Mapping mapping, int target) throws IOException {
        if (target < 0 || target > 2047) throw new IOException("Unsupported calibration brightness");
        if (target == 0) return 0;

        int uiMax = mapping == Mapping.LINEAR_2047 ? 2047 : 4095;
        for (int ui = 1; ui <= uiMax; ui++)
            if (mapToRaw(mapping, ui) == target) return ui;

        throw new IOException("Raw panel brightness " + target + " cannot be represented exactly");
    }

    static BacklightDisplayProfile open(int x, int y, int width, int height) throws Exception {
        if (x != 445 || y != 1931 || width != 190 || height != 190) throw new IOException("This fingerprint sensor position is not supported.");

        BacklightDisplayProfile profile = new BacklightDisplayProfile();
        profile.inspect();

        return profile;
    }

    private void inspect() throws Exception {
        for (String path : new String[] {DisplayProfiles.BRIGHTNESS, BASE + "/hbm"})
            if (!Files.isWritable(Paths.get(path))) throw new IOException("Display controls are unavailable.");

        oldBrightness = read(DisplayProfiles.BRIGHTNESS);
        oldHbm = read(BASE + "/hbm");
        if (!oldHbm.equals("0")) throw new IOException("Close other fingerprint apps and turn off sunlight mode.");

        Path panel = PanelTree.activePanel(BASE);
        int rawMin = PanelTree.u32(panel.resolve("qcom,mdss-dsi-bl-min-level"));
        int rawMax = PanelTree.u32(panel.resolve("qcom,mdss-dsi-bl-max-level"));
        uiMax = PanelTree.u32(panel.resolve("qcom,mdss-brightness-max-level"));
        mapping = mappingFor(rawMin, rawMax, uiMax);

        int exported = Integer.parseInt(read("/sys/class/backlight/panel0-backlight/max_brightness"));
        if (exported != uiMax) throw new IOException("Exported backlight maximum differs from active device tree");

        int old = Integer.parseInt(oldBrightness);
        if (old < 0 || old > uiMax) throw new IOException("Invalid saved brightness");

        for (int raw : new int[] {1048, 1190, 1680, 2047})
            uiForRaw(mapping, raw);

        Path timing = PanelTree.commandsNode(panel);
        verifyPanel(panel, timing);

        for (String name : new String[] {"force_fod_ui", "dc_dim", "msm_fb_ea_enable"}) {
            Path path = Paths.get(BASE, name);
            if (Files.exists(path)) interferenceControls.add(path.toString());
        }

        checkInterference();
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

    static void verifyPanel(Path panel, Path timing) throws Exception {
        String name = panel.getFileName().toString();
        if (!name.equals("qcom,mdss_dsi_ss_fhd_ea8076_cmd") && !name.equals("qcom,mdss_dsi_ss_fhd_ea8076_global_cmd")) throw new IOException("This display panel is not supported.");

        for (String command : new String[] {"qcom,mdss-dsi-dispparam-hbm-fod-on-command", "qcom,mdss-dsi-dispparam-hbm-fod-off-command"}) {
            long size = Files.size(timing.resolve(command));
            if (size < 8 || size > 4096) throw new IOException("Missing or invalid FOD HBM command set");
        }

        String on = CalibrationSession.sha(timing.resolve("qcom,mdss-dsi-dispparam-hbm-fod-on-command").toString());
        String off = CalibrationSession.sha(timing.resolve("qcom,mdss-dsi-dispparam-hbm-fod-off-command").toString());
        if (!on.equals("f242904fba9da23cb1836c891b035ff7184111de0cfac0eff23149d02a89d481") || !off.equals("939098fcb898ec154d2593c58a5cce14f184a15620f1c08b8261305a7a461a62")) throw new IOException("This display panel is not supported.");
    }

    void checkInterference() throws IOException {
        for (String path : interferenceControls)
            if (!read(path).equals("0")) throw new IOException("Turn off forced fingerprint illumination, DC dimming and exposure adjustment.");
    }

    public void prepare() throws Exception {
        checkManualBrightness();
        checkInterference();
        if (!read(BASE + "/hbm").equals("0")) throw new IOException("Panel HBM changed since preflight");

        oldBrightness = read(DisplayProfiles.BRIGHTNESS);
        oldHbm = "0";
        int value = Integer.parseInt(oldBrightness);
        if (value < 0 || value > uiMax) throw new IOException("Invalid saved brightness");

        expectedBrightness = oldBrightness;
        expectedHbm = oldHbm;
    }

    public void apply(String value) throws Exception {
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

        case "0xD00418":
        case "0xD004A6":
        case "0xD00690":
        case "0xD007FF":
        case "0xD00000":
            // Xiaomi's panel_disp_param_send_lock extracts the low 13 bits unchanged.
            level(Integer.parseInt(value.substring(4), 16));
            break;

        default:
            throw new IOException("Unsupported stock display operation " + value);
        }
    }

    public void verifyState() throws Exception {
        if (expectedBrightness == null || expectedHbm == null) throw new IOException("Calibration display state not initialized");
        checkInterference();
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
        int ui = uiForRaw(mapping, raw);
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
