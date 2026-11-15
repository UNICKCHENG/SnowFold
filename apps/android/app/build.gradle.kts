import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory

import org.gradle.api.DefaultTask
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class GenerateDirectoryTask : Exec() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

val repositoryRoot = rootDir.resolve("../..").canonicalFile
val coreDirectory = repositoryRoot.resolve(providers.gradleProperty("rust.corePath").get())
val bindgenDirectory = repositoryRoot.resolve(providers.gradleProperty("rust.bindgenPath").get())
val cargoTargetDirectory = providers.environmentVariable("CARGO_TARGET_DIR").orElse(repositoryRoot.resolve("target").path).get()
val generatedJniLibs = layout.buildDirectory.dir("generated/jniLibs")
val generatedKotlin = layout.buildDirectory.dir("generated/uniffi/kotlin")
val sharedIconsLine = repositoryRoot.resolve("shared/icons/line")
val sharedIconsFill = repositoryRoot.resolve("shared/icons/fill")
val generatedIcons = layout.buildDirectory.dir("generated/iconsKt")

// shared/fonts/inter 的 InterVariable.ttf 复制进 res/font（docs/design.md §2）。
abstract class CopyFontTask : DefaultTask() {
    @get:InputFile
    val source = project.objects.fileProperty()

    @get:InputFile
    val fontLicense = project.objects.fileProperty()

    @get:InputFile
    val iconLicense = project.objects.fileProperty()

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val dir = outputDirectory.asFile.get().resolve("font").apply { mkdirs() }
        source.asFile.get().copyTo(dir.resolve("inter_variable.ttf"), overwrite = true)
        val notices = outputDirectory.asFile.get().resolve("raw").apply { mkdirs() }
        fontLicense.asFile.get().copyTo(notices.resolve("inter_license.txt"), overwrite = true)
        iconLicense.asFile.get().copyTo(notices.resolve("iconmcp_license.txt"), overwrite = true)
    }
}

val copyFonts = tasks.register<CopyFontTask>("copyFonts") {
    group = "build"
    description = "把共用 Inter 变量字体复制到生成的 res/font。"
    source.set(repositoryRoot.resolve("shared/fonts/inter/InterVariable.ttf"))
    fontLicense.set(repositoryRoot.resolve("shared/fonts/inter/LICENSE.txt"))
    iconLicense.set(repositoryRoot.resolve("shared/icons/LICENSE-IconMCP.txt"))
    outputDirectory.set(layout.buildDirectory.dir("generated/res-font"))
}

// shared/icons/line 与 fill 的 IconMCP SVG 构建时转成 ImageVector。
abstract class GenerateIconsTask : DefaultTask() {
    @get:InputFile
    val script = project.objects.fileProperty()

    @get:InputDirectory
    val inputDirectory = project.objects.directoryProperty()

    @get:InputDirectory
    val secondInputDirectory = project.objects.directoryProperty()

    @get:InputFiles
    val svgs = project.objects.fileCollection()

    @get:OutputDirectory
    val outputDirectory = project.objects.directoryProperty()

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun run() {
        execOps.exec {
            commandLine(
                "python3",
                script.asFile.get().absolutePath,
                inputDirectory.asFile.get().absolutePath,
                secondInputDirectory.asFile.get().absolutePath,
                outputDirectory.asFile.get().absolutePath,
            )
        }
    }
}

val generateIcons = tasks.register<GenerateIconsTask>("generateIcons") {
    group = "build"
    description = "把共用 IconMCP 线条与实心 SVG 生成 Compose ImageVector。"
    script.set(project.layout.projectDirectory.file("../tools/svg2vector.py"))
    inputDirectory.set(sharedIconsLine)
    secondInputDirectory.set(sharedIconsFill)
    svgs.setFrom(files(fileTree(sharedIconsLine) { include("*.svg") }, fileTree(sharedIconsFill) { include("*.svg") }))
    outputDirectory.set(generatedIcons)
}
val minimumSdk = 26

