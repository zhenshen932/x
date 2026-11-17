package com.omnimedia.hub

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Refined Linear / Apple Pro Dark Monochrome & Titanium Palette (Zero tacky blue/green!)
private val BgCarbon = Color(0xFF0A0A0C)
private val SurfaceGraphite = Color(0xFF121216)
private val CardCharcoal = Color(0xFF18181E)
private val CardElevated = Color(0xFF22222A)
private val BorderHairline = Color(0xFF272730)
private val AccentTitanium = Color(0xFFE4E4E7)
private val AccentDarkInk = Color(0xFF09090B)
private val AccentSoftIndigo = Color(0xFFA5B4FC)
private val TextPrimary = Color(0xFFF4F4F6)
private val TextSecondary = Color(0xFF8E8E99)

sealed class ActivePage {
    object Home : ActivePage()
    data class MediaPlayer(val file: RecentFileEntry) : ActivePage()
    data class ImageViewer(val file: RecentFileEntry) : ActivePage()
    data class UnsupportedFileView(val file: RecentFileEntry) : ActivePage()
}

class MainActivity : ComponentActivity() {

    private var incomingExternalState = mutableStateOf<Triple<Uri, String?, Long>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extractIncomingUri(intent)?.let { (u, m) ->
            incomingExternalState.value = Triple(u, m, System.currentTimeMillis())
        }

