package com.tat.quickreels.downloader

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.system.Os
import android.database.Cursor
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.widget.doAfterTextChanged
import com.tat.quickreels.databinding.ActivityDownloaderBinding
import com.tat.quickreels.R
import com.chaquo.python.Python
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DownloaderPanel(context: Context, private val host: AppCompatActivity, private val onVideoDownloaded: (Uri) -> Unit) : FrameLayout(context) {

    private lateinit var binding: ActivityDownloaderBinding

    private val executor = Executors.newFixedThreadPool(3)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var initialized = false
    @Volatile private var downloading = false
    @Volatile private var loadingInfo = false
    @Volatile private var videoReady = false
    @Volatile private var ffmpegDirectory: String? = null

    private var currentUrl = ""
    @Volatile private var resolvedVideoUrl = ""
    private var infoRunnable: Runnable? = null

    private val formatOptions = mutableListOf<FormatOption>()
    private val mainFormats = mutableListOf<String>()

    data class FormatOption(
        val title: String,
        val selector: String,
        val isMp3: Boolean = false
    )

    init {
        binding = ActivityDownloaderBinding.inflate(LayoutInflater.from(context), this, true)
        setupSpinner()
        setupPasteButton()
        setupUrlWatcher()
        setupDownloadButton()
        binding.historyButton.setOnClickListener {
            host.startActivity(Intent(host, HistoryActivity::class.java))
        }
        handleSharedUrl(host.intent)
        binding.downloadButton.isEnabled = false
        binding.downloadProgress.visibility = View.GONE
        initializeDownloader()
    }

    // =========================================
    // FFMPEG / FFPROBE EXECUTABLES
    // =========================================

    /**
     * The bundled binaries are packaged as Android native libraries so the
     * package installer extracts them to nativeLibraryDir with executable
     * permissions. Do not copy them to filesDir: app data filesystems can be
     * mounted noexec on Android, which causes errno=13 (EACCES).
     */
    private fun setupFFmpeg(): String {
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        check(supportedAbis.contains("arm64-v8a")) {
            "This build requires an ARM64 device. Supported ABIs: ${supportedAbis.joinToString()}"
        }

        val nativeDir = File(host.applicationInfo.nativeLibraryDir)
        check(nativeDir.isDirectory) {
            "Android native library directory is missing: ${nativeDir.absolutePath}"
        }

        val nativeFfmpeg = File(nativeDir, "libffmpeg.so")
        val nativeFfprobe = File(nativeDir, "libffprobe.so")
        check(nativeFfmpeg.isFile && nativeFfmpeg.canRead() && nativeFfmpeg.canExecute()) {
            "Bundled FFmpeg executable is missing or not executable in ${nativeDir.absolutePath}"
        }
        check(nativeFfprobe.isFile && nativeFfprobe.canRead() && nativeFfprobe.canExecute()) {
            "Bundled FFprobe executable is missing or not executable in ${nativeDir.absolutePath}"
        }

        // yt-dlp expects the conventional names `ffmpeg` and `ffprobe`.
        // Create symlinks in private storage that resolve to the executable
        // files on Android's executable native-library mount. Do not copy the
        // binaries into filesDir: that filesystem may be mounted noexec.
        val commandDir = File(host.filesDir, "ffmpeg-bin")
        if (!commandDir.exists()) {
            check(commandDir.mkdirs()) { "Cannot create FFmpeg command directory" }
        }
        val ffmpegLink = File(commandDir, "ffmpeg")
        val ffprobeLink = File(commandDir, "ffprobe")
        ffmpegLink.delete()
        ffprobeLink.delete()
        Os.symlink(nativeFfmpeg.absolutePath, ffmpegLink.absolutePath)
        Os.symlink(nativeFfprobe.absolutePath, ffprobeLink.absolutePath)

        check(ffmpegLink.canExecute() && ffprobeLink.canExecute()) {
            "FFmpeg executable aliases could not be prepared"
        }
        return commandDir.absolutePath
    }

    private fun verifyBinary(file: File, expectedName: String): String {
        val process = ProcessBuilder(file.absolutePath, "-version")
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(20, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw Exception("$expectedName version check timed out")
        }

        val exitCode = process.exitValue()
        check(exitCode == 0 && output.contains(expectedName, ignoreCase = true)) {
            "$expectedName could not start (exit=$exitCode): ${output.take(500)}"
        }
        return output.lineSequence().firstOrNull().orEmpty()
    }

    // =========================================
    // INITIALIZATION
    // =========================================

    private fun initializeDownloader() {

        binding.statusText.text = "Initializing downloader..."

        executor.execute {

            try {

                YtDlp.init(context.applicationContext)

                val ffmpegPath = setupFFmpeg()
                ffmpegDirectory = ffmpegPath

                val ffmpeg = File(ffmpegPath, "ffmpeg")
                val ffprobe = File(ffmpegPath, "ffprobe")

                val ffmpegVersion = verifyBinary(ffmpeg, "ffmpeg")
                val ffprobeVersion = verifyBinary(ffprobe, "ffprobe")

                // Add FFmpeg directory to Python PATH.
                val py = Python.getInstance()
                val os = py.getModule("os")

                val environ = os.get("environ")
                    ?: throw Exception(
                        "Python environment not found"
                    )

                val oldPath = environ.callAttr(
                    "get",
                    "PATH",
                    ""
                ).toString()

                val newPath = ffmpegPath +
                    File.pathSeparator +
                    oldPath

                environ.callAttr(
                    "__setitem__",
                    "PATH",
                    newPath
                )

                host.runOnUiThread {

                    initialized = true

                    binding.statusText.text =
                        "Ready | ${ffmpegVersion.take(28)} | ${ffprobeVersion.take(28)}"

                    updateDownloadButton()

                    // IMPORTANT:
                    // Fetch URL if user entered it before initialization.
                    val url = currentUrl

                    if (
                        isValidUrl(url) &&
                        !loadingInfo &&
                        !downloading
                    ) {
                        fetchVideoInfo(url)
                    }
                }

            } catch (e: Exception) {

                host.runOnUiThread {

                    initialized = false

                    binding.statusText.text =
                        "Initialization failed: ${errorMessage(e)}"

                    updateDownloadButton()
                }
            }
        }
    }

    private fun handleSharedUrl(source: Intent?) {
        if (source?.action == Intent.ACTION_SEND && source.type == "text/plain") {
            val shared = source.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (shared.isNotBlank()) binding.urlInput.setText(shared)
        }
    }

    fun handleIncomingIntent(intent: Intent?) {
        handleSharedUrl(intent)
    }

    // =========================================
    // SPINNER
    // =========================================

    private fun setupSpinner() {

        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            mainFormats
        )

        adapter.setDropDownViewResource(
        android.R.layout.simple_spinner_dropdown_item
        )
        adapter.setDropDownViewResource(R.layout.spinner_item)
        binding.formatSpinner.adapter = adapter
        }

    private fun updateFormats(options: List<FormatOption>) {

        formatOptions.clear()
        formatOptions.addAll(options)

        mainFormats.clear()
        mainFormats.addAll(options.map { it.title })

        (binding.formatSpinner.adapter as? ArrayAdapter<*>)
            ?.notifyDataSetChanged()

        if (options.isNotEmpty()) {
            binding.formatSpinner.setSelection(0)
        }
    }

    // =========================================
    // PASTE BUTTON
    // =========================================

    private fun setupPasteButton() {

        binding.pasteButton.setOnClickListener {

            val clipboard = host.getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

            if (clipboard.hasPrimaryClip()) {

                val clip: ClipData? = clipboard.primaryClip

                val text = clip?.getItemAt(0)
                    ?.coerceToText(context)

                if (!text.isNullOrBlank()) {

                    binding.urlInput.setText(
                        text.toString().trim()
                    )

                    binding.urlInput.setSelection(
                        binding.urlInput.text.length
                    )

                } else {
                    toast("Clipboard is empty")
                }

            } else {
                toast("Clipboard is empty")
            }
        }
    }

    // =========================================
    // URL WATCHER
    // =========================================

    private fun setupUrlWatcher() {

        binding.urlInput.doAfterTextChanged { editable ->

            val url = editable?.toString()
                ?.trim()
                .orEmpty()

            infoRunnable?.let {
                handler.removeCallbacks(it)
            }

            if (url == currentUrl) {
                return@doAfterTextChanged
            }

            currentUrl = url
            resolvedVideoUrl = ""
            videoReady = false

            binding.videoInfo.visibility = View.GONE
            binding.videoThumbnail.setImageDrawable(null)

            updateFormats(emptyList())
            updateDownloadButton()

            if (url.isBlank()) {

                binding.statusText.text = "Ready"

                return@doAfterTextChanged
            }

            if (!isValidUrl(url)) {

                binding.statusText.text =
                    "Waiting for valid URL..."

                return@doAfterTextChanged
            }

            if (!initialized) {

                binding.statusText.text =
                    "Waiting for downloader initialization..."

                return@doAfterTextChanged
            }

            binding.statusText.text = "Waiting..."

            val task = Runnable {

                if (url == currentUrl) {
                    fetchVideoInfo(url)
                }
            }

            infoRunnable = task

            handler.postDelayed(task, 1000)
        }
    }

    private fun isValidUrl(url: String): Boolean {

        return try {

            val uri = Uri.parse(url)

            (uri.scheme == "https" || uri.scheme == "http") &&
                !uri.host.isNullOrBlank()

        } catch (e: Exception) {
            false
        }
    }

    // =========================================
    // DOWNLOAD BUTTON STATE
    // =========================================

    private fun updateDownloadButton() {

        binding.downloadButton.isEnabled =
            initialized &&
            videoReady &&
            !loadingInfo &&
            !downloading
    }

    // =========================================
    // VIDEO INFORMATION
    // =========================================

    private fun fetchVideoInfo(url: String) {

        if (url != currentUrl) return

        if (!initialized) {

            binding.statusText.text =
                "Waiting for downloader initialization..."

            return
        }

        if (loadingInfo || downloading) return

        loadingInfo = true
        updateDownloadButton()

        binding.statusText.text =
            "Getting video information..."

        executor.execute {

            try {

                val py = Python.getInstance()
                val ytDlp = py.getModule("yt_dlp")

                // Create Python dictionary.
                val options = py.getBuiltins()
                    .callAttr("dict")

                options.callAttr("__setitem__", "quiet", true)
                options.callAttr("__setitem__", "no_warnings", true)
                options.callAttr("__setitem__", "skip_download", true)

                val ydl = ytDlp.callAttr(
                    "YoutubeDL",
                    options
                )

                val info = ydl.callAttr(
                    "extract_info",
                    url,
                    false
                )

                val json = py.getModule("json")
                    .callAttr("dumps", info)
                    .toJava(String::class.java)

                val video = JSONObject(json)

                // Prefer yt-dlp's resolved single-video URL when downloading.
                // Facebook share links (for example /share/r/...) can redirect
                // differently on the second request; the canonical webpage URL
                // avoids repeating that redirect whenever the extractor provides it.
                val canonicalUrl = video.optString("webpage_url", "").trim()
                val safeCanonicalUrl = if (
                    canonicalUrl.startsWith("https://", ignoreCase = true) ||
                    canonicalUrl.startsWith("http://", ignoreCase = true)
                ) canonicalUrl else ""

                // Reject playlist-only URLs.
                val type = video.optString(
                    "_type",
                    "video"
                )

                val entries = video.optJSONArray("entries")

                val isPlaylist =
                    type == "playlist" ||
                    type == "multi_video" ||
                    (entries != null && entries.length() > 1)

                if (isPlaylist) {

                    throw IllegalArgumentException(
                        "Playlist URL is not supported. Use a single video URL."
                    )
                }

                val title = video.optString(
                    "title",
                    "Unknown title"
                )

                val duration = video.optDouble(
                    "duration",
                    0.0
                ).toInt()

                val uploader = video.optString(
                    "uploader",
                    "Unknown uploader"
                )

                val thumbnail = video.optString(
                    "thumbnail",
                    ""
                )

                val formats = parseFormats(video)

                host.runOnUiThread {

                    if (url != currentUrl || host.isFinishing) {

                        finishInfoLoading(url)

                        return@runOnUiThread
                    }

                    resolvedVideoUrl = safeCanonicalUrl
                    binding.videoInfo.visibility = View.VISIBLE

                    binding.videoTitle.text = title

                    binding.videoDuration.text =
                        "Duration: ${formatDuration(duration)}"

                    binding.videoUploader.text =
                        "Uploader: $uploader"

                    updateFormats(formats)

                    videoReady = formats.isNotEmpty()

                    binding.statusText.text =
                        if (videoReady) {
                            "Video information loaded"
                        } else {
                            "No downloadable format found"
                        }

                    finishInfoLoading(url)
                }

                if (thumbnail.isNotBlank()) {
                    loadThumbnail(thumbnail, url)
                }

            } catch (e: Exception) {

                host.runOnUiThread {

                    if (url == currentUrl) {

                        videoReady = false

                        binding.videoInfo.visibility =
                            View.GONE

                        updateFormats(emptyList())

                        binding.statusText.text =
                            "Info error: ${errorMessage(e)}"
                    }

                    finishInfoLoading(url)
                }
            }
        }
    }

    private fun finishInfoLoading(url: String) {

        loadingInfo = false

        updateDownloadButton()

        // Fetch the latest URL if it changed during previous request.
        if (
            url != currentUrl &&
            isValidUrl(currentUrl) &&
            initialized &&
            !downloading
        ) {

            val latestUrl = currentUrl

            handler.postDelayed({

                if (
                    latestUrl == currentUrl &&
                    !loadingInfo &&
                    !downloading
                ) {
                    fetchVideoInfo(latestUrl)
                }

            }, 300)
        }
    }

    // =========================================
    // FORMAT PARSING
    // =========================================


    private fun parseFormats(video: JSONObject): List<FormatOption> {
        val result = mutableListOf<FormatOption>()
        val formats = video.optJSONArray("formats") ?: return result
        val available = mutableListOf<JSONObject>()
        var hasAudioSource = false
    
        for (i in 0 until formats.length()) {
            val item = formats.optJSONObject(i) ?: continue
            val id = item.optString("format_id", "")
            if (id.isBlank()) continue
    
            val vcodec = item.optString("vcodec", "none")
            val acodec = item.optString("acodec", "none")
    
            if (acodec != "none") hasAudioSource = true
            if (vcodec != "none") available.add(item)
        }
    
        val sorted = available.sortedWith(
            compareByDescending<JSONObject> { it.optInt("height", 0) }
                .thenByDescending { it.optDouble("tbr", 0.0) }
        )
    
        val seen = mutableSetOf<String>()
    
        for (item in sorted) {
            val id = item.optString("format_id", "")
            val ext = item.optString("ext", "unknown")
            val height = item.optInt("height", 0)
            val vcodec = item.optString("vcodec", "none")
            val acodec = item.optString("acodec", "none")
            val size = item.optLong(
                "filesize",
                item.optLong("filesize_approx", 0L)
            )
    
            val resolution = if (height > 0) "${height}p" else "Video"
            val codec = vcodec.substringBefore(".")
            val hasAudio = acodec != "none"
            val selector = if (!hasAudio) "$id+bestaudio/best" else id
            val label = "$resolution | $ext | $codec | ${formatSize(size)}"
    
            if (seen.add(selector)) {
                result.add(
                    FormatOption(
                        title = label,
                        selector = selector
                    )
                )
            }
        }
    
        if (hasAudioSource) {
            result.add(
                FormatOption(
                    title = "MP3 | Audio Only | 192 kbps",
                    selector = "bestaudio/best",
                    isMp3 = true
                )
            )
        }
    
        return result
    }
    

    // =========================================
    // THUMBNAIL
    // =========================================

    private fun loadThumbnail(
        imageUrl: String,
        url: String
    ) {

        executor.execute {

            var connection: HttpURLConnection? = null

            try {

                connection = URL(imageUrl).openConnection()
                    as HttpURLConnection

                connection.connectTimeout = 10000
                connection.readTimeout = 15000

                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0"
                )

                connection.connect()

                val bitmap = connection.inputStream.use {
                    BitmapFactory.decodeStream(it)
                }

                host.runOnUiThread {

                    if (
                        url == currentUrl &&
                        bitmap != null &&
                        !host.isFinishing
                    ) {
                        binding.videoThumbnail.setImageBitmap(bitmap)
                    }
                }

            } catch (e: Exception) {

                // Thumbnail failure does not stop downloading.

            } finally {
                connection?.disconnect()
            }
        }
    }

    // =========================================
    // DOWNLOAD BUTTON
    // =========================================

    private fun setupDownloadButton() {

        binding.downloadButton.setOnClickListener {

            val url = binding.urlInput.text
                .toString()
                .trim()

            if (!isValidUrl(url)) {

                toast("Enter a valid URL")

                return@setOnClickListener
            }

            if (!initialized) {

                toast("Downloader is not ready")

                return@setOnClickListener
            }

            if (loadingInfo || downloading) {

                toast("Please wait")

                return@setOnClickListener
            }

            if (!videoReady || url != currentUrl) {

                toast("Load video information first")

                return@setOnClickListener
            }

            val position =
                binding.formatSpinner.selectedItemPosition

            if (position !in formatOptions.indices) {

                toast("Select a format")

                return@setOnClickListener
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                host.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                host.requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    4102
                )
                toast("Allow storage access, then tap Download again")
                return@setOnClickListener
            }

            startDownload(
                url,
                formatOptions[position]
            )
        }
    }

    // =========================================
    // DOWNLOAD PROCESS
    // =========================================

    private fun startDownload(
        url: String,
        selected: FormatOption
    ) {

        if (downloading) return

        downloading = true

        // Keep videoReady true so another format can be downloaded later.
        updateDownloadButton()

        binding.downloadButton.isEnabled = false
        binding.pasteButton.isEnabled = false
        binding.urlInput.isEnabled = false
        binding.formatSpinner.isEnabled = false

        binding.downloadProgress.visibility = View.VISIBLE
        binding.downloadProgress.isIndeterminate = false
        binding.downloadProgress.progress = 0

        binding.statusText.text = "Starting download..."
        val requestedDisplayTitle = binding.videoTitle.text.toString()

        executor.execute {

            var jobFolder: File? = null

            try {

                val baseFolder = host.getExternalFilesDir("Downloads")
                    ?: File(host.filesDir, "Downloads")

                // Ensure the parent and unique job folder both exist.
                if (!baseFolder.exists() && !baseFolder.mkdirs()) {
                    throw Exception("Cannot create temporary Downloads directory: ${baseFolder.absolutePath}")
                }

                jobFolder = File(
                    baseFolder,
                    "job_${System.currentTimeMillis()}"
                )

                if (!jobFolder.mkdirs()) {
                    throw Exception("Cannot create download job directory: ${jobFolder.absolutePath}")
                }

                // Never use the remote video title as a path component.
                // Titles can contain slashes, control characters, or path-like text.
                val output = File(jobFolder, "download.%(ext)s").absolutePath

                val ffmpegPath = ffmpegDirectory
                    ?: throw Exception("FFmpeg runtime is not initialized")
                // Try the resolved canonical URL first, then the original share URL.
                // Facebook share URLs can redirect to a different page variant on
                // subsequent requests; the canonical URL often avoids that issue.
                val downloadCandidates = linkedSetOf<String>().apply {
                    resolvedVideoUrl.takeIf { it.isNotBlank() }?.let { add(it) }
                    add(url)
                }

                var downloadSucceeded = false
                var lastDownloadError = "Unknown yt-dlp error"
                var lastOutputLine = ""
                val isYoutube = isYoutubeUrl(url)

                // First use the exact format selected by the user. If YouTube
                // rejects its direct stream with HTTP 403, retry once through
                // the web_safari HLS client at the same requested resolution.
                for (candidateUrl in downloadCandidates) {
                    val attemptCount = if (isYoutube && !selected.isMp3) 2 else 1
                    for (attemptIndex in 0 until attemptCount) {
                        lastOutputLine = ""
                        val request = YtDlpRequest(candidateUrl)
                            .setOutputTemplate(output)
                            .addOption("--no-playlist")
                            .addOption("--newline")
                            .addOption("--ffmpeg-location", ffmpegPath)
                            .addOption("--retries", "3")
                            .addOption("--fragment-retries", "3")
                            .addOption("--extractor-retries", "2")
                            .addOption("--socket-timeout", "30")

                        if (attemptIndex == 1) {
                            val requestedHeight = Regex("(\\d+)p")
                                .find(selected.title)?.groupValues?.getOrNull(1)
                            request.addOption(
                                "--extractor-args",
                                "youtube:player_client=web_safari"
                            )
                            // Keep the same resolution; do not silently switch
                            // the user to a lower-quality download.
                            request.addOption(
                                "-f",
                                if (requestedHeight != null) {
                                    "best[height=$requestedHeight]"
                                } else {
                                    selected.selector
                                }
                            )
                        } else if (selected.isMp3) {
                            request.addOption("-f", "bestaudio/best")
                        } else {
                            request.addOption("-f", selected.selector)
                        }

                        if (selected.isMp3) {
                            request.addOption("-x")
                            request.addOption("--audio-format", "mp3")
                            request.addOption("--audio-quality", "192K")
                        } else {
                            request.addOption("--merge-output-format", "mp4")
                            request.addOption("--recode-video", "mp4")
                            request.addOption("--embed-metadata")
                        }

                        val future = YtDlp.executeAsync(
                            request
                        ) { progress, eta, line ->
                            lastOutputLine = line
                            host.runOnUiThread {
                                if (host.isFinishing) return@runOnUiThread
                                if (progress >= 0) {
                                    val value = progress.toInt().coerceIn(0, 100)
                                    binding.downloadProgress.progress = value
                                    binding.statusText.text = "Downloading: $value% | ETA: ${eta}s"
                                } else {
                                    binding.statusText.text = line.take(150)
                                }
                            }
                        }

                        val attemptResponse = future.get()
                        if (attemptResponse.isSuccess) {
                            downloadSucceeded = true
                            break
                        }

                        lastDownloadError =
                            "yt-dlp exit code: ${attemptResponse.exitCode}. ${lastOutputLine.take(500)}"

                        val isHttp403 = lastDownloadError.contains("403", ignoreCase = true) ||
                            lastDownloadError.contains("Forbidden", ignoreCase = true)
                        if (!isHttp403) break
                    }
                    if (downloadSucceeded) break
                }

                if (!downloadSucceeded) {
                    if (lastDownloadError.contains("Facebook", ignoreCase = true) ||
                        lastDownloadError.contains("Cannot parse data", ignoreCase = true)
                    ) {
                        throw Exception(
                            "Facebook could not provide this format on the retry. " +
                            "The page may require a logged-in Facebook session or Facebook " +
                            "may have changed its video response. Try again later, use the " +
                            "original video URL, or provide an exported Facebook cookies file. " +
                            lastDownloadError
                        )
                    }
                    if (isYoutube && (
                        lastDownloadError.contains("403", ignoreCase = true) ||
                        lastDownloadError.contains("Forbidden", ignoreCase = true)
                    )) {
                        throw Exception(
                            "YouTube rejected the stream (HTTP 403) even after the HLS retry. " +
                            "This can require a current YouTube PO token or an authenticated cookies file. " +
                            "Try another available format or update the downloader library when a newer yt-dlp build is released. " +
                            lastDownloadError
                        )
                    }
                    throw Exception("Download failed: $lastDownloadError")
                }

                val downloaded = jobFolder.listFiles()
                    ?.filter { it.isFile }
                    ?.maxByOrNull { it.lastModified() }

                if (
                    downloaded == null ||
                    !downloaded.exists() ||
                    downloaded.length() <= 0
                ) {

                    throw Exception(
                        "Downloaded file was not found"
                    )
                }

                val preferredName = buildSafeMediaFileName(
                    requestedDisplayTitle,
                    downloaded.extension
                )
                val namedFile = File(jobFolder, preferredName)
                if (namedFile.absolutePath != downloaded.absolutePath) {
                    if (namedFile.exists()) namedFile.delete()
                    if (downloaded.renameTo(namedFile)) {
                        // Keep the friendly, sanitized title for Downloads and History.
                    } else {
                        downloaded.copyTo(namedFile, overwrite = true)
                        downloaded.delete()
                    }
                }
                val finalDownloaded = if (namedFile.isFile && namedFile.length() > 0) namedFile else downloaded

                val savedUri = saveToDownloads(finalDownloaded)

                if (savedUri == null) {

                    throw Exception(
                        "Download completed but saving to Downloads failed"
                    )
                }

                HistoryStore.add(
                    context,
                    title = finalDownloaded.name,
                    uri = savedUri.toString(),
                    sizeBytes = finalDownloaded.length(),
                    mediaType = if (selected.isMp3) "Audio" else "Video"
                )

                if (!selected.isMp3) {
                    host.runOnUiThread { onVideoDownloaded(savedUri) }
                }

                host.runOnUiThread {

                    binding.downloadProgress.progress = 100

                    binding.statusText.text =
                        "Download completed! Saved to Downloads"

                    toast("Download completed")

                    finishDownload()
                }

            } catch (e: Exception) {

                host.runOnUiThread {

                    binding.statusText.text =
                        "Download error: ${errorMessage(e)}"

                    toast("Download failed")

                    finishDownload()
                }

            } finally {

                // Remove temporary app-storage files after processing.
                jobFolder?.deleteRecursively()
            }
        }
    }

    private fun isYoutubeUrl(value: String): Boolean {
        val host = Uri.parse(value).host?.lowercase().orEmpty()
        return host == "youtu.be" ||
            host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" ||
            host.endsWith(".youtube-nocookie.com")
    }

    private fun buildSafeMediaFileName(title: String, extension: String): String {
        val cleaned = title
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', ' ')
            .take(100)
            .ifBlank { "TAT_Video" }
        val ext = extension.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "mp4" }
        return "$cleaned.$ext"
    }

    // =========================================
    // SAVE TO PUBLIC DOWNLOADS
    // =========================================

    private fun saveToDownloads(
        source: File
    ): Uri? {

        return try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                val values = ContentValues().apply {

                    put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        uniqueDisplayName(source.name)
                    )

                    put(
                        MediaStore.MediaColumns.MIME_TYPE,
                        getMimeType(source.extension)
                    )

                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS +
                            "/TAT Video Downloader"
                    )

                    put(
                        MediaStore.MediaColumns.IS_PENDING,
                        1
                    )
                }

                val resolver = host.contentResolver

                val uri = resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return null

                try {

                    resolver.openOutputStream(uri)?.use { output ->

                        source.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    } ?: throw Exception(
                        "Cannot open Downloads output"
                    )

                    val completed = ContentValues().apply {

                        put(
                            MediaStore.MediaColumns.IS_PENDING,
                            0
                        )
                    }

                    resolver.update(
                        uri,
                        completed,
                        null,
                        null
                    )

                    uri

                } catch (e: Exception) {

                    resolver.delete(uri, null, null)

                    throw e
                }

            } else {

                @Suppress("DEPRECATION")
                val downloads =
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    )

                val targetFolder = File(
                    downloads,
                    "TAT Video Downloader"
                )

                if (!targetFolder.exists()) {
                    targetFolder.mkdirs()
                }

                val target = File(targetFolder, uniqueLegacyName(targetFolder, source.name))
                source.copyTo(target, overwrite = false)

                Uri.fromFile(target)
            }

        } catch (e: Exception) {

            null
        }
    }

    private fun uniqueDisplayName(fileName: String): String {
        val resolver = host.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        var candidate = fileName
        var suffix = 1
        while (true) {
            var cursor: Cursor? = null
            val exists = try {
                cursor = resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(candidate),
                    null
                )
                cursor?.moveToFirst() == true
            } finally {
                cursor?.close()
            }
            if (!exists) return candidate
            val dot = fileName.lastIndexOf('.')
            candidate = if (dot > 0) {
                "${fileName.substring(0, dot)} ($suffix)${fileName.substring(dot)}"
            } else "$fileName ($suffix)"
            suffix++
        }
    }

    private fun uniqueLegacyName(folder: File, fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var candidate = fileName
        var suffix = 1
        while (File(folder, candidate).exists()) {
            candidate = "$stem ($suffix)$ext"
            suffix++
        }
        return candidate
    }

    private fun getMimeType(
        extension: String
    ): String {

        return when (extension.lowercase()) {

            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "m4a" -> "audio/mp4"

            else -> "application/octet-stream"
        }
    }

    // =========================================
    // FINISH DOWNLOAD
    // =========================================

    private fun finishDownload() {

        downloading = false

        binding.pasteButton.isEnabled = true
        binding.urlInput.isEnabled = true
        binding.formatSpinner.isEnabled = true

        binding.downloadProgress.visibility = View.GONE

        updateDownloadButton()
    }

    // =========================================
    // HELPERS
    // =========================================

    private fun formatDuration(
        seconds: Int
    ): String {

        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return if (hours > 0) {

            String.format(
                "%02d:%02d:%02d",
                hours,
                minutes,
                secs
            )

        } else {

            String.format(
                "%02d:%02d",
                minutes,
                secs
            )
        }
    }

    private fun formatSize(
        bytes: Long
    ): String {

        if (bytes <= 0) return "Unknown size"

        val mb = bytes / (1024.0 * 1024.0)

        return if (mb >= 1024) {

            String.format(
                "%.2f GB",
                mb / 1024
            )

        } else {

            String.format(
                "%.1f MB",
                mb
            )
        }
    }

    private fun errorMessage(
        e: Throwable
    ): String {

        val root = generateSequence(e) { it.cause }.last()
        val message = root.message ?: e.message ?: root.javaClass.simpleName
        return (if (message.contains("Permission denied", ignoreCase = true) || message.contains("EACCES", ignoreCase = true)) {
            "Executable permission denied (EACCES). Check nativeLibraryDir packaging and device ABI. $message"
        } else {
            "${root.javaClass.simpleName}: $message"
        }).take(350)
    }

    private fun toast(
        message: String
    ) {

        Toast.makeText(
            context,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }

    // =========================================
    // DESTROY
    // =========================================

    fun dispose() {

        infoRunnable?.let {
            handler.removeCallbacks(it)
        }

        executor.shutdownNow()

    }
}
