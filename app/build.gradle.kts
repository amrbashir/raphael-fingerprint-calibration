import com.android.build.api.artifact.SingleArtifact
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.Properties
import org.apache.tools.ant.filters.FixCrLfFilter
import org.apache.tools.ant.filters.ReplaceTokens

plugins {
    id("com.android.application")
}

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

version = versionProperties.getProperty("version")
val parts = Regex("^(\\d{1,6})\\.(\\d{1,2})(?:\\.(\\d{1,2}))?$").matchEntire(version.toString())?.groupValues ?: error("Version must be MAJOR.MINOR or MAJOR.MINOR.PATCH")
val moduleVersionCode = parts[1].toLong() * 10000 + parts[2].toLong() * 100 + (parts[3].toLongOrNull() ?: 0)

require(moduleVersionCode in 1..Int.MAX_VALUE) { "Version code must fit in a positive signed 32-bit integer" }

val keystoreFilePath = System.getProperty("KEYSTORE_FILE_PATH")
val hasSigningConfig = listOf(
    keystoreFilePath,
    System.getProperty("KEYSTORE_PASSWORD"),
    System.getProperty("KEY_ALIAS"),
    System.getProperty("KEY_PASSWORD"),
).all { !it.isNullOrBlank() }

android {
    enableKotlin = false
    namespace = "me.amrbashir.raphael.fingerprint.calibration"
    compileSdk = 37
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "me.amrbashir.raphael.fingerprint.calibration"
        minSdk = 28
        targetSdk = 37
        versionName = project.version.toString()
        versionCode = moduleVersionCode.toInt()
    }

    signingConfigs {
        create("release") {
            if (hasSigningConfig) {
                storeFile = rootProject.file(keystoreFilePath!!)
                storePassword = System.getProperty("KEYSTORE_PASSWORD")
                keyAlias = System.getProperty("KEY_ALIAS")
                keyPassword = System.getProperty("KEY_PASSWORD")
            }

            enableV1Signing = false
            enableV2Signing = false
            enableV3Signing = true
            enableV4Signing = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        buildConfig = false
        resValues = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

}

val systemSdkRevision = "3af7c93524be6f51e092b87b17f009f13ee98b43"
val systemSdkChecksum = "c9005404c142ddef2bb829657c374ca6948c1bb079342ed5e89dc72f7e7f8391"
val systemSdkUrl = "https://android.googlesource.com/platform/prebuilts/sdk/+/$systemSdkRevision/30/system/android.jar?format=TEXT"
val systemSdk = rootProject.layout.projectDirectory.file(".gradle/aosp/android-system-30-$systemSdkRevision.jar")

val downloadSystemSdk = tasks.register("downloadSystemSdk") {
    inputs.properties(mapOf("url" to systemSdkUrl, "sha256" to systemSdkChecksum))
    outputs.file(systemSdk)

    doLast {
        val target = systemSdk.asFile
        val bytes = if (target.exists()) {
            target.readBytes()
        } else {
            check(!gradle.startParameter.isOffline) { "Run once online to download the pinned AOSP System SDK." }
            val connection = URI(systemSdkUrl).toURL().openConnection().apply {
                connectTimeout = 15000
                readTimeout = 60000
            }
            Base64.getMimeDecoder().wrap(connection.getInputStream()).use { it.readBytes() }
        }
        val checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        check(checksum == systemSdkChecksum) { "AOSP System SDK checksum mismatch." }

        if (!target.exists()) {
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
        }
    }
}

dependencies {
    compileOnly(files(downloadSystemSdk))
    implementation("com.android.support.constraint:constraint-layout:1.1.3")
    compileOnly("com.android.support:support-annotations:28.0.0")
}

val stockRecipe = rootProject.layout.projectDirectory.dir("buildSrc/src/main/resources/goodix-calibration")
val stockPins = Properties().apply {
    stockRecipe.file("toolchain.properties").asFile.inputStream().use { load(it) }
}
val jadxZip = rootProject.layout.projectDirectory.file(".gradle/goodix-calibration/jadx-${stockPins.getProperty("jadx.version")}.zip")
val downloadJadx = tasks.register("downloadJadx") {
    inputs.file(stockRecipe.file("toolchain.properties"))
    outputs.file(jadxZip)

    doLast {
        val target = jadxZip.asFile
        val expected = stockPins.getProperty("jadx.zip.sha256")
        fun checksum(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        if (target.exists()) {
            check(checksum(target.readBytes()) == expected) { "JADX archive checksum mismatch." }
        } else {
            check(!gradle.startParameter.isOffline) { "Run once online to download pinned JADX." }
            val connection = URI(stockPins.getProperty("jadx.url")).toURL().openConnection().apply {
                connectTimeout = 15000
                readTimeout = 60000
            }
            val bytes = connection.getInputStream().use { it.readBytes() }
            check(checksum(bytes) == expected) { "JADX download checksum mismatch." }
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
        }
    }
}

val generateStockGoodixSources = tasks.register<GenerateStockGoodixSources>("generateStockGoodixSources") {
    group = "build"
    description = "Generates the stock Goodix calibration sources and resources."
    recipe.set(stockRecipe)
    stockApk.set(stockRecipe.file("goodix_sz.apk"))
    jadxArchive.set(layout.file(downloadJadx.map { jadxZip.asFile }))
    javaOutput.set(layout.buildDirectory.dir("generated/goodix-calibration/java"))
    resOutput.set(layout.buildDirectory.dir("generated/goodix-calibration/res"))
    assetsOutput.set(layout.buildDirectory.dir("generated/goodix-calibration/assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(generateStockGoodixSources, GenerateStockGoodixSources::getJavaOutput)
        variant.sources.res?.addGeneratedSourceDirectory(generateStockGoodixSources, GenerateStockGoodixSources::getResOutput)
        variant.sources.assets?.addGeneratedSourceDirectory(generateStockGoodixSources, GenerateStockGoodixSources::getAssetsOutput)
    }

    onVariants(selector().withBuildType("release")) { variant ->
        tasks.register<Zip>("assembleModule") {
            group = "build"
            description = "Builds the signed Raphael Fingerprint Calibration KernelSU module."

            doFirst {
                check(hasSigningConfig) { "Module packaging requires KEYSTORE_FILE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD in .env or JVM system properties." }
            }

            archiveFileName.set("raphael-fingerprint-calibration-v$version.zip")
            destinationDirectory.set(rootProject.layout.buildDirectory)

            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            duplicatesStrategy = DuplicatesStrategy.FAIL
            filteringCharset = "UTF-8"
            filePermissions { unix("644") }
            dirPermissions { unix("755") }

            from(variant.artifacts.get(SingleArtifact.APK)) {
                include("*.apk")
                rename { "raphael-fingerprint-calibration.apk" }
            }

            from(rootProject.file("module")) {
                filesMatching(listOf("**/*.sh", "**/*.prop")) {
                    filter<ReplaceTokens>("tokens" to mapOf("VERSION" to version.toString(), "VERSION_CODE" to moduleVersionCode.toString()))
                    filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
                }
                filesMatching("**/*.sh") { permissions { unix("755") } }
            }
        }
    }
}
