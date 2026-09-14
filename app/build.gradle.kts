plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.textnote.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.textnote.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        // 不开 buildConfig：全项目零 `BuildConfig` 引用，开它只会多生成一个类。
        // 哪天要按构建类型区分常量（比如日志开关），再把它打开。
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
