package com.textnote.app.data

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 应用内可选的界面语言。
 *
 * [tag] 为空表示「跟随系统」，其余是 BCP 47 语言标签，交给 [Locale.forLanguageTag] 解析。
 *
 * 中文刻意按 **script** 标注（`zh-Hans` / `zh-Hant`）而不是区域（`zh-CN` / `zh-TW`）：
 * 资源目录同样是 `values-b+zh+Hans` / `values-b+zh+Hant`，一一对应，而且繁体一次覆盖
 * 中国台湾、中国香港、中国澳门三地。用 `values-zh-rTW` 的话 zh-HK、zh-MO 会掉回兜底语言。
 *
 * [endonym] 是该语言的**自称**，刻意不走资源文件：选择列表用自称展示，这样无论当前界面
 * 是哪国语言，用户都能一眼认出自己的那一项，而不是从「Chinese / 日语 / Chinesisch」里猜。
 *
 * ⚠️ 新增语言时四处要一起改，缺一处就是静默不一致：这里加一项、`res/` 下建对应的
 * `values-xx/strings.xml`、`res/xml/locales_config.xml` 里加一条、最后跑 `tools/check_locales.py`。
 */
enum class AppLanguage(val tag: String, val endonym: String) {
    SYSTEM("", ""),
    CHINESE_SIMPLIFIED("zh-Hans", "简体中文"),
    CHINESE_TRADITIONAL("zh-Hant", "繁體中文"),
    ENGLISH("en", "English"),
    LATIN("la", "Latina"),
    ;

    /** 交给资源解析与系统「按应用语言」用的 Locale；[SYSTEM] 时是 [Locale.ROOT]（空 locale） */
    val locale: Locale get() = if (tag.isEmpty()) Locale.ROOT else Locale.forLanguageTag(tag)

    companion object {
        /**
         * 从语言标签还原；空串、认不出的标签、归一后仍无对应的，一律回落到「跟随系统」。
         *
         * 这里做归一而不是直接比字符串，是因为标签有三个来源：本应用自己写的、
         * 旧版本持久化过的 `zh-CN` / `zh-TW`、以及系统回传的 `zh-Hans-CN` / `en-US` 这类带区域的形式。
         */
        fun fromTag(tag: String?): AppLanguage {
            val normalized = normalize(tag) ?: return SYSTEM
            return entries.firstOrNull { it.tag == normalized } ?: SYSTEM
        }

        /** `zh-CN` → `zh-Hans`、`zh-TW` / `zh-HK` → `zh-Hant`、`en-US` → `en` */
        private fun normalize(tag: String?): String? {
            if (tag.isNullOrBlank()) return null
            val locale = Locale.forLanguageTag(tag.replace('_', '-'))
            val language = locale.language.lowercase(Locale.ROOT)
            if (language.isEmpty() || language == "und") return null
            if (language != "zh") return language
            return if (chineseScript(locale) == "Hant") CHINESE_TRADITIONAL.tag
            else CHINESE_SIMPLIFIED.tag
        }

        /** 优先用显式 script；没有就按地区推断（旧版本存的是 `zh-CN` / `zh-TW` 这种标签） */
        private fun chineseScript(locale: Locale): String = locale.script.ifEmpty {
            when (locale.country.uppercase(Locale.ROOT)) {
                "TW", "HK", "MO" -> "Hant"
                else -> "Hans"
            }
        }
    }
}

/**
 * 语言偏好的读写。
 *
 * 为什么不放进 [AppSettings]：进程最早的 `attachBaseContext` 阶段就要知道选了什么语言，
 * 那时 Compose 还没有起点、读不到 StateFlow，只能直接查 SharedPreferences。
 * 所以它独立成一个 object，但**与 [SettingsRepository] 共用同一个文件与同一个键**，
 * 免得出现两份真相。
 *
 * Android 13（API 33）起多了一个权威来源：系统「设置 → 应用 → 语言」（本应用声明了
 * `android:localeConfig`，所以那里能直接改）。两者的关系：
 * 系统里设过（`applicationLocales` 非空）以系统为准，并回写本地偏好，让应用内的选择器同步；
 * 系统里没设过（为空 = 跟随系统）则用本地偏好，这样即便某个机型上系统接口不可用，
 * 应用内切换依然可靠。
 */
object AppLocaleStore {
    /** 与 [SettingsRepository] 共用同一个偏好文件（对方那个是私有的，这里必须保持一致） */
    const val PREFS = "settings"
    const val KEY = "app_language"

