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
val sharedIconsDirectory = repositoryRoot.resolve("shared/icons/ui")
val generatedIcons = layout.buildDirectory.dir("generated/iconsKt")

// shared/icons/ui 的 Lucide SVG 构建时转成 vector drawable（规范 docs/design.md §4）。
abstract class GenerateIconsTask : DefaultTask() {
    @get:InputFile
    val script = project.objects.fileProperty()

    @get:InputDirectory
    val inputDirectory = project.objects.directoryProperty()

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
                outputDirectory.asFile.get().absolutePath,
            )
        }
    }
}

val generateIcons = tasks.register<GenerateIconsTask>("generateIcons") {
    group = "build"
    description = "Convert shared Lucide SVG icons into Android vector drawables."
    script.set(project.layout.projectDirectory.file("../tools/svg2vector.py"))
    inputDirectory.set(sharedIconsDirectory)
    svgs.setFrom(fileTree(sharedIconsDirectory) { include("*.svg") })
    outputDirectory.set(generatedIcons)
}
val minimumSdk = 26

// Frozen release signing contract: sign only when all four SNOWFOLD_ANDROID_*
// environment variables are present; otherwise assembleRelease stays unsigned
// and the build must not fail.
val keystoreFile = providers.environmentVariable("SNOWFOLD_ANDROID_KEYSTORE_FILE").orNull
val keystorePassword = providers.environmentVariable("SNOWFOLD_ANDROID_KEYSTORE_PASSWORD").orNull
val keyAlias = providers.environmentVariable("SNOWFOLD_ANDROID_KEY_ALIAS").orNull
val keyPassword = providers.environmentVariable("SNOWFOLD_ANDROID_KEY_PASSWORD").orNull
val releaseSigningMaterialPresent =
    listOf(keystoreFile, keystorePassword, keyAlias, keyPassword).all { !it.isNullOrBlank() }

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
                keyAlias = keyAlias
                keyPassword = keyPassword
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
    }
}

tasks.named("preBuild") {
    dependsOn(generateCoreBindings)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    // The AAR includes Android libjnidispatch.so; a desktop JNA JAR cannot load on Android.
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
