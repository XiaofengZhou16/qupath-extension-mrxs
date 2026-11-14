plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-extension-mrxs"
    group = "io.github.xiaofengzhou"
    version = "0.5.0-SNAPSHOT"
    description = "Native multichannel fluorescence MRXS image support for QuPath"
    automaticModule = "io.github.xiaofengzhou.qupath.mrxs"
}

dependencies {
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)

    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    val privateFixtures = listOf("MRXS_TEST_SAMPLE_4C", "MRXS_TEST_SAMPLE_5C", "MRXS_TEST_SAMPLE_5C_POSITIVE_CY5")
    privateFixtures.forEach { variable ->
        inputs.property(variable, providers.environmentVariable(variable).orElse(""))
    }
    // Private slide directories can change without their pathname changing. Never
    // reuse a previous integration-test result when a real fixture is requested.
    outputs.upToDateWhen { privateFixtures.none { !System.getenv(it).isNullOrBlank() } }
}

// Optional GUI smoke entry points compile against the same provided JavaFX API as the extension.
sourceSets.test {
    compileClasspath += sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().compileClasspath
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}
