package com.omnimedia.hub

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class ExternalAppTarget(
    val packageName: String,
    val activityName: String,
    val label: String,
    val labelLower: String,
    val isPinned: Boolean,
    val usageCount: Int,
    val isDirectMimeMatch: Boolean,
    val iconBitmap: ImageBitmap?
) {
    val componentKey: String get() = packageName
}

private data class CachedAppMeta(
    val packageName: String,
    val activityName: String,
    val label: String,
    val labelLower: String,
    val iconBitmap: ImageBitmap?
)

@Immutable
data class RecentFileEntry(
    val uriString: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sizeLabel: String,
    val pathOrSource: String,
    val openedTimestamp: Long
) {
    val extension: String
        get() = name.substringAfterLast('.', "").uppercase().take(6).ifEmpty {
            mimeType.substringAfterLast('/', "FILE").uppercase().take(6)
        }

    val isVideo: Boolean
        get() {
            val lowerName = name.lowercase()
            val lowerMime = mimeType.lowercase()
            return lowerMime.startsWith("video/") ||
                lowerMime == "application/mp4" ||
                lowerMime == "application/x-matroska" ||
                lowerMime == "application/vnd.apple.mpegurl" ||
                lowerMime == "application/x-mpegurl" ||
                lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") ||
                lowerName.endsWith(".avi") || lowerName.endsWith(".mov") ||
                lowerName.endsWith(".flv") || lowerName.endsWith(".webm") ||
                lowerName.endsWith(".ts") || lowerName.endsWith(".m4v") ||
                lowerName.endsWith(".3gp") || lowerName.endsWith(".wmv")
        }

    val isAudio: Boolean
        get() {
            val lowerName = name.lowercase()
            val lowerMime = mimeType.lowercase()
            return lowerMime.startsWith("audio/") ||
                lowerMime.contains("flac") ||
                lowerMime.contains("ogg") ||
                lowerMime.contains("opus") ||
                lowerMime.contains("mpegurl") ||
                lowerName.endsWith(".mp3") || lowerName.endsWith(".flac") ||
                lowerName.endsWith(".wav") || lowerName.endsWith(".aac") ||
                lowerName.endsWith(".ogg") || lowerName.endsWith(".m4a") ||
                lowerName.endsWith(".opus") || lowerName.endsWith(".wma") ||
                lowerName.endsWith(".ape") || lowerName.endsWith(".alac")
        }

    val isImage: Boolean
        get() {
            val lowerName = name.lowercase()
            val lowerMime = mimeType.lowercase()
            return lowerMime.startsWith("image/") ||
                lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                lowerName.endsWith(".png") || lowerName.endsWith(".webp") ||
                lowerName.endsWith(".gif") || lowerName.endsWith(".bmp") ||
                lowerName.endsWith(".heic") || lowerName.endsWith(".heif") ||
                lowerName.endsWith(".avif")
        }

    val isMedia: Boolean get() = isVideo || isAudio
    val isSupportedInternal: Boolean get() = isVideo || isAudio || isImage
}

class AppRouterManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("omnimedia_router_v5", Context.MODE_PRIVATE)

    companion object {
        private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val iconCache = ConcurrentHashMap<String, ImageBitmap>()
        private val labelCache = ConcurrentHashMap<String, String>()
        private val mimeMatchCache = ConcurrentHashMap<String, Set<String>>()
        @Volatile
        private var cachedAllApps: List<CachedAppMeta> = emptyList()
        @Volatile
        private var isPrewarming = false
    }

    init {
        prewarmAppCacheAsync()
    }

    fun prewarmAppCacheAsync() {
        if (cachedAllApps.isNotEmpty() || isPrewarming) return
        isPrewarming = true
        bgScope.launch {
            try {
                val appCtx = context.applicationContext ?: context
                val pm = appCtx.packageManager
                val probeUri = Uri.parse("content://${appCtx.packageName}.fileprovider/probe")
                val combinedByPkg = LinkedHashMap<String, ResolveInfo>()

                val viewIntent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(probeUri, "*/*") }
                for (info in queryIntentList(pm, viewIntent)) {
                    val pkg = info.activityInfo?.packageName ?: continue
                    if (pkg != appCtx.packageName && !combinedByPkg.containsKey(pkg)) {
                        combinedByPkg[pkg] = info
                    }
                }

                val sendIntent = Intent(Intent.ACTION_SEND).apply { type = "*/*" }
                for (info in queryIntentList(pm, sendIntent)) {
                    val pkg = info.activityInfo?.packageName ?: continue
                    if (pkg != appCtx.packageName && !combinedByPkg.containsKey(pkg)) {
                        combinedByPkg[pkg] = info
                    }
                }

                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                for (info in queryIntentList(pm, launcherIntent)) {
                    val pkg = info.activityInfo?.packageName ?: continue
                    if (pkg != appCtx.packageName && !combinedByPkg.containsKey(pkg)) {
                        combinedByPkg[pkg] = info
                    }
                }

                val built = ArrayList<CachedAppMeta>(combinedByPkg.size)
                for ((pkg, info) in combinedByPkg) {
                    val act = info.activityInfo?.name ?: continue
                    val label = labelCache[pkg] ?: try {
                        val appInfo = info.activityInfo?.applicationInfo
                        val rawLabel = if (appInfo != null) pm.getApplicationLabel(appInfo).toString() else info.loadLabel(pm).toString()
                        rawLabel.ifBlank { pkg }
                    } catch (_: Throwable) {
                        pkg
                    }.also { labelCache[pkg] = it }

                    val iconBmp = iconCache[pkg] ?: try {
                        val d = info.activityInfo?.applicationInfo?.loadIcon(pm) ?: info.loadIcon(pm)
                        drawableToScaledImageBitmap(d)
                    } catch (_: Throwable) {
                        null
                    }?.also { iconCache[pkg] = it }

                    built.add(CachedAppMeta(pkg, act, label, label.lowercase(), iconBmp))
                }
                built.sortBy { it.labelLower }
                cachedAllApps = built
            } catch (_: Throwable) {
            } finally {
                isPrewarming = false
            }
        }
    }

    fun getLanguage(): String = prefs.getString("app_lang", "zh") ?: "zh"
    fun setLanguage(lang: String) {
        prefs.edit().putString("app_lang", lang).apply()
    }

    fun isGridViewPreferred(): Boolean = prefs.getBoolean("pref_is_grid_view", true)
    fun setGridViewPreferred(isGrid: Boolean) {
        prefs.edit().putBoolean("pref_is_grid_view", isGrid).apply()
    }

    fun getSortFieldPreferred(): String = prefs.getString("pref_sort_field", "date") ?: "date"
    fun setSortFieldPreferred(field: String) {
        prefs.edit().putString("pref_sort_field", field).apply()
    }

    fun isSortDescendingPreferred(): Boolean = prefs.getBoolean("pref_sort_desc", true)
    fun setSortDescendingPreferred(desc: Boolean) {
        prefs.edit().putBoolean("pref_sort_desc", desc).apply()
    }

    fun isIncludeAllAppsPreferred(): Boolean = prefs.getBoolean("pref_include_all_apps", true)
    fun setIncludeAllAppsPreferred(includeAll: Boolean) {
        prefs.edit().putBoolean("pref_include_all_apps", includeAll).apply()
    }

    fun isRepeatOnePreferred(): Boolean = prefs.getBoolean("pref_repeat_one", false)
    fun setRepeatOnePreferred(repeatOne: Boolean) {
        prefs.edit().putBoolean("pref_repeat_one", repeatOne).apply()
    }

    fun loadRecentFiles(): List<RecentFileEntry> {
        val raw = prefs.getString("recent_files_json", null) ?: return emptyList()
        val list = mutableListOf<RecentFileEntry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val uriStr = obj.optString("uriString")
                if (uriStr.isNotBlank()) {
                    list.add(
                        RecentFileEntry(
                            uriString = uriStr,
                            name = obj.optString("name", "Unknown"),
                            mimeType = obj.optString("mimeType", "*/*"),
                            sizeBytes = obj.optLong("sizeBytes", 0L),
                            sizeLabel = obj.optString("sizeLabel", "0 B"),
                            pathOrSource = obj.optString("pathOrSource", ""),
                            openedTimestamp = obj.optLong("openedTimestamp", 0L)
                        )
                    )
                }
            }
        } catch (_: Throwable) {
        }
        return list.distinctBy { it.uriString }
    }

    fun saveRecentFiles(files: List<RecentFileEntry>) {
        try {
            val arr = JSONArray()
            files.distinctBy { it.uriString }.take(200).forEach { f ->
                val obj = JSONObject().apply {
                    put("uriString", f.uriString)
                    put("name", f.name)
                    put("mimeType", f.mimeType)
                    put("sizeBytes", f.sizeBytes)
                    put("sizeLabel", f.sizeLabel)
                    put("pathOrSource", f.pathOrSource)
                    put("openedTimestamp", f.openedTimestamp)
                }
                arr.put(obj)
            }
            prefs.edit().putString("recent_files_json", arr.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    fun removeRecentFile(uriString: String): List<RecentFileEntry> {
        val updated = loadRecentFiles().filterNot { it.uriString == uriString }
        saveRecentFiles(updated)
        return updated
    }

    fun clearAllRecentFiles() {
        prefs.edit().remove("recent_files_json").apply()
    }

    fun detectMimeType(fileName: String, fallbackMime: String?): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val byExt = when (ext) {
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "flv" -> "video/x-flv"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            "3gp" -> "video/3gpp"
            "wmv" -> "video/x-ms-wmv"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "ogg", "opus" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "wma" -> "audio/x-ms-wma"
            "ape" -> "audio/x-ape"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "avif" -> "image/avif"
            "apk" -> "application/vnd.android.package-archive"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "py" -> "text/x-python"
            "html", "htm" -> "text/html"
            "txt", "log", "ass", "srt", "json", "xml", "md" -> "text/plain"
            else -> null
        }
        if (byExt != null) return byExt
        if (!fallbackMime.isNullOrBlank() && fallbackMime != "*/*" && fallbackMime != "application/octet-stream") {
            return fallbackMime
        }
        if (ext.isNotEmpty()) {
            try {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return fallbackMime ?: "*/*"
    }

    fun getAvailableAppsFast(
        fileName: String,
        mimeType: String,
        forShare: Boolean,
        includeAllApps: Boolean
    ): List<ExternalAppTarget> {
        return try {
            val currentCache = cachedAllApps
            if (currentCache.isEmpty()) return emptyList()

            val exactPkgs: Set<String> = if (includeAllApps) {
                emptySet()
            } else {
                val effectiveMime = detectMimeType(fileName, mimeType)
                val cacheKey = "${if (forShare) "S" else "V"}_$effectiveMime"
                mimeMatchCache.getOrPut(cacheKey) {
                    val pm = context.packageManager
                    val probeUri = Uri.parse("content://${context.packageName}.fileprovider/probe")
                    val action = if (forShare) Intent.ACTION_SEND else Intent.ACTION_VIEW
                    val exactIntent = Intent(action).apply {
                        if (forShare) {
                            type = effectiveMime
                            putExtra(Intent.EXTRA_STREAM, probeUri)
                        } else {
                            setDataAndType(probeUri, effectiveMime)
                        }
                    }
                    val matches = queryIntentList(pm, exactIntent)
                    val set = HashSet<String>(matches.size)
                    for (info in matches) {
                        info.activityInfo?.packageName?.let { set.add(it) }
                    }
                    set
                }
            }

            val sourceList = if (includeAllApps) {
                currentCache
            } else {
                currentCache.filter {
                    exactPkgs.contains(it.packageName) || isPinned(it.packageName) || getUsageCount(it.packageName) > 0
                }
            }

            val result = ArrayList<ExternalAppTarget>(sourceList.size)
            for (meta in sourceList) {
                val pkg = meta.packageName
                result.add(
                    ExternalAppTarget(
                        packageName = pkg,
                        activityName = meta.activityName,
                        label = meta.label,
                        labelLower = meta.labelLower,
                        isPinned = isPinned(pkg),
                        usageCount = getUsageCount(pkg),
                        isDirectMimeMatch = true,
                        iconBitmap = meta.iconBitmap
                    )
                )
            }

            result.sortWith { a, b ->
                if (a.isPinned != b.isPinned) {
                    if (a.isPinned) -1 else 1
                } else if (a.usageCount != b.usageCount) {
                    b.usageCount.compareTo(a.usageCount)
                } else {
                    a.labelLower.compareTo(b.labelLower)
                }
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun resolveSafeUriForLaunch(rawUriString: String): Uri {
        val uri = Uri.parse(rawUriString)
        if (uri.scheme == "file") {
            val path = uri.path
            if (path != null) {
                val file = File(path)
                if (file.exists()) {
                    return try {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                    } catch (_: Throwable) {
                        copyToCacheAndGetUri(file) ?: uri
                    }
                }
            }
        }
        return uri
    }

    private fun copyToCacheAndGetUri(sourceFile: File): Uri? {
        return try {
            val shareDir = File(context.cacheDir, "shared_media")
            if (!shareDir.exists()) shareDir.mkdirs()
            val destFile = File(shareDir, sourceFile.name)
            sourceFile.inputStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
        } catch (_: Throwable) {
            null
        }
    }

    fun togglePin(packageName: String): Boolean {
        val next = !isPinned(packageName)
        prefs.edit().putBoolean("pin_$packageName", next).apply()
        return next
    }

    fun launchExternalApp(
        target: ExternalAppTarget,
        rawUriString: String,
        fileName: String,
        mimeType: String,
        forShare: Boolean
    ) {
        val pkg = target.packageName
        prefs.edit().putInt("count_$pkg", getUsageCount(pkg) + 1).apply()

        val safeUri = resolveSafeUriForLaunch(rawUriString)
        val effectiveMime = detectMimeType(fileName, mimeType)

        try {
            context.grantUriPermission(
                pkg,
                safeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Throwable) {
        }

        try {
            val pkgIntent = Intent(if (forShare) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
                setPackage(pkg)
                if (forShare) {
                    type = effectiveMime
                    putExtra(Intent.EXTRA_STREAM, safeUri)
                    clipData = ClipData.newRawUri("shared_file", safeUri)
                } else {
                    setDataAndType(safeUri, effectiveMime)
                    clipData = ClipData.newRawUri("opened_file", safeUri)
                }
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            }
            context.startActivity(pkgIntent)
            return
        } catch (_: Throwable) {
        }

        try {
            val wildcardPkgIntent = Intent(if (forShare) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
                setPackage(pkg)
                if (forShare) {
                    type = "*/*"
                    putExtra(Intent.EXTRA_STREAM, safeUri)
                } else {
                    setDataAndType(safeUri, "*/*")
                }
                clipData = ClipData.newRawUri("file", safeUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(wildcardPkgIntent)
            return
        } catch (_: Throwable) {
        }

        try {
            val compIntent = Intent(if (forShare) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
                component = ComponentName(pkg, target.activityName)
                if (forShare) {
                    type = "*/*"
                    putExtra(Intent.EXTRA_STREAM, safeUri)
                } else {
                    setDataAndType(safeUri, "*/*")
                }
                clipData = ClipData.newRawUri("file", safeUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(compIntent)
            return
        } catch (_: Throwable) {
        }

        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                return
            }
        } catch (_: Throwable) {
            Toast.makeText(context, "无法启动 ${target.label}", Toast.LENGTH_SHORT).show()
        }
    }

    fun isPinned(packageName: String): Boolean =
        prefs.getBoolean("pin_$packageName", false)

    fun getUsageCount(packageName: String): Int =
        prefs.getInt("count_$packageName", 0)

    private fun queryIntentList(pm: PackageManager, intent: Intent): List<ResolveInfo> {
        val flags = PackageManager.MATCH_ALL
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, flags)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun drawableToScaledImageBitmap(drawable: Drawable?): ImageBitmap? {
        if (drawable == null) return null
        return try {
            val targetPx = 68
            if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
                val src = drawable.bitmap
                val safeSrc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && src.config == Bitmap.Config.HARDWARE) {
                    src.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                } else {
                    src
                }
                val scaled = if (safeSrc.width <= targetPx && safeSrc.height <= targetPx) {
                    safeSrc
                } else {
                    Bitmap.createScaledBitmap(safeSrc, targetPx, targetPx, true)
                }
                scaled.prepareToDraw()
                return scaled.asImageBitmap()
            }
            val bitmap = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, targetPx, targetPx)
            drawable.draw(canvas)
            bitmap.prepareToDraw()
            bitmap.asImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }
}
