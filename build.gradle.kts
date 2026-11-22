plugins {
    base
    id("com.android.application") version "9.3.1" apply false
}

tasks.assemble { dependsOn(":app:assembleModule") }