        setContent {
            val scheme = darkColorScheme(
                background = BgCarbon,
                surface = SurfaceGraphite,
                surfaceVariant = CardCharcoal,
                primary = AccentTitanium,
                secondaryContainer = CardElevated,
                onBackground = TextPrimary,
                onSurface = TextPrimary
            )
            MaterialTheme(colorScheme = scheme) {
                OmniMediaAppContent(
                    incomingExternal = incomingExternalState.value,
                    onConsumeIncoming = { incomingExternalState.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractIncomingUri(intent)?.let { (u, m) ->
            incomingExternalState.value = Triple(u, m, System.currentTimeMillis())
        }
    }

    private fun extractIncomingUri(intent: Intent?): Pair<Uri, String?>? {
        if (intent == null || intent.action == Intent.ACTION_MAIN) return null
        return try {
            val mime = intent.type
            intent.data?.let { return it to mime }
            val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            if (streamUri != null) return streamUri to mime
            if (intent.clipData != null && intent.clipData!!.itemCount > 0) {
                intent.clipData!!.getItemAt(0)?.uri?.let { return it to mime }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniMediaAppContent(
    incomingExternal: Triple<Uri, String?, Long>?,
    onConsumeIncoming: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val routerManager = remember { AppRouterManager(context) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var lang by remember { mutableStateOf(routerManager.getLanguage()) }
    val isZh = lang == "zh"

    var searchQuery by remember { mutableStateOf("") }
    var isGridView by remember { mutableStateOf(routerManager.isGridViewPreferred()) }
    var sortField by remember { mutableStateOf(routerManager.getSortFieldPreferred()) }
    var sortDescending by remember { mutableStateOf(routerManager.isSortDescendingPreferred()) }
    var categoryFilter by remember { mutableStateOf("all") }

    var recentFiles by remember { mutableStateOf(routerManager.loadRecentFiles()) }

    var activePage by remember { mutableStateOf<ActivePage>(ActivePage.Home) }
    var routerSheetTargetFile by remember { mutableStateOf<RecentFileEntry?>(null) }
    var routerShareMode by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    fun navigateToFilePage(entry: RecentFileEntry) {
        routerSheetTargetFile = null
        activePage = when {
            entry.isMedia -> ActivePage.MediaPlayer(entry)
            entry.isImage -> ActivePage.ImageViewer(entry)
            else -> ActivePage.UnsupportedFileView(entry)
        }
    }

    fun recordAndOpenFile(entry: RecentFileEntry) {
        val updated = (listOf(entry) + recentFiles.filterNot { it.uriString == entry.uriString })
            .distinctBy { it.uriString }
        recentFiles = updated
        routerManager.saveRecentFiles(updated)
        navigateToFilePage(entry)
    }

    suspend fun buildEntryFromUriAsync(uri: Uri, explicitMime: String? = null): RecentFileEntry =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Throwable) {
            }

            var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "File"
            var sizeBytes = 0L

            if (uri.scheme == "content") {
                try {
                    context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIdx >= 0) {
                                cursor.getString(nameIdx)?.let { if (it.isNotBlank()) displayName = it }
                            }
                            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                                sizeBytes = cursor.getLong(sizeIdx)
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            } else if (uri.scheme == "file") {
                uri.path?.let { p ->
                    val f = File(p)
                    displayName = f.name
                    sizeBytes = f.length()
                }
            }

            val resolverMime = try {
                context.contentResolver.getType(uri)
            } catch (_: Throwable) {
                null
            }
            val finalMime = routerManager.detectMimeType(displayName, resolverMime ?: explicitMime)

            RecentFileEntry(
                uriString = uri.toString(),
                name = displayName,
                mimeType = finalMime,
                sizeBytes = sizeBytes,
                sizeLabel = formatFileSize(sizeBytes),
                pathOrSource = uri.path ?: uri.toString(),
                openedTimestamp = System.currentTimeMillis()
            )
        }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val entry = buildEntryFromUriAsync(uri)
                recordAndOpenFile(entry)
            }
        }
    }

    LaunchedEffect(incomingExternal) {
        if (incomingExternal != null) {
            val (extUri, extMime, _) = incomingExternal
            val entry = buildEntryFromUriAsync(extUri, extMime)
            recordAndOpenFile(entry)
            onConsumeIncoming()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BgCarbon)) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = activePage is ActivePage.Home && routerSheetTargetFile == null,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = SurfaceGraphite,
                    drawerContentColor = TextPrimary,
                    modifier = Modifier.width(275.dp)
                ) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(AccentTitanium, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = AccentDarkInk,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = "OmniMedia Hub",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = TextPrimary,
                            maxLines = 1
                        )
                    }

                    HorizontalDivider(color = BorderHairline, modifier = Modifier.padding(vertical = 10.dp))

                    val navItems = listOf(
                        Triple("all", if (isZh) "最近打开的全部文件" else "All Recent Files", Icons.Default.History),
                        Triple("video", if (isZh) "仅看视频文件" else "Videos Only", Icons.Default.Movie),
                        Triple("audio", if (isZh) "仅看音频文件" else "Audio Only", Icons.Default.MusicNote),
                        Triple("image", if (isZh) "仅看图片文件" else "Images Only", Icons.Default.Image),
                        Triple("other", if (isZh) "非音视频中转记录" else "Other Files", Icons.Default.Description)
                    )
                    navItems.forEach { (catKey, label, icon) ->
                        NavigationDrawerItem(
                            label = {
                                Text(
                                    text = label,
                                    fontSize = 14.sp,
                                    fontWeight = if (categoryFilter == catKey) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            selected = categoryFilter == catKey,
                            onClick = {
                                categoryFilter = catKey
                                scope.launch { drawerState.close() }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = CardElevated,
                                selectedTextColor = TextPrimary,
                                selectedIconColor = AccentTitanium,
                                unselectedTextColor = TextSecondary,
                                unselectedIconColor = TextSecondary
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider(color = BorderHairline)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = {
                                lang = if (lang == "zh") "en" else "zh"
                                routerManager.setLanguage(lang)
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = CardCharcoal,
                                contentColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Crossfade(targetState = isZh, animationSpec = tween(150), label = "LangBtn") { zh ->
                                Text(if (zh) "English" else "简体中文", fontSize = 13.sp, maxLines = 1)
                            }
                        }

                        TextButton(
                            onClick = {
                                routerManager.clearAllRecentFiles()
                                recentFiles = emptyList()
                                scope.launch { drawerState.close() }
                            }
                        ) {
                            Text(
                                text = if (isZh) "清空记录" else "Clear",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        ) {
            Scaffold(
                containerColor = BgCarbon,
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = { documentPickerLauncher.launch(arrayOf("*/*")) },
                        shape = RoundedCornerShape(18.dp),
                        containerColor = AccentTitanium,
                        contentColor = AccentDarkInk,
                        modifier = Modifier.size(58.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Open File", modifier = Modifier.size(26.dp))
                    }
                }
            ) { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    // 1. TOP BAR
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } }
                        ) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = "Menu",
                                tint = TextPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .background(SurfaceGraphite, RoundedCornerShape(23.dp))
                                .border(1.dp, BorderHairline, RoundedCornerShape(23.dp))
                                .padding(start = 18.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = if (isZh) "搜索文档" else "Search files",
                                        color = TextSecondary,
                                        fontSize = 15.sp,
                                        maxLines = 1
                                    )
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = TextPrimary,
                                        fontSize = 15.sp,
                                        fontFamily = FontFamily.Default
                                    ),
                                    cursorBrush = SolidColor(AccentTitanium),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(CardElevated, CircleShape)
                                    .clip(CircleShape)
                                    .clickable { documentPickerLauncher.launch(arrayOf("*/*")) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = "Open File",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(SurfaceGraphite, CircleShape)
                                .border(1.dp, BorderHairline, CircleShape)
                                .clip(CircleShape)
                                .clickable { showSettingsDialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = "Settings",
                                tint = TextPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // 2. SECONDARY BAR: Sort Selector + Persisted Smooth List/Grid Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when (sortField) {
                                    "name" -> if (isZh) "文件名称" else "File name"
                                    "size" -> if (isZh) "文件大小" else "File size"
                                    else -> if (isZh) "我打开的日期" else "Date opened"
                                },
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        val nextField = when (sortField) {
                                            "date" -> "name"
                                            "name" -> "size"
                                            else -> "date"
                                        }
                                        sortField = nextField
                                        routerManager.setSortFieldPreferred(nextField)
                                    }
                                    .padding(vertical = 4.dp, horizontal = 4.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val arrowRotation by animateFloatAsState(
                                targetValue = if (sortDescending) 0f else 180f,
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                label = "SortArrowRotation"
                            )
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(CardCharcoal, CircleShape)
                                    .border(1.dp, BorderHairline, CircleShape)
                                    .clip(CircleShape)
                                    .clickable {
                                        val nextDesc = !sortDescending
                                        sortDescending = nextDesc
                                        routerManager.setSortDescendingPreferred(nextDesc)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.ArrowDownward,
                                    contentDescription = "Sort Order",
                                    tint = TextPrimary,
                                    modifier = Modifier
                                        .size(15.dp)
                                        .graphicsLayer { rotationZ = arrowRotation }
                                )
                            }
                        }

                        // Monochrome Titanium Segmented View Toggle
                        val listBg by animateColorAsState(
                            targetValue = if (!isGridView) AccentTitanium else Color.Transparent,
                            animationSpec = tween(160),
                            label = "ListBg"
                        )
                        val gridBg by animateColorAsState(
                            targetValue = if (isGridView) AccentTitanium else Color.Transparent,
                            animationSpec = tween(160),
                            label = "GridBg"
                        )
                        Row(
                            modifier = Modifier
                                .background(SurfaceGraphite, RoundedCornerShape(16.dp))
                                .border(1.dp, BorderHairline, RoundedCornerShape(16.dp))
                                .padding(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(listBg)
                                    .clickable {
                                        if (isGridView) {
                                            isGridView = false
                                            routerManager.setGridViewPreferred(false)
                                        }
                                    }
                                    .padding(horizontal = 15.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.FormatListBulleted,
                                    contentDescription = "List View",
                                    tint = if (!isGridView) AccentDarkInk else TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(gridBg)
                                    .clickable {
                                        if (!isGridView) {
                                            isGridView = true
                                            routerManager.setGridViewPreferred(true)
                                        }
                                    }
                                    .padding(horizontal = 15.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.GridView,
                                    contentDescription = "Grid View",
                                    tint = if (isGridView) AccentDarkInk else TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = BorderHairline, thickness = 1.dp)

                    // 3. RECENT FILES CONTENT (With smooth transitions for both Category Filter AND Grid/List switching!)
                    val filteredFiles = remember(recentFiles, searchQuery, categoryFilter, sortField, sortDescending) {
                        recentFiles
                            .filter { f ->
                                val matchesCat = when (categoryFilter) {
                                    "video" -> f.isVideo
                                    "audio" -> f.isAudio
                                    "image" -> f.isImage
                                    "other" -> !f.isSupportedInternal
                                    else -> true
                                }
                                val matchesQuery = searchQuery.isBlank() ||
                                    f.name.contains(searchQuery, ignoreCase = true) ||
                                    f.mimeType.contains(searchQuery, ignoreCase = true)
                                matchesCat && matchesQuery
                            }
                            .distinctBy { it.uriString }
                            .let { list ->
                                val comparator = when (sortField) {
                                    "name" -> compareBy<RecentFileEntry> { it.name.lowercase() }
                                    "size" -> compareBy<RecentFileEntry> { it.sizeBytes }
                                    else -> compareBy<RecentFileEntry> { it.openedTimestamp }
                                }
                                if (sortDescending) list.sortedWith(comparator.reversed()) else list.sortedWith(comparator)
                            }
                    }

                    // Combined state key so switching Drawer Category OR switching Grid/List has a smooth non-overlapping transition!
                    val contentStateKey = Triple(categoryFilter, isGridView, filteredFiles.isEmpty())

                    AnimatedContent(
                        targetState = contentStateKey,
                        transitionSpec = {
                            (fadeIn(tween(180, easing = FastOutSlowInEasing)) +
                                slideInVertically(
                                    initialOffsetY = { it / 24 },
                                    animationSpec = tween(180, easing = FastOutSlowInEasing)
                                ))
                                .togetherWith(fadeOut(tween(90)))
                        },
                        label = "ContentAndCategoryTransition",
                        modifier = Modifier.fillMaxSize()
                    ) { (_, gridMode, isEmpty) ->
                        if (isEmpty) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(68.dp)
                                            .background(SurfaceGraphite, CircleShape)
                                            .border(1.dp, BorderHairline, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.FolderOpen,
                                            contentDescription = null,
                                            tint = TextSecondary,
                                            modifier = Modifier.size(30.dp)
                                        )
                                    }
                                    Text(
                                        text = if (isZh) "暂无最近打开的文件记录" else "No recent files",
                                        color = TextSecondary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        } else if (gridMode) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(
                                    items = filteredFiles,
                                    key = { it.uriString },
                                    contentType = { "grid_card" }
                                ) { file ->
                                    RecentFileGridCard(
                                        file = file,
                                        isZh = isZh,
                                        onCardClick = { recordAndOpenFile(file) },
                                        onMoreClick = {
                                            routerShareMode = false
                                            routerSheetTargetFile = file
                                        }
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(
                                    items = filteredFiles,
                                    key = { it.uriString },
                                    contentType = { "list_row" }
                                ) { file ->
                                    RecentFileListRow(
                                        file = file,
                                        isZh = isZh,
                                        onRowClick = { recordAndOpenFile(file) },
                                        onMoreClick = {
                                            routerShareMode = false
                                            routerSheetTargetFile = file
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // =====================================================================
        // TELEGRAM-STYLE FULL-SCREEN EXPANDABLE PAGES
        // =====================================================================
        AnimatedVisibility(
            visible = activePage !is ActivePage.Home,
            enter = fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                scaleIn(
                    initialScale = 0.94f,
                    animationSpec = spring(
                        dampingRatio = 0.9f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) +
                slideInVertically(
                    initialOffsetY = { it / 14 },
                    animationSpec = spring(
                        dampingRatio = 0.9f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
            exit = fadeOut(tween(160)) +
                scaleOut(targetScale = 0.96f, animationSpec = tween(160)) +
                slideOutVertically(targetOffsetY = { it / 16 }, animationSpec = tween(160))
        ) {
            when (val page = activePage) {
                is ActivePage.MediaPlayer -> {
                    BuiltInMediaPlayerScreen(
                        file = page.file,
                        isZh = isZh,
                        onBack = { activePage = ActivePage.Home },
                        onOpenRouterSheet = {
                            routerShareMode = false
                            routerSheetTargetFile = page.file
                        }
                    )
                }
                is ActivePage.ImageViewer -> {
                    BuiltInImageViewerScreen(
                        file = page.file,
                        isZh = isZh,
                        onBack = { activePage = ActivePage.Home },
                        onOpenRouterSheet = {
                            routerShareMode = false
                            routerSheetTargetFile = page.file
                        }
                    )
                }
                is ActivePage.UnsupportedFileView -> {
                    ExpandedUnsupportedFileScreen(
                        file = page.file,
                        routerManager = routerManager,
                        isZh = isZh,
                        onBack = { activePage = ActivePage.Home },
                        onOpenRouterSheet = {
                            routerShareMode = false
                            routerSheetTargetFile = page.file
                        }
                    )
                }
                ActivePage.Home -> {}
            }
        }

        // =====================================================================
        // IN-WINDOW 120FPS APP ROUTER SHEET
        // =====================================================================
        val sheetVisible = routerSheetTargetFile != null
        val scrimAlpha by animateFloatAsState(
            targetValue = if (sheetVisible) 0.65f else 0f,
            animationSpec = tween(180),
            label = "SheetScrim"
        )
        if (sheetVisible || scrimAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        routerSheetTargetFile = null
                    }
            )
        }

        AnimatedVisibility(
            visible = sheetVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(
                    dampingRatio = 0.9f,
                    stiffness = 460f
                )
            ) + fadeIn(tween(160)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(180, easing = FastOutSlowInEasing)
            ) + fadeOut(tween(140))
        ) {
            val targetFile = routerSheetTargetFile
            if (targetFile != null) {
                TelegramStyleAppRouterSheet(
                    file = targetFile,
                    initialShareMode = routerShareMode,
                    routerManager = routerManager,
                    isZh = isZh,
                    onDismiss = { routerSheetTargetFile = null },
                    onDeleteRecord = {
                        val targetUri = targetFile.uriString
                        recentFiles = routerManager.removeRecentFile(targetUri)
                        routerSheetTargetFile = null
                        if (activePage !is ActivePage.Home) {
                            activePage = ActivePage.Home
                        }
                    }
                )
            }
        }
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            containerColor = SurfaceGraphite,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = {
                Text(if (isZh) "设置与默认打开方式" else "Settings & Default Handler")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = if (isZh) "界面语言 (Language)" else "Interface Language",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                lang = "zh"
                                routerManager.setLanguage("zh")
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isZh) AccentTitanium else CardCharcoal,
                                contentColor = if (isZh) AccentDarkInk else TextPrimary
                            )
                        ) {
                            Text("简体中文")
                        }
                        Button(
                            onClick = {
                                lang = "en"
                                routerManager.setLanguage("en")
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!isZh) AccentTitanium else CardCharcoal,
                                contentColor = if (!isZh) AccentDarkInk else TextPrimary
                            )
                        ) {
                            Text("English")
                        }
                    }

                    HorizontalDivider(color = BorderHairline)

                    FilledTonalButton(
                        onClick = {
                            showSettingsDialog = false
                            try {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                            } catch (_: Throwable) {
                                context.startActivity(Intent(Settings.ACTION_SETTINGS))
                            }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = CardCharcoal,
                            contentColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isZh) "前往系统默认应用设置" else "System Default Apps Settings")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text(if (isZh) "关闭" else "Close", color = TextPrimary)
                }
            }
        )
    }
}

// =============================================================================
// GRID CARD & LIST ROW (Zero offscreen .clip() layers for ultra-fast rendering!)
// =============================================================================
@Composable
fun RecentFileGridCard(
    file: RecentFileEntry,
    isZh: Boolean,
    onCardClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    val badgeIcon = when {
        file.isVideo -> Icons.Default.PlayArrow
        file.isAudio -> Icons.Default.GraphicEq
        file.isImage -> Icons.Default.Image
        else -> Icons.Default.Description
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardCharcoal, RoundedCornerShape(16.dp))
            .border(1.dp, BorderHairline, RoundedCornerShape(16.dp))
            .clickable { onCardClick() }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(CardElevated, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = badgeIcon,
                    contentDescription = null,
                    tint = AccentTitanium,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = file.name,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onMoreClick,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More Options",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceGraphite, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(CardElevated, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = file.extension,
                        color = AccentTitanium,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = file.sizeLabel,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = file.mimeType,
                color = TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = formatTimestamp(file.openedTimestamp, isZh),
                color = TextSecondary,
                fontSize = 10.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
fun RecentFileListRow(
    file: RecentFileEntry,
    isZh: Boolean,
    onRowClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardCharcoal, RoundedCornerShape(14.dp))
            .border(1.dp, BorderHairline, RoundedCornerShape(14.dp))
            .clickable { onRowClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(CardElevated, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = file.extension,
                color = AccentTitanium,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${file.sizeLabel} • ${file.mimeType} • ${formatTimestamp(file.openedTimestamp, isZh)}",
                color = TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onMoreClick) {
            Icon(Icons.Default.MoreVert, contentDescription = "More Options", tint = TextSecondary)
        }
    }
}

// =============================================================================
// 1. BUILT-IN VIDEO & AUDIO PLAYER SCREEN
// - Video Player: 3-dots button placed at Top-Right (matching 1000043487.jpg!)
// - Audio Player: 3-dots button placed at the right side of the transport buttons row (matching 1000043485.jpg!)
// - Bottom giant bar completely removed!
// =============================================================================
@Composable
fun BuiltInMediaPlayerScreen(
    file: RecentFileEntry,
    isZh: Boolean,
    onBack: () -> Unit,
    onOpenRouterSheet: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var playerErrorMsg by remember { mutableStateOf<String?>(null) }

    val exoPlayer = remember(file.uriString) {
        ExoPlayer.Builder(context).build().apply {
            try {
                val mediaItem = MediaItem.fromUri(Uri.parse(file.uriString))
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            } catch (e: Throwable) {
                playerErrorMsg = e.localizedMessage ?: "Playback error"
            }
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        durationMs = duration.coerceAtLeast(0L)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    playerErrorMsg = error.localizedMessage ?: "Playback error"
                }
            })
        }
    }

    LaunchedEffect(exoPlayer, file.isAudio) {
        if (file.isAudio) {
            while (true) {
                currentPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                val dur = exoPlayer.duration
                if (dur > 0) durationMs = dur
                delay(200L)
            }
        }
    }

    DisposableEffect(file.uriString) {
        onDispose {
            try {
                exoPlayer.release()
            } catch (_: Throwable) {
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgCarbon)
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceGraphite)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${file.extension} • ${file.sizeLabel} • ${file.mimeType}",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }

            Box(
                modifier = Modifier
                    .background(CardCharcoal, RoundedCornerShape(10.dp))
                    .border(1.dp, BorderHairline, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
                        val nextIdx = (speeds.indexOf(playbackSpeed) + 1) % speeds.size
                        playbackSpeed = speeds[nextIdx]
                        exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
                    }
                    .padding(horizontal = 11.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "${playbackSpeed}x",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }

            // For Video files, 3-dots button is in Top-Right (matching 1000043487.jpg!)
            if (file.isVideo) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onOpenRouterSheet) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Open With or Share",
                        tint = TextPrimary
                    )
                }
            }
        }

        // Main Stage
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (file.isVideo) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                        }
                    },
                    update = { view ->
                        if (view.player != exoPlayer) {
                            view.player = exoPlayer
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Dedicated Audio Player UI:
                // Center circular waveform + bottom transport row with 3-dots on the right (matching 1000043485.jpg!)
                val infiniteTransition = rememberInfiniteTransition(label = "AudioPulse")
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 0.94f,
                    targetValue = if (isPlaying) 1.07f else 0.96f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(850, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "PulseScale"
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Spacer(modifier = Modifier.height(10.dp))

                    // Center Waveform & Track Info
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(124.dp)
                                .graphicsLayer {
                                    scaleX = if (isPlaying) pulseScale else 1f
                                    scaleY = if (isPlaying) pulseScale else 1f
                                }
                                .background(CardCharcoal, CircleShape)
                                .border(1.5.dp, BorderHairline, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = AccentTitanium,
                                modifier = Modifier.size(54.dp)
                            )
                        }

                        Text(
                            text = file.name,
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Text(
                            text = "${file.extension} • ${file.sizeLabel}",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }

                    // Bottom Audio Transport Area
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val total = durationMs.coerceAtLeast(1L)
                        val progressFraction = (currentPosMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)

                        Slider(
                            value = progressFraction,
                            onValueChange = { frac ->
                                val target = (frac * total).toLong()
                                currentPosMs = target
                                exoPlayer.seekTo(target)
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = AccentTitanium,
                                activeTrackColor = AccentTitanium,
                                inactiveTrackColor = CardElevated
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatDurationMs(currentPosMs),
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = formatDurationMs(durationMs),
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Symmetric Control Row:
                        // Left spacer (for visual center balance) | Rewind | Play/Pause | Forward | Right 3-dots button (matching 1000043485.jpg!)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Invisible balance box matching the right 3-dots button width so Play/Pause stays dead-center
                            Spacer(modifier = Modifier.size(48.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(22.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(50.dp)
                                        .background(CardCharcoal, RoundedCornerShape(16.dp))
                                        .border(1.dp, BorderHairline, RoundedCornerShape(16.dp))
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            val nextPos = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                            exoPlayer.seekTo(nextPos)
                                            currentPosMs = nextPos
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.FastRewind,
                                        contentDescription = "Rewind 10s",
                                        tint = TextPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                val btnScale by animateFloatAsState(
                                    targetValue = if (isPlaying) 1f else 0.95f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                                    label = "PlayBtnScale"
                                )
                                Box(
                                    modifier = Modifier
                                        .size(66.dp)
                                        .graphicsLayer {
                                            scaleX = btnScale
                                            scaleY = btnScale
                                        }
                                        .background(AccentTitanium, RoundedCornerShape(22.dp))
                                        .clip(RoundedCornerShape(22.dp))
                                        .clickable {
                                            if (exoPlayer.isPlaying) {
                                                exoPlayer.pause()
                                            } else {
                                                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                                    exoPlayer.seekTo(0L)
                                                }
                                                exoPlayer.play()
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Crossfade(targetState = isPlaying, animationSpec = tween(140), label = "PlayPauseIcon") { playing ->
                                        Icon(
                                            imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = "Play or Pause",
                                            tint = AccentDarkInk,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .size(50.dp)
                                        .background(CardCharcoal, RoundedCornerShape(16.dp))
                                        .border(1.dp, BorderHairline, RoundedCornerShape(16.dp))
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            val nextPos = (exoPlayer.currentPosition + 10_000L).coerceAtMost(durationMs.coerceAtLeast(0L))
                                            exoPlayer.seekTo(nextPos)
                                            currentPosMs = nextPos
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.FastForward,
                                        contentDescription = "Forward 10s",
                                        tint = TextPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            // 3-Dots Button on the right side of the transport row (Exact position drawn in 1000043485.jpg!)
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(CardCharcoal, RoundedCornerShape(15.dp))
                                    .border(1.dp, BorderHairline, RoundedCornerShape(15.dp))
                                    .clip(RoundedCornerShape(15.dp))
                                    .clickable { onOpenRouterSheet() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.MoreHoriz,
                                    contentDescription = "Open With or Share",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (playerErrorMsg != null) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth()
                        .background(SurfaceGraphite, RoundedCornerShape(16.dp))
                        .border(1.dp, BorderHairline, RoundedCornerShape(16.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        text = if (isZh) "当前编码流需外部解码器播放" else "Stream requires external decoder",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = playerErrorMsg ?: "",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// =============================================================================
// 2. BUILT-IN IMAGE VIEWER SCREEN
// - 3-dots button placed at Top-Right (matching 1000043481.jpg!)
// - Bottom giant bar completely removed!
// =============================================================================
@Composable
fun BuiltInImageViewerScreen(
    file: RecentFileEntry,
    isZh: Boolean,
    onBack: () -> Unit,
    onOpenRouterSheet: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current

    var imageBitmap by remember(file.uriString) { mutableStateOf<ImageBitmap?>(null) }
    var loadError by remember(file.uriString) { mutableStateOf(false) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotation by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(file.uriString) {
        loadError = false
        val bmp = withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(file.uriString)
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, boundsOpts)
                }
                var sampleSize = 1
                val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
                while (maxDim / sampleSize > 2048) {
                    sampleSize *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOpts)?.asImageBitmap()
                }
            } catch (_: Throwable) {
                null
            }
        }
        if (bmp != null) {
            imageBitmap = bmp
        } else {
            loadError = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgCarbon)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceGraphite)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${file.extension} • ${file.sizeLabel} • ${file.mimeType}",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }

            IconButton(
                onClick = { rotation = (rotation + 90f) % 360f }
            ) {
                Icon(Icons.Default.RotateRight, contentDescription = "Rotate", tint = TextSecondary)
            }

            // 3-Dots Button at Top-Right (matching 1000043481.jpg!)
            IconButton(onClick = onOpenRouterSheet) {
                Icon(Icons.Default.MoreVert, contentDescription = "Open With or Share", tint = TextPrimary)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.05f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = newScale
                        if (newScale > 1f) {
                            offset += pan
                        } else {
                            offset = Offset.Zero
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                imageBitmap != null -> {
                    Image(
                        bitmap = imageBitmap!!,
                        contentDescription = file.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                                rotationZ = rotation
                            }
                    )
                }
                loadError -> {
                    Text(
                        text = if (isZh) "无法解码该图片，请点击右上角三个点用其他应用打开" else "Cannot decode image, tap top-right menu to open externally",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                }
                else -> {
                    CircularProgressIndicator(color = AccentTitanium)
                }
            }
        }
    }
}

// =============================================================================
// 3. EXPANDED UNSUPPORTED FILE SCREEN
// - 3-dots button placed at Top-Right (matching 1000043483.jpg!)
// - Bottom giant bar completely removed!
// - Compact info header so all Pinned & Frequent app icons display cleanly without clipping!
// =============================================================================
@Composable
fun ExpandedUnsupportedFileScreen(
    file: RecentFileEntry,
    routerManager: AppRouterManager,
    isZh: Boolean,
    onBack: () -> Unit,
    onOpenRouterSheet: () -> Unit
) {
    BackHandler(onBack = onBack)

    val topApps = remember(file.uriString) {
        routerManager.getAvailableAppsFast(
            fileName = file.name,
            mimeType = file.mimeType,
            forShare = false,
            includeAllApps = true
        ).take(12)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgCarbon)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceGraphite)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${file.extension} • ${file.sizeLabel} • ${file.mimeType}",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }

            // 3-Dots Button at Top-Right (matching 1000043483.jpg!)
            IconButton(onClick = onOpenRouterSheet) {
                Icon(Icons.Default.MoreVert, contentDescription = "Open With or Share", tint = TextPrimary)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Compact, refined file status card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceGraphite, RoundedCornerShape(16.dp))
                    .border(1.dp, BorderHairline, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(CardElevated, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = file.extension,
                            color = AccentTitanium,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isZh) "本应用无法直接打开此文件" else "Cannot open this file format directly",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isZh)
                                "请选择下方常用应用或点击右上角三个点打开/分享"
                            else
                                "Select a frequent app below or tap the top-right menu",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                HorizontalDivider(color = BorderHairline)

                Text(
                    text = file.name,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${file.sizeLabel} • ${file.mimeType}",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }

            if (topApps.isNotEmpty()) {
                Text(
                    text = if (isZh) "置顶与常用打开方式" else "Pinned & Frequent Apps",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    items(
                        items = topApps,
                        key = { it.componentKey }
                    ) { app ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CardCharcoal, RoundedCornerShape(14.dp))
                                .border(
                                    1.dp,
                                    if (app.isPinned) AccentTitanium.copy(alpha = 0.45f) else BorderHairline,
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    routerManager.launchExternalApp(
                                        target = app,
                                        rawUriString = file.uriString,
                                        fileName = file.name,
                                        mimeType = file.mimeType,
                                        forShare = false
                                    )
                                }
                                .padding(vertical = 12.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (app.iconBitmap != null) {
                                Image(
                                    bitmap = app.iconBitmap,
                                    contentDescription = app.label,
                                    modifier = Modifier.size(38.dp)
                                )
                            } else {
                                Icon(
                                    Icons.Default.Apps,
                                    contentDescription = null,
                                    tint = AccentTitanium,
                                    modifier = Modifier.size(38.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = app.label,
                                color = TextPrimary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// 4. IN-WINDOW 120FPS APP ROUTER SHEET
// - Zero offscreen .clip() layers on list rows!
// - Fixed-height rows + pre-sorted lowercase labels + Release-level JIT enabled!
// =============================================================================
@Composable
fun TelegramStyleAppRouterSheet(
    file: RecentFileEntry,
    initialShareMode: Boolean,
    routerManager: AppRouterManager,
    isZh: Boolean,
    onDismiss: () -> Unit,
    onDeleteRecord: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    var forShare by remember { mutableStateOf(initialShareMode) }
    var includeAllApps by remember { mutableStateOf(true) }
    var appSearch by remember { mutableStateOf("") }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    val allApps = remember(file.uriString, forShare, includeAllApps, refreshTrigger) {
        routerManager.getAvailableAppsFast(
            fileName = file.name,
            mimeType = file.mimeType,
            forShare = forShare,
            includeAllApps = includeAllApps
        )
    }

    val filteredApps = remember(allApps, appSearch) {
        if (appSearch.isBlank()) {
            allApps
        } else {
            val q = appSearch.trim().lowercase()
            allApps.filter {
                it.labelLower.contains(q) || it.packageName.lowercase().contains(q)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.82f)
            .background(SurfaceGraphite, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .border(1.dp, BorderHairline, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
            .padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .background(BorderHairline, RoundedCornerShape(2.dp))
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val openTabBg by animateColorAsState(
                targetValue = if (!forShare) AccentTitanium else Color.Transparent,
                animationSpec = tween(150),
                label = "OpenTabBg"
            )
            val shareTabBg by animateColorAsState(
                targetValue = if (forShare) AccentTitanium else Color.Transparent,
                animationSpec = tween(150),
                label = "ShareTabBg"
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(CardCharcoal, RoundedCornerShape(12.dp))
                    .border(1.dp, BorderHairline, RoundedCornerShape(12.dp))
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(openTabBg, RoundedCornerShape(10.dp))
                        .clickable { forShare = false },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isZh) "用其他应用打开" else "Open With",
                        color = if (!forShare) AccentDarkInk else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(shareTabBg, RoundedCornerShape(10.dp))
                        .clickable { forShare = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isZh) "分享给..." else "Share To...",
                        color = if (forShare) AccentDarkInk else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            IconButton(onClick = onDeleteRecord, modifier = Modifier.size(38.dp)) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Remove Record",
                    tint = TextSecondary
                )
            }

            IconButton(onClick = onDismiss, modifier = Modifier.size(38.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = TextSecondary
                )
            }
        }

        Text(
            text = "${file.name} (${file.mimeType})",
            color = TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 6.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgCarbon, RoundedCornerShape(12.dp))
                .clickable { includeAllApps = !includeAllApps }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (isZh) "显示全部已安装应用（防止小众编辑软件漏识别）" else "Show all installed apps (Include niche editors)",
                color = TextPrimary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = includeAllApps,
                onCheckedChange = { includeAllApps = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AccentDarkInk,
                    checkedTrackColor = AccentTitanium,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = CardCharcoal
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .background(CardCharcoal, RoundedCornerShape(12.dp))
                .border(1.dp, BorderHairline, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (appSearch.isEmpty()) {
                    Text(
                        if (isZh) "搜索应用名称或包名..." else "Search app name or package...",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = appSearch,
                    onValueChange = { appSearch = it },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(AccentTitanium),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (appSearch.isNotEmpty()) {
                IconButton(onClick = { appSearch = "" }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Zero-clip, fixed-height rows for 120fps scrolling
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                items = filteredApps,
                key = { it.packageName },
                contentType = { "app_row" }
            ) { app ->
                val rowBg = if (app.isPinned) CardElevated else CardCharcoal
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .background(rowBg, RoundedCornerShape(12.dp))
                        .clickable {
                            onDismiss()
                            routerManager.launchExternalApp(
                                target = app,
                                rawUriString = file.uriString,
                                fileName = file.name,
                                mimeType = file.mimeType,
                                forShare = forShare
                            )
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (app.iconBitmap != null) {
                        Image(
                            bitmap = app.iconBitmap,
                            contentDescription = app.label,
                            modifier = Modifier.size(36.dp)
                        )
                    } else {
                        Icon(
                            Icons.Default.Android,
                            contentDescription = null,
                            tint = AccentTitanium,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = app.label,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (app.isPinned) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isZh) "置顶" else "PINNED",
                                    color = AccentTitanium,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (app.usageCount > 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isZh) "常用 ${app.usageCount} 次" else "Used ${app.usageCount}x",
                                    color = TextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Text(
                            text = app.packageName,
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = {
                            routerManager.togglePin(app.packageName)
                            refreshTrigger++
                        }
                    ) {
                        Icon(
                            imageVector = if (app.isPinned) Icons.Default.PushPin else Icons.Default.StarBorder,
                            contentDescription = "Pin",
                            tint = if (app.isPinned) AccentTitanium else TextSecondary
                        )
                    }
                }
            }
        }
    }
}

fun formatDurationMs(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000L
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", min, sec)
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "未知大小"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

fun formatTimestamp(ts: Long, isZh: Boolean): String {
    if (ts <= 0L) return ""
    val timeStr = try {
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ts))
    } catch (_: Throwable) {
        ""
    }
    if (timeStr.isEmpty()) return ""
    return if (isZh) "$timeStr 打开" else "Opened $timeStr"
}