// Release signing: sign only when both SNOWFOLD_ANDROID_KEYSTORE_* environment
// variables are present; otherwise assembleRelease stays unsigned and the build
// must not fail. The keystore is PKCS12, which requires the key password to equal
// the store password, and the alias is not a secret, so neither is configurable.
val releaseKeyAlias = "snowfold"
val keystoreFile = providers.environmentVariable("SNOWFOLD_ANDROID_KEYSTORE_FILE").orNull
val keystorePassword = providers.environmentVariable("SNOWFOLD_ANDROID_KEYSTORE_PASSWORD").orNull
val releaseSigningMaterialPresent =
    listOf(keystoreFile, keystorePassword).all { !it.isNullOrBlank() }

android {
    namespace = "app.snowfold"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "app.snowfold"
        minSdk = minimumSdk
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    if (releaseSigningMaterialPresent) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreFile!!)
                storePassword = keystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningMaterialPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

}

kotlin {
    jvmToolchain(21)
}

// Release artifacts are renamed per the frozen contract; CI picks up the file
// by this documented name.
androidComponents {
    onVariants { variant ->
        if (variant.buildType == "release") {
            variant.outputs.forEach { output -> output.outputFileName.set("SnowFold-android.apk") }
        }
    }
}

// Cargo owns incremental compilation; Gradle tracks the copied, ABI-specific outputs.
val buildRust = tasks.register<GenerateDirectoryTask>("buildRust") {
    group = "build"
    description = "Cross-compile the shared core for Android arm64 and x86_64."
    workingDir(repositoryRoot)
    environment("CARGO_TARGET_DIR", cargoTargetDirectory)
    environment("ANDROID_NDK_HOME", androidComponents.sdkComponents.ndkDirectory.get().asFile)
    inputs.files(repositoryRoot.resolve("Cargo.toml"), repositoryRoot.resolve("Cargo.lock"))
    inputs.files(fileTree(coreDirectory) { include("Cargo.toml", "src/**", "uniffi.toml") })
    inputs.property("ndkVersion", android.ndkVersion)
    inputs.property("minimumSdk", minimumSdk)
    outputDirectory.set(generatedJniLibs)
    commandLine(
        "cargo", "ndk", "--target", "arm64-v8a", "--target", "x86_64",
        "--platform", minimumSdk.toString(), "--output-dir", generatedJniLibs.get().asFile,
        "build", "--locked", "--release", "--manifest-path", coreDirectory.resolve("Cargo.toml"),
    )
}

val generateCoreBindings = tasks.register<GenerateDirectoryTask>("generateCoreBindings") {
    group = "build"
    description = "Generate Kotlin bindings from the Android core library using locked UniFFI."
    dependsOn(buildRust)
    workingDir(repositoryRoot)
    environment("CARGO_TARGET_DIR", cargoTargetDirectory)
    inputs.files(repositoryRoot.resolve("Cargo.toml"), repositoryRoot.resolve("Cargo.lock"))
    inputs.files(fileTree(bindgenDirectory) { include("Cargo.toml", "src/**") })
    inputs.file(coreDirectory.resolve("uniffi.toml"))
    inputs.file(generatedJniLibs.map { it.file("arm64-v8a/libsnowfold_core.so") })
    outputDirectory.set(generatedKotlin)
    doFirst {
        // Remove stale generated types if a future interface renames a package or object.
        delete(generatedKotlin)
    }
    commandLine(
        "cargo", "run", "--locked", "--manifest-path", bindgenDirectory.resolve("Cargo.toml"),
        "--bin", "uniffi-bindgen", "--", "generate", "--library",
        generatedJniLibs.get().file("arm64-v8a/libsnowfold_core.so").asFile,
        "--language", "kotlin", "--out-dir", generatedKotlin.get().asFile, "--no-format",
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(generateIcons, GenerateIconsTask::outputDirectory)
        variant.sources.java?.addGeneratedSourceDirectory(generateCoreBindings, GenerateDirectoryTask::outputDirectory)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildRust, GenerateDirectoryTask::outputDirectory)
        // InterVariable.ttf 由 copyFonts 复制进生成 res/font（docs/design.md §2）。
        variant.sources.res?.addGeneratedSourceDirectory(copyFonts, CopyFontTask::outputDirectory)
    }
}

tasks.named("preBuild") {
    dependsOn(generateCoreBindings, copyFonts)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    // The AAR includes Android libjnidispatch.so; a desktop JNA JAR cannot load on Android.
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    // WebDAV MKCOL 不在 Android HttpsURLConnection 支持的方法中；仅同步传输使用。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
