import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;


import javax.inject.Inject;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;


import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.w3c.dom.Element;
import org.w3c.dom.Node;


/** Imports the stock calibration code and resources during the Gradle build. */
public abstract class GenerateStockGoodixSources extends DefaultTask {
    private static final String STOCK_PACKAGE = "com.goodix.fingerprint";
    private static final String STOCK_JAVA_PATH = "java/com/goodix/fingerprint/";


    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getStockApk();


    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getJadxArchive();


    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getRecipe();


    @OutputDirectory
    public abstract DirectoryProperty getJavaOutput();


    @OutputDirectory
    public abstract DirectoryProperty getResOutput();


    @OutputDirectory
    public abstract DirectoryProperty getAssetsOutput();


    @Input
    public String getGeneratorJdk() {
        return System.getProperty("java.version");
    }


    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();


    @TaskAction
    public void generate() throws Exception {
        Path recipe = getRecipe().get().getAsFile().toPath();
        Path apk = getStockApk().get().getAsFile().toPath();
        Path work = getTemporaryDir().toPath();
        Properties toolchain = loadToolchain(recipe);
        Path jadxJar = prepareJadx(toolchain, work);


        Path javaOutput = getJavaOutput().get().getAsFile().toPath();
        Path resOutput = getResOutput().get().getAsFile().toPath();
        Path assetsOutput = getAssetsOutput().get().getAsFile().toPath();
        getFileSystemOperations().delete(spec -> spec.delete(javaOutput, resOutput, assetsOutput));


        List<String> selectedFiles = Files.readAllLines(recipe.resolve("files.list"));
        generateJava(jadxJar, apk, work, recipe, selectedFiles, javaOutput);
        generateResources(jadxJar, apk, work, recipe, selectedFiles, resOutput);
        extractCalibrationImage(apk, assetsOutput);


        getLogger().lifecycle("Generated stock Goodix calibration sources and resources.");
    }


    private Properties loadToolchain(Path recipe) throws IOException {
        Properties toolchain = new Properties();
        try (var input = Files.newInputStream(recipe.resolve("toolchain.properties"))) {
            toolchain.load(input);
        }


        String requiredJdk = toolchain.getProperty("jdk.version");
        require(getGeneratorJdk().equals(requiredJdk), "Stock generation requires JDK " + requiredJdk);
        return toolchain;
    }


