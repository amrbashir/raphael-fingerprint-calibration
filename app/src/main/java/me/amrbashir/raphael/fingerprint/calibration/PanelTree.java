package me.amrbashir.raphael.fingerprint.calibration;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Read-only resolution matching dsi_display_dev_probe's display-list indirection. */
final class PanelTree {
    static int u32(Path path) throws IOException {
        byte[] b = Files.readAllBytes(path);
        if (b.length != 4) throw new IOException("Not one DT uint32: " + path);
        return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    static Path activePanel(String displayBase) throws Exception {
        Path root = Paths.get("/sys/firmware/devicetree/base");
        Path parameter = Paths.get("/sys/module/msm_drm/parameters/dsi_display0");
        String selection = "";

        if (Files.exists(parameter)) {
            selection = string(parameter);
        } else {
            String cmdline = new String(Files.readAllBytes(Paths.get("/proc/cmdline")), StandardCharsets.UTF_8);
            int count = 0;

            for (String token : cmdline.trim().split("\\s+"))
                if (token.startsWith("msm_drm.dsi_display0=")) {
                    selection = token.substring("msm_drm.dsi_display0=".length());
                    count++;
                }

            if (count > 1) throw new IOException("Multiple primary display boot selectors");
        }

        return resolve(root, Paths.get(displayBase, "of_node"), selection);
    }

    static String string(Path p) throws IOException {
        byte[] b = Files.readAllBytes(p);
        if (b.length > 1024) throw new IOException("Oversized DT string: " + p);
        return new String(b, StandardCharsets.UTF_8).replace("\u0000", "").trim();
    }

    static String label(String selection) throws IOException {
        String s = selection.trim();
        if (s.isEmpty()) return "";

        int colon = s.indexOf(':');
        if (s.length() > 256 || colon < 1) throw new IOException("Unrecognized primary display selector");

        String name = s.substring(0, colon);
        if (!name.matches("[A-Za-z0-9_,.\\-]+")) throw new IOException("Invalid display label in selector");

        return name;
    }

    static Map<Integer, Path> index(Path root) throws Exception {
        Map<Integer, Path> nodes = new HashMap<Integer, Path>();
        int visited = 0;

        try (Stream<Path> tree = Files.walk(root, 12)) {
            Iterator<Path> it = tree.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                if (++visited > 40000) throw new IOException("Device tree traversal limit reached");

                String name = p.getFileName().toString();
                if (!name.equals("phandle") && !name.equals("linux,phandle")) continue;

                int handle = u32(p);
                if (handle == 0) throw new IOException("Invalid zero DT phandle");

                Path previous = nodes.put(handle, p.getParent());
                if (previous != null && !previous.equals(p.getParent())) throw new IOException("Ambiguous DT phandle " + handle);
            }
        }

        return nodes;
    }

    static Path linked(Map<Integer, Path> nodes, int handle) throws IOException {
        Path node = nodes.get(handle);
        if (node == null) throw new IOException("DT phandle target not found: " + handle);
        return node;
    }

    static Path resolve(Path root, Path displayDevice, String selection) throws Exception {
        Map<Integer, Path> nodes = index(root);
        Path direct = displayDevice.resolve("qcom,dsi-panel");
        if (Files.exists(direct)) return linked(nodes, u32(direct));

        Path list = displayDevice.resolve("qcom,dsi-display-list");
        byte[] b = Files.readAllBytes(list);
        if (b.length == 0 || b.length % 4 != 0 || b.length > 256) throw new IOException("Invalid primary display phandle list");

        String selectedLabel = label(selection);
        Path selected = null;
        int matches = 0;
        ByteBuffer input = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);

        while (input.hasRemaining()) {
            Path candidate = linked(nodes, input.getInt());
            String candidateLabel = string(candidate.resolve("label"));
            boolean match = selectedLabel.isEmpty() ? Files.exists(candidate.resolve("qcom,dsi-display-active")) : selectedLabel.equals(candidateLabel);
            if (match) {
                selected = candidate;
                matches++;
            }
        }

        if (matches != 1) throw new IOException("Primary display selection is missing or ambiguous: matches=" + matches + " label=" + selectedLabel);
        return linked(nodes, u32(selected.resolve("qcom,dsi-panel")));
    }

    static Path commandsNode(Path panel) throws IOException {
        String on = "qcom,mdss-dsi-dispparam-hbm-fod-on-command", off = "qcom,mdss-dsi-dispparam-hbm-fod-off-command";
        if (Files.exists(panel.resolve(on)) && Files.exists(panel.resolve(off))) return panel;

        Path timings = panel.resolve("qcom,mdss-dsi-display-timings");
        ArrayList<Path> modes = new ArrayList<Path>();

        try (DirectoryStream<Path> children = Files.newDirectoryStream(timings)) {
            for (Path child : children)
                if (Files.isDirectory(child)) modes.add(child);
        }

        // The supplied ROM has exactly one timing per physical EA8076 panel.
        // With multiple modes we cannot infer the currently active command set.
        if (modes.size() != 1) throw new IOException("Panel timing selection is ambiguous: modes=" + modes.size());

        Path mode = modes.get(0);
        if (!Files.exists(mode.resolve(on)) || !Files.exists(mode.resolve(off))) throw new IOException("Selected panel timing lacks FOD HBM commands");
        return mode;
    }
}
