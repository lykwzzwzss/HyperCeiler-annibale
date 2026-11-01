// file:noinspection DependencyNotationArgument
import com.android.build.api.dsl.ApplicationBuildType
import com.android.build.gradle.tasks.PackageAndroidArtifact
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Properties
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
}

val apkId = "HyperCeiler"
val gitHash: String by lazy { runGitCommand("rev-parse", "--short", "HEAD") ?: "unknown" }
val gitHashLong: String by lazy { runGitCommand("rev-parse", "HEAD") ?: "unknown" }
// 浅克隆时 `git rev-list --count` 只数得到可见提交，versionCode 会骤降到 5 左右并导致
// 无法覆盖安装。这里给一个不低于当前已发布值的下界，可用 versionCodeFallback 覆盖。
val versionCodeFloor = providers.gradleProperty("versionCodeFallback").orNull?.toIntOrNull() ?: 4753
val gitCommitCount: Int by lazy {
    runGitCommand("rev-list", "--count", "HEAD")?.toIntOrNull()?.takeIf { it > 0 } ?: 0
}
val gitBranch: String by lazy {
    val url = runGitCommand("remote", "get-url", "origin") ?: "unknown"
    val branch = runGitCommand("branch", "--show-current") ?: "unknown"
    """github\.com[:/](.+?)(\.git)?$""".toRegex().find(url)?.groupValues?.get(1).orEmpty() + "/" + branch
}
val gitVersionCode: Int by lazy {
    val generated = maxOf(versionCodeFloor, 5 + gitCommitCount)
    providers.gradleProperty("versionCodeOverride").orNull?.toInt()?.also {
        require(it >= generated) { "versionCodeOverride must be at least $generated" }
    } ?: generated
}

fun runGitCommand(vararg args: String): String? = runCatching {
    ProcessBuilder(listOf("git") + args)
        .redirectErrorStream(true)
        .start()
        .let { process ->
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && output.isNotBlank()) output else null
        }
}.getOrNull()

fun loadPropertiesFromFile(fileName: String): Properties? =
    rootProject.file(fileName).takeIf { it.exists() }?.let { file ->
        Properties().apply { load(file.inputStream()) }
    }

