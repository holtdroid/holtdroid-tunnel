plugins {
    alias(libs.plugins.android.application)
}

@CacheableTask
abstract class BindEngine : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val targets: ListProperty<String>

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:Internal
    abstract val moduleDir: DirectoryProperty

    @get:Internal
    abstract val sdkDir: DirectoryProperty

    @get:Internal
    abstract val ndkDir: DirectoryProperty

    @get:OutputFile
    abstract val aar: RegularFileProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun bind() {
        val bin = temporaryDir.resolve("bin")
        val path = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
        exec.exec {
            workingDir = moduleDir.get().asFile
            environment("GOBIN", bin.absolutePath)
            commandLine("go", "install", "golang.org/x/mobile/cmd/gomobile", "golang.org/x/mobile/cmd/gobind")
        }
        exec.exec {
            workingDir = moduleDir.get().asFile
            environment("PATH", path)
            environment("ANDROID_HOME", sdkDir.get().asFile.absolutePath)
            environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath)
            commandLine(
                bin.resolve("gomobile").absolutePath, "bind",
                "-target", targets.get().joinToString(","),
                "-androidapi", "26",
                "-javapkg", "io.github.kriakiku.holtdroid.tunnel",
                "-trimpath",
                "-ldflags", "-s -w -buildid=",
                "-tags", "with_gvisor",
                "-o", aar.get().asFile.absolutePath,
                ".",
            )
        }
    }
}

val goTargets = mapOf("arm64-v8a" to "android/arm64", "armeabi-v7a" to "android/arm", "x86_64" to "android/amd64", "x86" to "android/386")
val engineAbi = providers.gradleProperty("engineAbi").getOrElse("arm64-v8a,armeabi-v7a,x86_64").split(',').map { it.trim() }
val engineNdk = providers.gradleProperty("ndkVersion").get()
val engineDir = rootProject.layout.projectDirectory.dir("engine")

val bindEngine = tasks.register<BindEngine>("bindEngine") {
    sources.from(engineDir.asFileTree.matching { include("**/*.go", "go.mod", "go.sum") })
    targets = engineAbi.map { goTargets[it] ?: error("unknown engineAbi $it") }
    ndkVersion = engineNdk
    moduleDir = engineDir
    sdkDir = androidComponents.sdkComponents.sdkDirectory
    ndkDir = androidComponents.sdkComponents.ndkDirectory
    aar = layout.buildDirectory.file("generated/engine/engine.aar")
}

val appVersion = providers.gradleProperty("appVersion").getOrElse("0.0.0")
val versionParts = appVersion.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
val signingStore = providers.environmentVariable("SIGNING_KEYSTORE").orNull

android {
    namespace = "io.github.kriakiku.holtdroid.tunnel"
    ndkVersion = engineNdk
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "io.github.kriakiku.holtdroid.tunnel"
        minSdk = 26
        targetSdk = 37
        versionCode = maxOf(1, versionParts[0] * 1_000_000 + versionParts[1] * 1_000 + versionParts[2])
        versionName = appVersion
    }

    signingConfigs {
        if (signingStore != null) {
            create("release") {
                storeFile = file(signingStore)
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable += setOf(
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            "OldTargetApi",
            "DataExtractionRules",
            "ChromeOsAbiSupport",
            "UnusedAttribute",
        )
    }
}

dependencies {
    implementation(files(bindEngine.flatMap { it.aar }))
    testImplementation(libs.junit)
}
