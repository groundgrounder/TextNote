package com.textnote.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.textnote.app.data.DocumentMeta
import com.textnote.app.data.DocumentRepository
import com.textnote.app.data.DraftMeta
import com.textnote.app.data.DraftStore
import com.textnote.app.data.SettingsRepository
import com.textnote.app.data.SyntaxOverrides
import com.textnote.app.ui.editor.EditorScreen
import com.textnote.app.ui.editor.EditorViewModel
import com.textnote.app.ui.files.HomeScreen
import com.textnote.app.ui.settings.SettingsScreen
import com.textnote.app.ui.theme.TextNoteTheme
import com.textnote.app.ui.theme.resolveDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** 外部（文件管理器等）通过 VIEW/EDIT intent 传入的文档 Uri，桥接给 Compose */
    private var externalUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = DocumentRepository(applicationContext)
        val drafts = DraftStore(applicationContext)
        val settings = SettingsRepository(applicationContext)
        val syntaxOverrides = SyntaxOverrides(applicationContext)
        handleOpenIntent(intent)
        setContent {
            // 主题与编辑器字体都由设置决定，所以在最外层就收集。
            // 用 collectAsStateWithLifecycle：应用在后台时不必为了设置变化而重组。
            val appSettings by settings.settings.collectAsStateWithLifecycle(
                initialValue = settings.current,
            )
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

    /** singleTask 模式下，应用已在运行时外部再次打开文件会走这里 */
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

    val scope = rememberCoroutineScope()

    val factory = rememberFactory(repository, drafts, syntaxOverrides)
    val viewModel: EditorViewModel = viewModel(factory = factory)

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
            onBack = { showSettings = false },
        )
    } else if (hasDocument && viewModel.hasDocument) {
        EditorScreen(
            viewModel = viewModel,
            // 按**设置**算，不能用系统深色模式：两者不一致时语法色会与背景撞在一起
            darkTheme = appSettings.resolveDarkTheme(),
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
