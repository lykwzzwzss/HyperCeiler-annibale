import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.lsparanoid)
}

lsparanoid {
    seed = 227263
    classFilter = { true }
    includeDependencies = true
    variantFilter = { variant ->
        variant.buildType != "debug"
    }
}

android {
    namespace = "com.sevtinge.hyperceiler.libhook"
    compileSdk = 37

    defaultConfig {
        minSdk = 35

        buildConfigField("String", "APP_MODULE_ID", "\"com.sevtinge.hyperceiler\"")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    buildTypes {
        release {}
        create("beta") {}
        create("canary") {}
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }

    compilerOptions {
        freeCompilerArgs.add("-XXLanguage:+MultiDollarInterpolation")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    api(libs.core)
    api(libs.fragment)
    api(libs.recyclerview)
    api(libs.coordinatorlayout)
    api(libs.constraintlayout) {
        exclude("androidx.appcompat", "appcompat")
    }

    api(libs.core.ktx)
    api(libs.expansion)
    compileOnlyApi(projects.library.hiddenApi)

    // libxposed API 102 (compileOnly, runs against framework on device)
    compileOnlyApi(libs.libxposed.api)
    api(libs.libxposed.service)

    api(libs.dexkit)
    api(libs.ezhooktool.core)
    api(libs.ezhooktool.xposed102)
    api(libs.hiddenapibypass)
    api(libs.gson)
    api(libs.hyperfocusapi)
    api(libs.superlyricapi)
    api(libs.lunarcalendar)

    api(projects.library.processor)
    api(projects.library.common)
    annotationProcessor(projects.library.processor)
}

// aidl.exe 在中文用户名的 Windows 上会用 GBK 写生成文件里的命令行注释，
// 而 javac 按 UTF-8 读取，导致「编码 UTF-8 的不可映射字符」。编译前统一转成 UTF-8。
tasks.withType<JavaCompile>().configureEach {
    doFirst {
        val dir = layout.buildDirectory.dir("generated/aidl_source_output_dir").get().asFile
        if (!dir.exists()) return@doFirst
        dir.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { file ->
            val bytes = file.readBytes()
            val decodesAsUtf8 = runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                true
            }.getOrDefault(false)
            if (decodesAsUtf8) return@forEach
            file.writeText(String(bytes, charset("GBK")), Charsets.UTF_8)
        }
    }
}
