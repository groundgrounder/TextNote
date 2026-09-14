package com.textnote.app.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.textnote.app.R
import com.textnote.app.data.DocumentMeta
import com.textnote.app.data.DraftMeta
import com.textnote.app.data.DraftStore

/** 草稿离自动清理还剩多少天之内才提醒。太早说等于噪音，太晚说等于没说。 */
private const val EXPIRY_HINT_DAYS = 7

/**
 * 首页：打开入口 + 最近打开 + 未保存草稿。
 *
 * 用 LazyColumn 而不是居中的 Column，是因为列表可能长到几十项；另外最近列表里那些
 * **已失效**的条目也留在这里（灰显 + 说明），而不是过滤掉——用户需要知道「这个文件我开过，
 * 只是授权没了」，直接消失只会让人以为是应用把记录弄丢了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    recents: List<DocumentMeta>,
    drafts: List<DraftMeta>,
    formatTime: (Long) -> String,
    onOpenClick: () -> Unit,
    onNewClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRecentClick: (DocumentMeta) -> Unit,
    onRecentRemove: (DocumentMeta) -> Unit,
    onDraftClick: (DraftMeta) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings_open),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "open") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.open_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 「打开文件」在前保持原位（它是最常用的入口，挪位置会打断肌肉记忆），
                    // 「新建」跟在后面用描边样式，两者一眼能分开。
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onOpenClick) {
                            Text(stringResource(R.string.open_file))
                        }
                        OutlinedButton(onClick = onNewClick) {
                            Text(stringResource(R.string.new_file))
                        }
                    }
                }
            }

            if (recents.isNotEmpty()) {
                item(key = "recents-label") { SectionLabel(stringResource(R.string.recent_files)) }
                items(recents, key = { "r:${it.uri}" }) { meta ->
                    RecentRow(
                        meta = meta,
                        time = formatTime(meta.openedAt),
                        onClick = { onRecentClick(meta) },
                        onRemove = { onRecentRemove(meta) },
                    )
                }
            }

            if (drafts.isNotEmpty()) {
                item(key = "drafts-label") { SectionLabel(stringResource(R.string.unsaved_drafts)) }
                items(drafts, key = { "d:${it.uri}" }) { meta ->
                    DraftRow(
                        meta = meta,
                        time = formatTime(meta.updatedAt),
                        onClick = { onDraftClick(meta) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun RecentRow(
    meta: DocumentMeta,
    time: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    ItemCard(onClick = onClick) {
        Column(Modifier.weight(1f)) {
            Text(
                text = meta.name,
                style = MaterialTheme.typography.bodyMedium,
                // 失效的条目灰一点：它仍然可点，但点下去是去重新授权而不是打开
                color = if (meta.accessible) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.accessible) {
                if (meta.snippet.isNotBlank()) {
                    Text(
                        text = meta.snippet,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                // 说清「点它会发生什么」，否则用户会以为这一项坏了
                Text(
                    text = stringResource(R.string.recent_needs_permission),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.remove_from_list),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DraftRow(
    meta: DraftMeta,
    time: String,
    onClick: () -> Unit,
) {
    ItemCard(onClick = onClick) {
        Column(Modifier.weight(1f)) {
            Text(
                text = meta.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 快被自动清理的草稿要说出来。草稿是没写回文件的**用户内容**，
            // 静默清掉等于替用户决定——平时不打扰，只在真的快到了才提示（见 DraftStore.prune）。
            val days = remember(meta.updatedAt) { DraftStore.daysUntilExpiry(meta.updatedAt) }
            if (days <= EXPIRY_HINT_DAYS) {
                Text(
                    text = stringResource(R.string.draft_expires_in, days),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Text(
            text = stringResource(R.string.draft_chars_format, meta.chars),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 列表项外壳。右侧控件区用 `end = 4.dp` 收窄——IconButton 自带 48dp 触控区，
 * 留 12dp 会让图标看起来缩在中间。
 */
@Composable
private fun ItemCard(
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}
