package com.textnote.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.textnote.app.data.AppLocaleStore
import com.textnote.app.data.DocumentMeta
import com.textnote.app.data.DocumentRepository
import com.textnote.app.data.DraftMeta
import com.textnote.app.data.DraftStore
import com.textnote.app.data.SettingsRepository
import com.textnote.app.data.SyntaxOverrides
import com.textnote.app.data.localizedContext
import com.textnote.app.ui.editor.EditorScreen
import com.textnote.app.ui.editor.EditorViewModel
import com.textnote.app.ui.files.HomeScreen
import com.textnote.app.ui.settings.SettingsScreen
import com.textnote.app.ui.theme.TextNoteTheme
import com.textnote.app.ui.theme.resolveDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用外壳：单 Activity，**不引导航库**。
 *
 * 这一层持有的是**跨屏存活**的东西——外部 VIEW/EDIT intent 传入的 Uri、首页那两个列表
 * （最近打开 / 草稿）、重新授权流程、设置流；四块界面之间的切换就是几个 `rememberSaveable`
 * 布尔量。这么小的应用套一层导航图只会多一层间接，所以刻意留在这里。
 *
 * 编辑器自己的状态全在 [EditorViewModel] 里，由 `viewModel()` 按 Activity 作用域持有。
 * 这句话在多窗口下更值钱：本应用是多实例的，**每个窗口是一个独立 Activity**，
 * 于是各窗口的 ViewModel、正打开的文档、撤销栈天然互不干扰，这里不需要任何额外机制。
 * 反过来，首页那两份列表读的是同一份存储，一个窗口改了，另一个窗口的列表不会自己刷新。
 */
class MainActivity : ComponentActivity() {

    /** 外部（文件管理器等）通过 VIEW/EDIT intent 传入的文档 Uri，桥接给 Compose */
    private var externalUri by mutableStateOf<Uri?>(null)

    /**
     * 把应用内选定的语言套到 Activity 的 base Context 上。
     *
     * **必须在这里**：Activity 的 base Context 由系统按「应用资源」创建，不会继承 Application
     * 被包装过的 Context；而且这是唯一「早于 Resources 被使用」的时机——放 `onCreate` 就晚了，
     * 那时 base context 已经用旧语言包好了。
     *
     * 用 `refresh` 而不是 `current`：Android 13+ 用户可能在系统「应用语言」里改过，系统改完会
     * 重建 Activity，这里正是重新解析的时机（顺带把系统侧的选择回写到本地偏好）。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedContext(newBase, AppLocaleStore.refresh(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 这一次 Activity 是用哪个语言构建出来的（上面的 attachBaseContext 刚解析过）。
        // 设置页里换了语言要拿它比对，决定是否需要重建。
        val languageAtStart = AppLocaleStore.current(this)
        val repository = DocumentRepository(applicationContext)
        val drafts = DraftStore(applicationContext)
        val settings = SettingsRepository(applicationContext)
        val syntaxOverrides = SyntaxOverrides(applicationContext)
        // 只在真正的首次启动时处理 intent。旋转、进出分屏、改窗口尺寸都会重建 Activity，
        // 重建时若把打开动作重放一遍，就等于每次重建都重开一次文档——多窗口下来回分屏很频繁，
        // 这条会被明显放大。重建时编辑状态由 ViewModel 与 rememberSaveable 复原，不需要重放。
        if (savedInstanceState == null) handleOpenIntent(intent)
        setContent {
            // 主题与编辑器字体都由设置决定，所以在最外层就收集。
            // 用 collectAsStateWithLifecycle：应用在后台时不必为了设置变化而重组。
            val appSettings by settings.settings.collectAsStateWithLifecycle(
                initialValue = settings.current,
            )
            // 语言是在 attachBaseContext 阶段套到 Context 上的，运行中改不了已经发出去的
            // Resources —— 换完必须重建 Activity 才会整体生效（与系统「按应用语言」的行为一致）。
            // rememberSaveable 会保住「停在设置页」这件事，以及正在编辑的文档。
            // Android 13+ 还会把选择同步给系统，系统那侧可能也重建一次；重复重建只是多闪一下，
            // 换来的是任何系统版本上都立刻生效。
            LaunchedEffect(appSettings.appLanguage) {
                if (appSettings.appLanguage != languageAtStart) recreate()
            }
            TextNoteTheme(appSettings) {
                TextNoteApp(
                    repository = repository,
                    drafts = drafts,
                    settings = settings,
                    syntaxOverrides = syntaxOverrides,
                    externalUri = externalUri,
                    onExternalUriConsumed = { externalUri = null },
                )
            }
        }
    }

    /**
     * 应用已在运行时，外部再次打开**同一份**文档会走这里：系统认出已有窗口的 base intent 相同，
     * 就把 intent 投递给那个 task 栈顶的这个实例。文档不同则各开一个新窗口、各走各的 onCreate。
     *
     * 本应用是**多实例**的（manifest 的 `documentLaunchMode="intoExisting"`），
     * 所以这里不能再假设「应用只有一个实例」。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    private fun handleOpenIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> externalUri = uri
        }
    }
}

/**
 * 当前**进程内**每份文档正被哪些窗口显示着。
 *
 * 多实例的各个窗口都在同一个进程里，所以一张内存登记表就够，不需要跨进程机制。
 *
 * 用途只有一个：「在新窗口打开」之前查一下，已经开着的不再开第二个编辑器。两份编辑器同时
 * 自动保存同一个文件时谁后写谁覆盖，而用户完全看不出来。
 *
 * **按「文件身份」而不是只按 Uri 判断**：同一份文件从不同来源拿到的是两个 Uri
 * （见 [DocumentRepository.fileIdentity]），只比 Uri 会漏。身份是异步算出来的（要开一次文件
 * 描述符），所以它单独存、后补；还没算出来时退回按 Uri 判断——那两个 Uri 至少「自己等于自己」，
 * 不会误放，只是挡不住跨来源的重复。
 */
internal object OpenDocumentRegistry {
    /** uri → 显示它的窗口数（计数而非布尔：本来就可能已经开着两份） */
    private val windowCounts = mutableMapOf<String, Int>()

