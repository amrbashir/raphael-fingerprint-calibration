plugins {
    base
    id("com.android.application") version "9.3.1" apply false
}


rootProject.file(".env").takeIf { it.isFile }?.forEachLine { line ->
    if (line.isNotBlank() && !line.trimStart().startsWith("#")) {
        val entry = line.split("=", limit = 2)
        if (entry.size == 2) System.setProperty(entry[0].trim(), entry[1])
    }
}


tasks.assemble { dependsOn(":app:assembleModule") }