    /**
     * 进程内缓存。
     *
     * `getResources()` 会被高频调用（每次 `context.getString` 都可能走到），而查询系统
     * 「按应用语言」是一次 binder 调用，不能每次现问。缓存只在 [refresh] 与 [write] 时更新，
     * 而系统改语言后必定重建 Activity，[refresh] 的调用点正好覆盖那种情况。
     */
    @Volatile
    private var cached: AppLanguage? = null

    /** 取当前语言（走缓存）。缓存还没建立时先 [refresh] 一次。 */
    fun current(context: Context): AppLanguage = cached ?: refresh(context)

    /** 重新解析一次当前语言并刷新缓存 */
    fun refresh(context: Context): AppLanguage = resolve(context).also { cached = it }

    /** 写入语言偏好：更新缓存 + 落盘 + 同步系统（API 33+），三处保持一致 */
    fun write(context: Context, language: AppLanguage) {
        cached = language
        prefs(context).edit().putString(KEY, language.tag).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = if (language == AppLanguage.SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList(language.locale)
            }
            // 个别机型、或未声明 localeConfig 时可能抛异常，别让它把界面带崩
            runCatching {
                context.getSystemService(LocaleManager::class.java)?.setApplicationLocales(locales)
            }
        }
    }

    private fun resolve(context: Context): AppLanguage {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = runCatching {
                context.getSystemService(LocaleManager::class.java)?.applicationLocales
            }.getOrNull()
            if (locales != null) {
                val reported = if (locales.isEmpty) null else locales[0].toLanguageTag()
                val language = AppLanguage.fromTag(reported)
                // 系统那侧可能留着一个本版本已经没有的语言（用户旧版本选过、后来被删掉）。
                // 它不命中任何 values-* 目录 → 资源悄悄回退到兜底目录（英文），而应用内选择器
                // 却显示「跟随系统」——用户明明跟的是系统，看到的却是另一种语言。
                // 这里把那个遗留值清掉，让系统侧回到「未设置」，真正的「跟随系统」才名副其实。
                //
                // ⚠️ 这次清理对**本进程**不生效：进程的 Resources 在启动时就已经按旧语言建好了，
                // 框架不会因为应用自己改语言而重建它。所以升级后第一次启动仍会是兜底语言，
                // 从第二次启动起才真正跟随系统。
                if (reported != null && language == AppLanguage.SYSTEM) {
                    runCatching {
                        context.getSystemService(LocaleManager::class.java)
                            ?.setApplicationLocales(LocaleList.getEmptyLocaleList())
                    }
                }
                // 与本地偏好对齐：在系统设置里改过之后，应用内选择器要显示同一种语言。
                // 按**原始字符串**比而不是按解析结果比，这样顺带把认不出的遗留值也改写掉
                // （旧版本存的 ja / de 之类解析后一律等于「跟随系统」，按解析结果比会认为
                // 「已经一致」而不改写，文件里就永远留着一个当前版本不存在的语言）。
                if (prefs(context).getString(KEY, null).orEmpty() != language.tag) {
                    prefs(context).edit().putString(KEY, language.tag).apply()
                }
                return language
            }
        }
        return AppLanguage.fromTag(prefs(context).getString(KEY, null))
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * 把 [base] 包一层目标语言的 Context；「跟随系统」时原样返回。
 *
 * 本应用的主题继承的是 `android:Theme.Material.*` 而不是 `Theme.AppCompat.*`，
 * 没有引入 AppCompat，所以用不了 `AppCompatDelegate.setApplicationLocales()`，
 * 语言切换由自己实现。需要在**两个位置**各自套用：
 *
 * - **Activity**（本函数，见 `MainActivity.attachBaseContext`）：Activity 的 base context 由
 *   系统按「应用资源」创建，**不会**继承 Application 包装过的 Context。不包的话 Compose 里
 *   `stringResource` 读到的仍是系统语言，界面文案完全不跟着变。
 * - **Application**（见 `TextNoteApplication.getResources`）：让 `applicationContext` 也拿到
 *   目标语言，仓库层用 `context.getString(...)` 取到的文案才会被翻译。
 *
 * 缺任何一个都会表现为「只有一部分文案变了」。
 *
 * 换语言后必须重建 Activity 才会整体生效（与系统「按应用设置语言」的行为一致）——
 * 语言是在 `attachBaseContext` 阶段套上去的，运行中改不了已经发出去的 Resources。
 */
fun localizedContext(base: Context, language: AppLanguage): Context {
    if (language == AppLanguage.SYSTEM) return base
    val locale = language.locale
    val config = Configuration(base.resources.configuration).apply {
        setLocale(locale)
        setLayoutDirection(locale)
    }
    return base.createConfigurationContext(config)
}