    private Path prepareJadx(Properties toolchain, Path work) throws Exception {
        Path archive = getJadxArchive().get().getAsFile().toPath();
        verify(archive, toolchain.getProperty("jadx.zip.sha256"));


        getFileSystemOperations().delete(spec -> spec.delete(work.resolve("decoded"), work.resolve("sources"), work.resolve("home")));
        Files.createDirectories(work);
        Path jadxJar = work.resolve("jadx.jar");


        try (ZipFile zip = new ZipFile(archive.toFile())) {
            String jarEntry = "lib/jadx-" + toolchain.getProperty("jadx.version") + "-all.jar";
            var entry = zip.getEntry(jarEntry);
            require(entry != null, "Pinned JADX distribution is missing its executable JAR");


            try (var input = zip.getInputStream(entry)) {
                Files.copy(input, jadxJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }


        verify(jadxJar, toolchain.getProperty("jadx.jar.sha256"));
        return jadxJar;
    }


    private void generateJava(Path jadxJar, Path apk, Path work, Path recipe, List<String> selectedFiles, Path output) throws Exception {
        List<String> sources = selectedFiles.stream()
            .filter(name -> name.startsWith(STOCK_JAVA_PATH))
            .map(name -> name.substring(STOCK_JAVA_PATH.length()))
            .toList();
        Path sourcesDir = work.resolve("sources");
        Files.createDirectories(sourcesDir);


        for (String name : sources) {
            Path sourceFile = sourcesDir.resolve(name);
            Files.createDirectories(sourceFile.getParent());
            String className = STOCK_PACKAGE + "." + name.substring(0, name.length() - 5).replace('/', '.');


            runJadx(jadxJar, apk, work, "--no-res", "--single-class", className, "--single-class-output", sourceFile.toString());
            String text = Files.readString(sourceFile).replace("\r\n", "\n");
            Files.writeString(sourceFile, text);
        }


        applyPatch(sourcesDir, recipe.resolve("compatibility.patch"));
        for (String name : sources) {
            copy(sourcesDir.resolve(name), output.resolve("com/goodix/fingerprint/" + name));
        }
    }


    private void generateResources(Path jadxJar, Path apk, Path work, Path recipe, List<String> selectedFiles, Path output) throws Exception {
        Path decoded = work.resolve("decoded");
        runJadx(jadxJar, apk, work, "--no-src", "--output-dir", decoded.toString());
        Path originalResources = decoded.resolve("resources/res");


        for (String name : selectedFiles) {
            if (name.startsWith("res/") && !name.startsWith("res/values")) {
                String relativePath = name.substring(4);
                copy(originalResources.resolve(relativePath), output.resolve(relativePath));
            }
        }


        generateValues(originalResources, output, recipe.resolve("resources.list"));
    }


    private static void extractCalibrationImage(Path apk, Path output) throws IOException {
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            var image = zip.getEntry("res/drawable/gradient_spot_190.png");
            require(image != null, "Stock calibration image is missing");
            Files.createDirectories(output);


            try (var input = zip.getInputStream(image)) {
                Files.copy(input, output.resolve("gradient_spot_190.png"));
            }
        }
    }


    private void runJadx(Path jadxJar, Path apk, Path work, String... options) throws Exception {
        List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US", "-Duser.timezone=UTC",
            "-Djava.awt.headless=true", "-Duser.home=" + work.resolve("home"), "-Xmx1g", "-cp", jadxJar.toString(), "jadx.cli.JadxCLI",
            "--config", "none", "--threads-count", "1", "--decompilation-mode", "auto", "--comments-level", "info", "--log-level", "warn"
        ));
        command.addAll(List.of(options));
        command.add(apk.toString());


        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("JADX_CONFIG_DIR", work.resolve("home/config").toString());
        builder.environment().put("JADX_CACHE_DIR", work.resolve("home/cache").toString());
        builder.environment().put("JADX_TMP_DIR", work.resolve("home/tmp").toString());


        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        require(process.waitFor() == 0, "Pinned JADX failed:\n" + output);
        if (!output.isBlank()) {
            getLogger().info("{}", output.strip());
        }
    }


    /** Exact unified patches: offsets, context and deletions must all hunk; no fuzzy application. */
    private static void applyPatch(Path sourcesDir, Path patch) throws IOException {
        List<String> patchLines = Files.readAllLines(patch);
        var header = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
        int lineIndex = 0;


        while (lineIndex < patchLines.size()) {
            require(patchLines.get(lineIndex).startsWith("--- "), "Invalid stock patch header");
            String fileName = patchLines.get(lineIndex++).substring(4);
            require(patchLines.get(lineIndex++).equals("+++ " + fileName), "Stock patch must modify the same file");


            Path sourceFile = sourcesDir.resolve(fileName).normalize();
            require(sourceFile.startsWith(sourcesDir) && !fileName.contains("\\"), "Invalid stock patch path");
            List<String> sourceLines = Files.readAllLines(sourceFile);
            List<String> patchedLines = new ArrayList<>();
            int sourcePosition = 0;


            while (lineIndex < patchLines.size() && patchLines.get(lineIndex).startsWith("@@ ")) {
                var hunk = header.matcher(patchLines.get(lineIndex++));
                require(hunk.matches(), "Invalid stock patch hunk");


                int sourceOffset = Integer.parseInt(hunk.group(1)) - 1;
                require(sourceOffset >= sourcePosition && sourceOffset <= sourceLines.size(), "Stock patch offset changed: " + fileName);
                patchedLines.addAll(sourceLines.subList(sourcePosition, sourceOffset));
                sourcePosition = sourceOffset;
                require(patchedLines.size() == Integer.parseInt(hunk.group(3)) - 1, "Stock patch output offset changed: " + fileName);


                int consumedLines = 0;
                int emittedLines = 0;
                while (lineIndex < patchLines.size() && !patchLines.get(lineIndex).startsWith("@@ ") && !patchLines.get(lineIndex).startsWith("--- ")) {
                    String line = patchLines.get(lineIndex++);
                    require(!line.isEmpty() && " +-".indexOf(line.charAt(0)) >= 0, "Invalid stock patch line");
                    char operation = line.charAt(0);
                    String content = line.substring(1);


                    if (operation != '+') {
                        require(sourcePosition < sourceLines.size() && sourceLines.get(sourcePosition++).equals(content), "Stock patch context changed: " + fileName);
                        consumedLines++;
                    }


                    if (operation != '-') {
                        patchedLines.add(content);
                        emittedLines++;
                    }
                }


                int expectedOldCount = hunk.group(2) == null ? 1 : Integer.parseInt(hunk.group(2));
                int expectedNewCount = hunk.group(4) == null ? 1 : Integer.parseInt(hunk.group(4));
                require(consumedLines == expectedOldCount && emittedLines == expectedNewCount, "Stock patch hunk size changed: " + fileName);
            }


            patchedLines.addAll(sourceLines.subList(sourcePosition, sourceLines.size()));
            Files.writeString(sourceFile, String.join("\n", patchedLines) + "\n");
        }
    }


    private static void generateValues(Path originalResources, Path output, Path selection) throws Exception {
        Set<String> selectedNames = new TreeSet<>(Files.readAllLines(selection));
        Set<String> defaultNames = new TreeSet<>();


        DocumentBuilderFactory parser = DocumentBuilderFactory.newInstance();
        parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        TransformerFactory serializer = TransformerFactory.newInstance();
        serializer.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);


        // These are the stock locales containing calibration values.
        for (String qualifier : List.of("values", "values-zh-rCN")) {
            Map<String, Element> values = readResourceValues(originalResources.resolve(qualifier), selectedNames, parser);
            if (qualifier.equals("values")) {
                defaultNames.addAll(values.keySet());
            }


            Element buttonStyle = values.get("style/DefaultButtonStyle");
            if (buttonStyle != null) {
                fixButtonGravity(buttonStyle);
            }


            Path resourceFile = output.resolve(qualifier + "/stock_calibration.xml");
            writeResourceValues(resourceFile, values, serializer);
        }


        require(defaultNames.equals(selectedNames), "Selected stock resources are missing");
    }


    private static Map<String, Element> readResourceValues(Path directory, Set<String> selectedNames, DocumentBuilderFactory parser) throws Exception {
        Map<String, Element> values = new TreeMap<>();


        try (var files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                Element root = parser.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
                for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (!(node instanceof Element value)) continue;


                    String name = value.getTagName() + "/" + value.getAttribute("name");
                    if (selectedNames.contains(name)) {
                        require(values.put(name, value) == null, "Duplicate stock resource: " + name);
                    }
                }
            }
        }


        return values;
    }


    private static void fixButtonGravity(Element buttonStyle) {
        for (Node node = buttonStyle.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element item && item.getAttribute("name").equals("android:gravity")) {
                require(item.getTextContent().equals("0x11"), "Stock button gravity changed");
                item.setTextContent("center"); // Equivalent named flags required by current AAPT.
            }
        }
    }


    private static void writeResourceValues(Path resourceFile, Map<String, Element> values, TransformerFactory serializer) throws Exception {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n");


        for (Element value : values.values()) {
            var transformer = serializer.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");


            StringWriter text = new StringWriter();
            transformer.transform(new DOMSource(value), new StreamResult(text));
            xml.append("    ").append(text).append('\n');
        }


        xml.append("</resources>\n");
        Files.createDirectories(resourceFile.getParent());
        Files.writeString(resourceFile, xml.toString().replace("\r\n", "\n"));
    }


    private static void copy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(source, target);
    }


    private static void verify(Path file, String checksum) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        String actualChecksum = HexFormat.of().formatHex(digest);
        require(actualChecksum.equals(checksum), "Checksum mismatch: " + file);
    }


    private static void require(boolean condition, String message) {
        if (!condition) throw new GradleException(message);
    }
}