    /** uri → 文件身份（算出来才登记） */
    private val identities = mutableMapOf<String, String>()

    fun add(uri: String) {
        windowCounts[uri] = (windowCounts[uri] ?: 0) + 1
    }

    fun remove(uri: String) {
        val left = (windowCounts[uri] ?: 0) - 1
        if (left > 0) {
            windowCounts[uri] = left
        } else {
            windowCounts.remove(uri)
            identities.remove(uri)
        }
    }

    /** 补登记身份（异步算出来之后调用；该文档已经不在任何窗口显示时忽略） */
    fun setIdentity(uri: String, identity: String?) {
        if (identity != null && windowCounts.containsKey(uri)) identities[uri] = identity
    }

    /** 有没有哪块窗口正在显示这份文档（同 Uri，或身份相同） */
    fun isShown(uri: String, identity: String?): Boolean {
        if ((windowCounts[uri] ?: 0) > 0) return true
        return identity != null && identities.values.any { it == identity }
    }
}

@Composable
fun TextNoteApp(
    repository: DocumentRepository,
    drafts: DraftStore,
    settings: SettingsRepository,
    syntaxOverrides: SyntaxOverrides,
    externalUri: Uri?,
    onExternalUriConsumed: () -> Unit,
) {
    /**
     * 用户进过编辑器。用 rememberSaveable 是为了扛住旋转——Activity 重建时 ViewModel 还活着，
     * 不能把人踢回首页。
     *
     * 但**不能只看它**：进程被杀后重建时这个标志也会被恢复，而 ViewModel 是新的，
     * 于是会渲染出一个空编辑器。所以下面判断显示哪一层时，还要 `&& viewModel.hasDocument`。
     */
    var hasDocument by rememberSaveable { mutableStateOf(false) }
    var pendingDrafts by remember { mutableStateOf<List<DraftMeta>>(emptyList()) }
    var recents by remember { mutableStateOf<List<DocumentMeta>>(emptyList()) }

    /**
     * 用户点了「授权已失效」的最近项，正在等他重新挑一次这个文件。
     *
     * 记下旧 Uri 是为了选完之后把它**换掉**而不是多出一条记录：同一份文件从不同入口拿到
     * 的 Uri 不同（SAF 文档 Uri / MediaStore Uri / FileProvider Uri），直接追加会让列表里
     * 出现同一份文件的多个条目。
     */
    var reauthorizingUri by remember { mutableStateOf<String?>(null) }

    /** 设置页盖在文档/首页之上显示；返回时回到原来那一层，文档状态不受影响 */
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val appSettings by settings.settings.collectAsStateWithLifecycle(initialValue = settings.current)

    /** 「新建」对话框里的建议文件名。用户可以改，改成 .md 之类就自动切到对应语法。 */
    val defaultNewFileName = stringResource(R.string.untitled)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val factory = rememberFactory(repository, drafts, syntaxOverrides)
    val viewModel: EditorViewModel = viewModel(factory = factory)

    /**
     * 在**新窗口**里打开这份文档：起一个新的 Activity 实例，这份文档在那边显示，本窗口不动。
     *
     * 不加 `FLAG_ACTIVITY_MULTIPLE_TASK`——加上它，同一份文档必定开出第二个窗口，两份副本各自
     * 自动保存、谁后写谁覆盖，而用户看不出发生了什么。不加则交给 `intoExisting` 去重。
     *
     * 但**光靠平台去重不够**：`intoExisting` 比的是「task 的 base intent」，而用应用内列表点开的
     * 文档从来没有过自己的 VIEW-task，于是会真的多开一个编辑器。所以这里再查一次进程内的登记表。
     */
    val openInNewWindow: (String) -> Unit = { uri ->
        // 文件身份要开一次文件描述符（跨进程 IO），所以先算身份再决定
        scope.launch {
            val identity = repository.fileIdentity(Uri.parse(uri))
            if (OpenDocumentRegistry.isShown(uri, identity)) {
                // 各屏自带 Scaffold、没有 Snackbar 宿主，用 Toast 说一句即可，不抢焦点
                Toast.makeText(context, R.string.already_open_in_a_window, Toast.LENGTH_SHORT).show()
            } else {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(Uri.parse(uri))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT),
                )
            }
        }
    }

    // 登记「本窗口正在显示哪份文档」，供上面的检查与 [OpenDocumentRegistry] 用。
    // DisposableEffect(shownUri)：换文档时先注销旧的、再登记新的；窗口销毁时自动注销。
    // 漏了注销那一侧会**永久挡住**某份文档开新窗口，比原来的重复开窗更糟。
    val shownUri = viewModel.currentUri?.toString()
    DisposableEffect(shownUri) {
        if (shownUri != null) OpenDocumentRegistry.add(shownUri)
        onDispose { if (shownUri != null) OpenDocumentRegistry.remove(shownUri) }
    }

    // 身份单独补登记（要开一次 fd，所以异步）：同一份文件的不同来源 Uri 靠它对齐，
    // 算不出来时登记表退回按 Uri 判断。
    LaunchedEffect(shownUri) {
        if (shownUri != null) {
            OpenDocumentRegistry.setIdentity(shownUri, repository.fileIdentity(Uri.parse(shownUri)))
        }
    }

    // onStop 落草稿。这是全应用最容易丢数据的地方：切后台后进程随时可能被回收，
    // 而 onSaveInstanceState 的 Bundle 有 1MB 上限，塞不下正文。
    //
    // onStart 则用来查原文件有没有被别的应用改过。用 ON_START 而不是 ON_RESUME：
    // 从系统的文件选择器返回时也会走 ON_START，那个时刻文档状态是干净的，检测无害。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.flushDraft()
                Lifecycle.Event.ON_START -> viewModel.checkExternalChange()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /**
     * 刷新首页的两份列表。
     *
     * 草稿要按持久化授权过滤——进程重启后授权失效的那些列了也点不开；
     * 最近列表则**不过滤**，失效项留着并标注（见 [HomeScreen] 的说明）。
     */
    suspend fun refreshHome() {
        withContext(Dispatchers.IO) { drafts.prune() }
        // 过滤也要在 IO 里：`hasPersistedPermission` 是跨进程查询（ContentResolver），
        // 每条草稿一次。写在 `withContext` 外面就等于把它们全丢回主线程。
        pendingDrafts = withContext(Dispatchers.IO) {
            drafts.pending().filter { repository.hasPersistedPermission(Uri.parse(it.uri)) }
        }
        recents = withContext(Dispatchers.IO) { repository.recentDocuments() }
    }

    LaunchedEffect(Unit) { refreshHome() }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val replacing = reauthorizingUri
            reauthorizingUri = null
            if (replacing != null) {
                scope.launch { withContext(Dispatchers.IO) { repository.replaceRecent(replacing, uri) } }
            }
            hasDocument = true
            pendingDrafts = emptyList()
            viewModel.open(uri)
        } else {
            // 用户取消了重新授权，别把待替换状态留着悬在那儿
            reauthorizingUri = null
        }
    }

    /**
     * 新建文档。
     *
     * 走 SAF 的 `ACTION_CREATE_DOCUMENT`（系统会**立刻建出一个 0 字节文件**并返回 Uri），
     * 而不是先在内存里养一个没有归属的「未命名缓冲区」：后者要额外维护一套
     * 「currentUri 为空」的状态，草稿、外部改动检测、最近列表、首页的草稿入口全都要加分支，
     * 而且那种缓冲区一旦进程被杀就很难再找回来（草稿是按 Uri 存的，没有 Uri 就没处放）。
     * 代价是新文档必须先定下位置和名字——但 SAF 的对话框本身就能输入文件名，一步到位。
     *
     * 建完直接 [EditorViewModel.open]：它就是普通的「打开一个空文件」，不需要另立一条路径。
     */
    val newDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            hasDocument = true
            pendingDrafts = emptyList()
            viewModel.open(uri)
        }
    }

    // 外部 intent 打开：直接进编辑器
    LaunchedEffect(externalUri) {
        val uri = externalUri ?: return@LaunchedEffect
        // 切文档之前先把当前这份落草稿。同 Activity 内换文件不会触发 ON_STOP，而 open() 会
        // 直接覆盖正文并清掉撤销栈——不先写下去，被切走那份文档在防抖窗口内的输入就没了。
        // 这一步**不能**挪进 open()：reloadFromDisk 走的也是 open()，那里恰恰要放弃本地版本。
        viewModel.flushDraft()
        hasDocument = true
        pendingDrafts = emptyList()
        // 用户此刻可能正停在设置页。不把它关掉的话，下面渲染分支仍然走 SettingsScreen，
        // 文件确实开了、人却看不见——观感就是「点了链接没反应」。
        showSettings = false
        viewModel.open(uri)
        onExternalUriConsumed()
    }

    if (showSettings) {
        SettingsScreen(
            settings = appSettings,
            onThemeMode = settings::setThemeMode,
            onDynamicColor = settings::setDynamicColor,
            onFont = settings::setFont,
            onFontSize = settings::setFontSizeSp,
            onLineHeight = settings::setLineHeight,
            onSoftWrap = settings::setSoftWrap,
            onLanguage = settings::setAppLanguage,
            onBack = { showSettings = false },
        )
    } else if (hasDocument && viewModel.hasDocument) {
        EditorScreen(
            viewModel = viewModel,
            // 按**设置**算，不能用系统深色模式：两者不一致时语法色会与背景撞在一起
            darkTheme = appSettings.resolveDarkTheme(),
            softWrap = appSettings.softWrap,
            onClose = {
                // 在同一个 Activity 内从文档切回首页**不会触发 ON_STOP**，所以不能指望那条
                // 路径落草稿——未保存的内容得在这里主动写下去，否则紧接着进程被回收就丢了。
                // 顺序要紧：flushDraft 读的是 field，必须排在 closeDocument 清空它之前。
                viewModel.flushDraft()
                viewModel.closeDocument()
                hasDocument = false
                scope.launch { refreshHome() }
            },
            onSettingsClick = { showSettings = true },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        HomeScreen(
            recents = recents,
            drafts = pendingDrafts,
            formatTime = repository::formatTime,
            onOpenClick = {
                reauthorizingUri = null
                openLauncher.launch(arrayOf("text/plain", "*/*"))
            },
            onNewClick = { newDocLauncher.launch(defaultNewFileName) },
            onSettingsClick = { showSettings = true },
            onRecentClick = { meta ->
                if (meta.accessible) {
                    hasDocument = true
                    pendingDrafts = emptyList()
                    viewModel.open(Uri.parse(meta.uri))
                } else {
                    // 授权没了（重启、系统清理、文件被移走）。让用户重新指一次，
                    // 而不是甩一句「打不开」——文件多半还在，只是我们不再握有权限。
                    reauthorizingUri = meta.uri
                    openLauncher.launch(arrayOf("text/plain", "*/*"))
                }
            },
            onRecentRemove = { meta ->
                scope.launch {
                    withContext(Dispatchers.IO) { repository.removeFromRecents(meta.uri) }
                    refreshHome()
                }
            },
            onOpenInNewWindow = { meta -> openInNewWindow(meta.uri) },
            onDraftClick = { meta ->
                hasDocument = true
                pendingDrafts = emptyList()
                viewModel.open(Uri.parse(meta.uri))
            },
        )
    }
}

/** 把依赖注入 ViewModel（它们要在 IO 线程问 ContentProvider / 碰磁盘，不能 new 在组合里） */
@Composable
private fun rememberFactory(
    repository: DocumentRepository,
    drafts: DraftStore,
    syntaxOverrides: SyntaxOverrides,
): ViewModelProvider.Factory =
    remember(repository, drafts, syntaxOverrides) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                EditorViewModel(repository, drafts, syntaxOverrides) as T
        }
    }
