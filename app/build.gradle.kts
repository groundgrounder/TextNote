import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 签名密钥从根目录的 `keystore.properties` 读（该文件与 `keystore/` 一起被 gitignore）。
 *
 * **没有它也要能构建**——新 clone 的机器、还没配 secret 的 CI，都不该因为缺密钥而失败。
 * 那种情况下 release 包照常产出，只是签的是假签名、不能与正式包互相覆盖安装。
 * 兜底不能省：否则任何没密钥的环境都会直接构建失败。
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.textnote.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.textnote.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.2.0"
    }

    // 只有拿得到密钥时才声明它，buildTypes 那边再按需挂上。
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 必须开：`material-icons-extended` 带进 5000+ 个图标，而项目只用了 12 个。
            // 不裁剪的话 dex 有 32MB（包里最大的一块），裁剪后未用到的全被移除。
            isMinifyEnabled = true
            // 有密钥就挂上；没有则保持 AGP 默认——仍能构建出未签名的包。
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
        // 不开 buildConfig：全项目零 `BuildConfig` 引用，开它只会多生成一个类。
        // 哪天要按构建类型区分常量（比如日志开关），再把它打开。
    }

    /**
     * App Bundle 默认**按语言拆分**：只把「用户设备语言」的那套 strings.xml 下发给设备。
     * 而本应用在设置页里随时可以切语言（见 `data/AppLanguage.kt`），切过去的正是**没下发**
     * 的那几套 —— 界面纹丝不动，用户只会以为语言选项坏了。关掉拆分，代价是基础包多带
     * 三套 strings.xml（几 KB）。
     *
     * 当前 CI 出的是 APK（`assembleRelease`，APK 不做拆分），所以这条现在不会真的触发；
     * 它是给「将来改用 AAB 上架」预置的 —— 那时若忘了关，故障是静默的，事后极难查回来。
     * （lint 的 `AppBundleLocaleChanges` 就是报这个。）
     */
    bundle {
        language {
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    // `Dispatchers` / `runBlocking` / `Job` / `delay` / `StateFlow` 全在用，但以前只靠
    // lifecycle 与 compose 传递进来：上游一改结构就编译失败，版本也不由自己说了算。
    // 1.7.3 是当前实际解析到的版本，写在这里等于把已知可用的这一组钉成**下限**——
    // 将来谁要升，改这一行，而不是被传递依赖偷偷升上去。
    implementation(platform("org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.7.3"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")

    // 这里原本还有 documentfile 与 compose 的 ui-tooling(-preview)。前者全仓库没有一处
    // `DocumentFile` 引用（读写都直接走 ContentResolver，不需要那层封装），却会进 release 包；
    // 后者的用途只有 `@Preview`，而本项目一个预览都没写。真要加预览时，把下面这两行放回来：
    //   implementation("androidx.compose.ui:ui-tooling-preview")
    //   debugImplementation("androidx.compose.ui:ui-tooling")
    // 注意 preview 那行必须在 implementation（不是 debug），否则 release 变体会编译不过。
}
