// 仓库顺序：**本地镜像优先，CI 上用官方源**。
//
// 为什么 CI 必须换掉镜像：镜像对「它自己没有的 artifact」返 404 时 Gradle 会继续试下一个仓库，
// 所以平时看不出问题；但它偶发返 5xx（实测 `kotlin-scripting-common:2.0.20` 遇到
// `Received status code 502 from server`）时，Gradle **会直接中止整轮解析**——不去试后面的
// 官方源，`:app:compileDebugKotlin` 当场失败，发版卡在第三方镜像的抖动上。
// 而 GitHub runner 直连 Maven 是通的、也更快，没有理由让构建依赖一个镜像的可用性。
//
// 判据用 `GITHUB_ACTIONS`（GitHub 自动注入）而不是命令行参数：任何 workflow、任何 job 都自动
// 生效，不必记得给每个构建步骤加参数——「记得加」正是这类修法最容易失效的地方。
// 本地要演练这条路：`TEXTNOTE_OFFICIAL_REPOS=1 ./gradlew help`
//
// ⚠️ 下面两处的判据必须一致。没能提到一个公共 val 上，是因为 Gradle 要求
// `pluginManagement` 是本文件的**第一个块**（前面连 val 都不许有），那里只能内联。
pluginManagement {
    repositories {
        if (System.getenv("GITHUB_ACTIONS") != "true" &&
            System.getenv("TEXTNOTE_OFFICIAL_REPOS") != "1"
        ) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

/** 本地镜像优先（默认），还是只用官方源（CI，或显式演练）。判据见文件头。 */
val officialReposOnly = System.getenv("GITHUB_ACTIONS") == "true" ||
    System.getenv("TEXTNOTE_OFFICIAL_REPOS") == "1"

// 在 CI 的构建日志里留一行标记：出了问题（比如又撞上镜像 5xx）时，一眼就能确认这次走的是哪套仓库，
// 不必靠推测。本地不打——否则每次 gradlew 都刷一行，噪音盖过价值。
if (officialReposOnly) {
    println("[TextNote] 依赖仓库 = 官方源（Google / Maven Central，未使用 aliyun 镜像）")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!officialReposOnly) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "TextNote"
include(":app")
