plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-extension-mrxs"
    group = "io.github.xiaofengzhou"
    version = "0.4.0-alpha"
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
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}
