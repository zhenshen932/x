package com.omnimedia.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.widget.RemoteViews
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AudioPlayerController {
    var currentFile by mutableStateOf<RecentFileEntry?>(null)
    var isPlaying by mutableStateOf(false)
    var currentPositionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var playbackSpeed by mutableFloatStateOf(1.0f)
    var trackTitle by mutableStateOf("")
    var trackArtist by mutableStateOf("")
    var coverBitmap by mutableStateOf<Bitmap?>(null)
    var errorMessage by mutableStateOf<String?>(null)

    internal var serviceInstance: AudioPlaybackService? = null

    fun playFile(context: Context, file: RecentFileEntry) {
        val sameTrack = currentFile?.uriString == file.uriString && serviceInstance != null
        currentFile = file
        if (sameTrack) {
            serviceInstance?.ensurePlaying()
            return
        }
        val intent = Intent(context, AudioPlaybackService::class.java).apply {
            action = AudioPlaybackService.ACTION_PLAY_NEW
            putExtra(AudioPlaybackService.EXTRA_URI, file.uriString)
            putExtra(AudioPlaybackService.EXTRA_NAME, file.name)
            putExtra(AudioPlaybackService.EXTRA_MIME, file.mimeType)
            putExtra(AudioPlaybackService.EXTRA_SIZE, file.sizeBytes)
            putExtra(AudioPlaybackService.EXTRA_SIZE_LABEL, file.sizeLabel)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Throwable) {
            try {
                context.startService(intent)
            } catch (_: Throwable) {
            }
        }
    }

    fun togglePlayPause(context: Context) {
        val svc = serviceInstance
        if (svc != null) {
            svc.togglePlayPauseInternal()
        } else {
            currentFile?.let { playFile(context, it) }
        }
    }

    fun seekTo(posMs: Long) {
        serviceInstance?.seekToInternal(posMs)
    }

    fun seekBy(deltaMs: Long) {
        serviceInstance?.seekByInternal(deltaMs)
    }

    fun cycleSpeed() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
        val nextIdx = (speeds.indexOf(playbackSpeed) + 1) % speeds.size
        val next = speeds[nextIdx]
        playbackSpeed = next
        serviceInstance?.setSpeedInternal(next)
    }

    fun skipPrev(context: Context) {
        serviceInstance?.skipToAdjacentAudio(-1) ?: currentFile?.let { playFile(context, it) }
    }

    fun skipNext(context: Context) {
        serviceInstance?.skipToAdjacentAudio(1) ?: currentFile?.let { playFile(context, it) }
    }
}

class AudioPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "omnimedia_audio_playback_channel"
        const val NOTIFICATION_ID = 2026
        const val ACTION_PLAY_NEW = "com.omnimedia.hub.action.PLAY_NEW"
        const val ACTION_TOGGLE_PLAY = "com.omnimedia.hub.action.TOGGLE_PLAY"
        const val ACTION_PREV = "com.omnimedia.hub.action.PREV"
        const val ACTION_NEXT = "com.omnimedia.hub.action.NEXT"

        const val EXTRA_URI = "extra_uri"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_MIME = "extra_mime"
        const val EXTRA_SIZE = "extra_size"
        const val EXTRA_SIZE_LABEL = "extra_size_label"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        AudioPlayerController.serviceInstance = this
        createNotificationChannel()
        initExoPlayer()
        startProgressPoller()
    }

    private fun initExoPlayer() {
        if (exoPlayer != null) return
        val audioAttrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        exoPlayer = ExoPlayer.Builder(applicationContext)
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        AudioPlayerController.isPlaying = isPlaying
                        updateNotification()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            AudioPlayerController.durationMs = duration.coerceAtLeast(0L)
                        } else if (playbackState == Player.STATE_ENDED) {
                            AudioPlayerController.isPlaying = false
                            updateNotification()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        AudioPlayerController.errorMessage = error.localizedMessage ?: "Playback error"
                        updateNotification()
                    }
                })
            }
    }

    private fun startProgressPoller() {
        serviceScope.launch {
            while (true) {
                val p = exoPlayer
                if (p != null) {
                    AudioPlayerController.currentPositionMs = p.currentPosition.coerceAtLeast(0L)
                    val d = p.duration
                    if (d > 0) AudioPlayerController.durationMs = d
                }
                delay(200L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        initExoPlayer()
        when (intent?.action) {
            ACTION_PLAY_NEW -> {
                val uriStr = intent.getStringExtra(EXTRA_URI) ?: return START_STICKY
                val name = intent.getStringExtra(EXTRA_NAME) ?: "Audio"
                val mime = intent.getStringExtra(EXTRA_MIME) ?: "audio/*"
                val sizeBytes = intent.getLongExtra(EXTRA_SIZE, 0L)
                val sizeLabel = intent.getStringExtra(EXTRA_SIZE_LABEL) ?: ""
                val entry = RecentFileEntry(
                    uriString = uriStr,
                    name = name,
                    mimeType = mime,
                    sizeBytes = sizeBytes,
                    sizeLabel = sizeLabel,
                    pathOrSource = uriStr,
                    openedTimestamp = System.currentTimeMillis()
                )
                loadAndPlayEntry(entry)
            }
            ACTION_TOGGLE_PLAY -> {
                togglePlayPauseInternal()
            }
            ACTION_PREV -> {
                skipToAdjacentAudio(-1)
            }
            ACTION_NEXT -> {
                skipToAdjacentAudio(1)
            }
        }
        return START_STICKY
    }

    private fun loadAndPlayEntry(entry: RecentFileEntry) {
        AudioPlayerController.currentFile = entry
        AudioPlayerController.errorMessage = null

        val rawTitle = entry.name.substringBeforeLast('.')
        val parsedTitle = if (rawTitle.contains(" - ")) rawTitle.substringAfter(" - ").trim() else rawTitle
        val parsedArtist = if (rawTitle.contains(" - ")) rawTitle.substringBefore(" - ").trim() else "${entry.extension} • ${entry.sizeLabel}"

        AudioPlayerController.trackTitle = parsedTitle.ifBlank { entry.name }
        AudioPlayerController.trackArtist = parsedArtist
        AudioPlayerController.coverBitmap = buildFallbackCoverBitmap()

        // Start foreground immediately with initial metadata
        startForegroundSafely(buildCustomNotification())

        try {
            exoPlayer?.apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(entry.uriString)))
                playbackParameters = PlaybackParameters(AudioPlayerController.playbackSpeed)
                prepare()
                playWhenReady = true
            }
        } catch (e: Throwable) {
            AudioPlayerController.errorMessage = e.localizedMessage
        }

        // Extract real ID3/FLAC embedded title, artist, and album cover asynchronously
        serviceScope.launch {
            val extracted = withContext(Dispatchers.IO) {
                extractAudioMetadata(entry.uriString)
            }
            if (extracted.first != null) {
                AudioPlayerController.trackTitle = extracted.first!!
            }
            if (extracted.second != null) {
                AudioPlayerController.trackArtist = extracted.second!!
            }
            if (extracted.third != null) {
                AudioPlayerController.coverBitmap = extracted.third!!
            }
            updateNotification()
        }
    }

    internal fun ensurePlaying() {
        val p = exoPlayer ?: return
        if (!p.isPlaying) {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0L)
            p.play()
        }
        updateNotification()
    }

    internal fun togglePlayPauseInternal() {
        val p = exoPlayer ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0L)
            p.play()
        }
        updateNotification()
    }

    internal fun seekToInternal(posMs: Long) {
        val p = exoPlayer ?: return
        p.seekTo(posMs.coerceAtLeast(0L))
        AudioPlayerController.currentPositionMs = p.currentPosition.coerceAtLeast(0L)
    }

    internal fun seekByInternal(deltaMs: Long) {
        val p = exoPlayer ?: return
        val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (p.currentPosition + deltaMs).coerceIn(0L, dur)
        p.seekTo(target)
        AudioPlayerController.currentPositionMs = target
    }

    internal fun setSpeedInternal(speed: Float) {
        exoPlayer?.playbackParameters = PlaybackParameters(speed)
    }

    internal fun skipToAdjacentAudio(direction: Int) {
        val router = AppRouterManager(applicationContext)
        val audioList = router.loadRecentFiles().filter { it.isAudio }
        val curUri = AudioPlayerController.currentFile?.uriString
        if (audioList.size <= 1 || curUri == null) {
            if (direction < 0) seekToInternal(0L) else seekByInternal(10_000L)
            return
        }
        val idx = audioList.indexOfFirst { it.uriString == curUri }
        val nextIdx = if (idx < 0) 0 else (idx + direction + audioList.size) % audioList.size
        loadAndPlayEntry(audioList[nextIdx])
    }

    private fun extractAudioMetadata(uriString: String): Triple<String?, String?, Bitmap?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(applicationContext, Uri.parse(uriString))
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
            val picBytes = retriever.embeddedPicture
            val coverBmp = if (picBytes != null && picBytes.isNotEmpty()) {
                val raw = BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size)
                if (raw != null) makeRoundedCoverBitmap(raw, 168, 28f) else null
            } else {
                null
            }
            Triple(title, artist, coverBmp)
        } catch (_: Throwable) {
            Triple(null, null, null)
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun makeRoundedCoverBitmap(src: Bitmap, sizePx: Int, cornerRadiusPx: Float): Bitmap {
        val scaled = Bitmap.createScaledBitmap(src, sizePx, sizePx, true)
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, sizePx, sizePx)
        val rectF = RectF(rect)
        canvas.drawRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, rect, rect, paint)
        return output
    }

    private fun buildFallbackCoverBitmap(): Bitmap {
        val sizePx = 168
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, sizePx.toFloat(), sizePx.toFloat(),
                intArrayOf(0xFF2B2D42.toInt(), 0xFF1A1B26.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
        }
        val rectF = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        canvas.drawRoundRect(rectF, 28f, 28f, paint)

        // Draw crisp waveform bars in the center
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF818CF8.toInt()
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
        }
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val heights = floatArrayOf(24f, 48f, 70f, 44f, 26f)
        val offsets = floatArrayOf(-36f, -18f, 0f, 18f, 36f)
        for (i in heights.indices) {
            val x = cx + offsets[i]
            val half = heights[i] / 2f
            canvas.drawLine(x, cy - half, x, cy + half, barPaint)
        }
        return output
    }

    private fun buildCustomNotification(): Notification {
        val remoteViews = RemoteViews(packageName, R.layout.notification_media_player)
        val title = AudioPlayerController.trackTitle.ifBlank { AudioPlayerController.currentFile?.name ?: "OmniMedia Hub" }
        val subtitle = AudioPlayerController.trackArtist.ifBlank { "OmniMedia Audio" }
        val cover = AudioPlayerController.coverBitmap ?: buildFallbackCoverBitmap()

        remoteViews.setTextViewText(R.id.notif_title, title)
        remoteViews.setTextViewText(R.id.notif_subtitle, subtitle)
        remoteViews.setImageViewBitmap(R.id.notif_cover, cover)
        remoteViews.setImageViewResource(
            R.id.btn_notif_play_pause,
            if (AudioPlayerController.isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play
        )

        val prevPending = PendingIntent.getService(
            this,
            101,
            Intent(this, AudioPlaybackService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val togglePending = PendingIntent.getService(
            this,
            102,
            Intent(this, AudioPlaybackService::class.java).apply { action = ACTION_TOGGLE_PLAY },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextPending = PendingIntent.getService(
            this,
            103,
            Intent(this, AudioPlaybackService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openAppPending = PendingIntent.getActivity(
            this,
            104,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        remoteViews.setOnClickPendingIntent(R.id.btn_notif_prev, prevPending)
        remoteViews.setOnClickPendingIntent(R.id.btn_notif_play_pause, togglePending)
        remoteViews.setOnClickPendingIntent(R.id.btn_notif_next, nextPending)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(openAppPending)
            .setCustomContentView(remoteViews)
            .setCustomBigContentView(remoteViews)
            .setOngoing(AudioPlayerController.isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startForegroundSafely(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {
        }
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildCustomNotification())
        } catch (_: Throwable) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音频后台播放控制",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于后台播放音频并在通知栏提供播放控制"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AudioPlayerController.serviceInstance = null
        try {
            exoPlayer?.release()
        } catch (_: Throwable) {
        }
        exoPlayer = null
        super.onDestroy()
    }
}