android {
    namespace = "com.sevtinge.hyperceiler"
    compileSdk = 37
    compileSdkMinor = 0
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = namespace
        minSdk = 35
        targetSdk = 37
        versionCode = gitVersionCode
        versionName = "2.10.166"

        // 可复现构建：时间与用户名只从环境读取，缺失时留空，不写入本机信息。
        val buildTime = System.getenv("SOURCE_DATE_EPOCH")?.let { epoch ->
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss").apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(epoch.toLong() * 1000))
        }.orEmpty()

        val buildConfigData = mapOf(
            "BUILD_TIME" to buildTime,
            "BUILD_OS_NAME" to System.getProperty("os.name").orEmpty(),
            "BUILD_USER_NAME" to (System.getenv("GITHUB_ACTOR") ?: ""),
            "BUILD_JAVA_VERSION" to System.getProperty("java.version").orEmpty(),
            "GIT_BRANCH" to gitBranch
        )

        for ((key, value) in buildConfigData) {
            buildConfigField("String", key, "\"$value\"")
        }

        ndk {
            // noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-fvisibility=hidden")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    androidResources {
        additionalParameters += listOf("--allow-reserved-package-id", "--package-id", "0x36")
    }

    packaging {
        resources {
            merges += listOf("META-INF/xposed/*")
            excludes += listOf("**")
        }
        jniLibs {
            useLegacyPackaging = false
        }
        dex {
            useLegacyPackaging = true
        }
    }

    val properties: Properties? = loadPropertiesFromFile("signing.properties")
    fun getString(propertyName: String, environmentName: String, prompt: String): String =
        properties?.getProperty(propertyName)
            ?: System.getenv(environmentName)
            ?: System.console()?.readLine("\n$prompt: ").orEmpty()

    val buildTimeSuffix: String by lazy {
        SimpleDateFormat("MMddHHmm").apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date())
    }
    val dateSuffix: String by lazy {
        DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDateTime.now())
    }

    // 只含 gpr.* 的 signing.properties 不算可签名的配置，必须有 storeFile 才算。
    val hasSigningConfig = properties?.getProperty("storeFile") != null

    signingConfigs {
        create("hasProperties") {
            if (hasSigningConfig) {
                storeFile = file(getString("storeFile", "STORE_FILE", "Store file"))
                storePassword = getString("storePassword", "STORE_PASSWORD", "Store password")
                keyAlias = getString("keyAlias", "KEY_ALIAS", "Key alias")
                keyPassword = getString("keyPassword", "KEY_PASSWORD", "Key password")
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        /**
         * release/beta 不允许回退到 debug 签名：debug 签名的产物无法覆盖正式版，
         * 而且会把"忘记配置签名"变成一次看似成功的发布。没有配置时产出未签名 APK。
         */
        fun ApplicationBuildType.applySigning(requireRealKey: Boolean) {
            when {
                hasSigningConfig -> signingConfig = signingConfigs.getByName("hasProperties")
                requireRealKey -> {
                    logger.warn("signing.properties is missing: ${name} APK will be unsigned")
                    signingConfig = null
                }
                else -> signingConfig = signingConfigs.getByName("debug")
            }
        }

        val configSigning: ApplicationBuildType.() -> Unit = { applySigning(true) }
        val configDebugSigning: ApplicationBuildType.() -> Unit = { applySigning(false) }

        val applyBase: ApplicationBuildType.() -> Unit = {
            optimization.enable = true
            buildConfigField("String", "GIT_CODE", "\"$gitVersionCode\"")
        }

        release {
            applyBase()
            configSigning()
            buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
            versionNameSuffix = "-$dateSuffix"
        }

        create("beta") {
            applyBase()
            configSigning()
            buildConfigField("String", "GIT_HASH", "\"$gitHashLong\"")
            versionNameSuffix = "-$dateSuffix"
        }

        create("canary") {
            applyBase()
            configDebugSigning()
            buildConfigField("String", "GIT_HASH", "\"$gitHashLong\"")
            versionNameSuffix = "-${gitHash}-r${gitVersionCode}"
        }

        debug {
            isMinifyEnabled = false
            buildConfigField("String", "GIT_HASH", "\"$gitHashLong\"")
            buildConfigField("String", "GIT_CODE", "\"$gitVersionCode\"")
            versionNameSuffix = "-${buildTimeSuffix}-r${gitVersionCode}"
            configDebugSigning()
        }
    }

}

afterEvaluate {
    base {
        val buildTypeName = gradle.startParameter.taskNames
            .firstOrNull { it.contains("assemble", ignoreCase = true) }
            ?.substringAfterLast(":")
            ?.replace("assemble", "", ignoreCase = true)
            ?.lowercase() ?: "debug"
        val suffix = android.buildTypes.findByName(buildTypeName)?.versionNameSuffix ?: ""
        archivesName.set("$apkId-${android.defaultConfig.versionName}$suffix")
    }
}

// https://stackoverflow.com/a/77745844
tasks.withType<PackageAndroidArtifact> {
    doFirst { appMetadata.asFile.orNull?.writeText("") }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin.jvmToolchain(25)

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(libs.viewpager2)
    implementation(libs.expansion)
    implementation(projects.library.core)
    implementation(projects.library.common)

    api (libs.room.runtime)
    // FTS 支持
    api (libs.room.ktx)
    annotationProcessor (libs.room.compiler)
}

/**
 * Host-side native tests for the NativeHookRuntime (app/src/main/cpp/nativehook/)
 * and the desktop Dart hook's host-testable layers.
 *
 * These run on the build machine, not on Android: they need no device and no
 * Android SDK. On Linux they also build the ARM64 assembly harness test; on
 * macOS that single test is skipped by the script (eventfd / GNU as).
 *
 * Usage: ./gradlew :app:hostNativeTests
 * Optional: ./gradlew :app:hostNativeTests -PlibappSo=/path/to/libapp.so
 */
tasks.register<Exec>("hostNativeTests") {
    group = "verification"
    description = "Compiles and runs the host native tests (no device required)"
    workingDir = rootProject.projectDir
    val script = rootProject.file("tests/run_host_native_tests.sh")
    commandLine(buildList {
        add("bash")
        add(script.absolutePath)
        (project.findProperty("libappSo") as String?)?.let { add(it) }
    })
}
