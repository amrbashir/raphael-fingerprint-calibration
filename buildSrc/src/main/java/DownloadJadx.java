import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;


import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;


/** Acquires the recipe's pinned JADX distribution, reusing a verified local archive. */
public abstract class DownloadJadx extends DefaultTask {
    private final boolean offline;


    public DownloadJadx() {
        offline = getProject().getGradle().getStartParameter().isOffline();
        getArchive().convention(getCacheDirectory().file(getToolchain().map(file ->
            "jadx-" + loadToolchain(file).getProperty("jadx.version") + ".zip")));
    }


    @Internal
    public abstract DirectoryProperty getRecipe();


    @Internal
    public abstract DirectoryProperty getCacheDirectory();


    @InputFile @PathSensitive(PathSensitivity.NONE)
    public Provider<RegularFile> getToolchain() {
        return getRecipe().file("toolchain.properties");
    }


    @OutputFile
    public abstract RegularFileProperty getArchive();


    @TaskAction
    public void download() throws Exception {
        Properties toolchain = loadToolchain(getToolchain().get());
        Path target = getArchive().get().getAsFile().toPath();
        String expected = toolchain.getProperty("jadx.zip.sha256");


        if (Files.exists(target)) {
            verify(Files.readAllBytes(target), expected, "JADX archive checksum mismatch.");
            return;
        }


        if (offline) throw new GradleException("Run once online to download pinned JADX.");
        var connection = URI.create(toolchain.getProperty("jadx.url")).toURL().openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);


        byte[] bytes;
        try (var input = connection.getInputStream()) {
            bytes = input.readAllBytes();
        }


        verify(bytes, expected, "JADX download checksum mismatch.");
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }


    private static Properties loadToolchain(RegularFile file) {
        Properties toolchain = new Properties();
        try (var input = Files.newInputStream(file.getAsFile().toPath())) {
            toolchain.load(input);
        } catch (IOException failure) {
            throw new GradleException("Could not read the pinned JADX toolchain.", failure);
        }


        return toolchain;
    }


    private static void verify(byte[] bytes, String expected, String message) throws Exception {
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!checksum.equals(expected)) throw new GradleException(message);
    }
}
