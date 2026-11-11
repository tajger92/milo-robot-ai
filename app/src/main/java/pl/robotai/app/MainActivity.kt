package pl.robotai.app

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.WindowManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.UnknownHostException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.Locale

private val Ink = Color(0xFF18333A)
private val Teal = Color(0xFF2EAAA0)
private val Cream = Color(0xFFF5F3EC)
private val SoftMint = Color(0xFFE2F2ED)

data class ChatMessage(val role: String, val text: String)

private class MiloVoiceController(
    context: Context,
    private val onTranscript: (String) -> Unit,
    private val onListeningChanged: (Boolean) -> Unit,
    private val onSpeakingChanged: (Boolean) -> Unit,
    private val onVoiceReady: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitializationFinished = false
    private var liveConversation = false
    private var pendingSpeech: String? = null
    private var utteranceNumber = 0

    private val retryListening = Runnable { startListening() }

    init {
        if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { onListeningChanged(true) }
                    override fun onBeginningOfSpeech() { onListeningChanged(true) }
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() { onListeningChanged(false) }
                    override fun onError(error: Int) {
                        onListeningChanged(false)
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            liveConversation = false
                            onError("Android nie udostępnił mikrofonu Milo.")
                        } else if (liveConversation) {
                            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                                onError("Nasłuchiwanie zostało przerwane. Milo spróbuje ponownie.")
                            }
                            scheduleListeningRetry(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1_000L else 650L)
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        onListeningChanged(false)
                        val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                        if (liveConversation && !words.isNullOrBlank()) onTranscript(words)
                        else if (liveConversation) scheduleListeningRetry(350L)
                    }
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }
        }

        tts = TextToSpeech(appContext) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                ttsInitializationFinished = true
                mainHandler.post { onVoiceReady(false) }
                return@TextToSpeech
            }
            val languageStatus = engine.setLanguage(Locale("pl", "PL"))
            val polishAvailable = languageStatus != TextToSpeech.LANG_MISSING_DATA &&
                languageStatus != TextToSpeech.LANG_NOT_SUPPORTED
            engine.voices
                ?.filter { it.locale.language == "pl" }
                ?.maxWithOrNull(compareBy<Voice> { it.quality }
                    .thenBy { if (it.isNetworkConnectionRequired) 1 else 0 })
                ?.let(engine::setVoice)
            engine.setPitch(.98f)
            engine.setSpeechRate(.98f)
            ttsReady = polishAvailable
            ttsInitializationFinished = true
            mainHandler.post {
                onVoiceReady(polishAvailable)
                val queued = pendingSpeech
                pendingSpeech = null
                if (queued != null && polishAvailable) speakNow(queued)
                else if (liveConversation) scheduleListeningRetry(250L)
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post { onSpeakingChanged(true) }
            }
            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    onSpeakingChanged(false)
                    if (liveConversation) scheduleListeningRetry(450L)
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    onSpeakingChanged(false)
                    if (liveConversation) scheduleListeningRetry(450L)
                }
            }
        })
    }

    fun startLiveConversation(): Boolean {
        if (recognizer == null) {
            onError("Na tym urządzeniu nie ma usługi rozpoznawania mowy.")
            return false
        }
        liveConversation = true
        startListening()
        return true
    }

    fun stopLiveConversation() {
        liveConversation = false
        mainHandler.removeCallbacks(retryListening)
        try { recognizer?.cancel() } catch (_: Exception) { }
        onListeningChanged(false)
    }

    fun pauseForResponse() {
        mainHandler.removeCallbacks(retryListening)
        try { recognizer?.cancel() } catch (_: Exception) { }
        onListeningChanged(false)
    }

    fun resumeAfterResponse() {
        if (liveConversation) scheduleListeningRetry(300L)
    }

    fun stopSpeaking() {
        try { tts?.stop() } catch (_: Exception) { }
        onSpeakingChanged(false)
    }

    fun speak(text: String): Boolean {
        if (!ttsReady) {
            if (ttsInitializationFinished) return false
            pendingSpeech = text
            pauseForResponse()
            return true
        }
        return speakNow(text)
    }

    private fun speakNow(text: String): Boolean {
        val engine = tts ?: return false
        pauseForResponse()
        val utteranceId = "milo-${++utteranceNumber}"
        val status = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (status != TextToSpeech.SUCCESS) return false
        return true
    }

    private fun startListening() {
        if (!liveConversation) return
        val service = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pl-PL")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Milo słucha. Powiedz coś po polsku.")
        }
        try {
            service.startListening(intent)
        } catch (_: Exception) {
            onListeningChanged(false)
            scheduleListeningRetry(1_000L)
        }
    }

    private fun scheduleListeningRetry(delayMillis: Long) {
        if (!liveConversation) return
        mainHandler.removeCallbacks(retryListening)
        mainHandler.postDelayed(retryListening, delayMillis)
    }

    fun shutdown() {
        stopLiveConversation()
        try { recognizer?.destroy() } catch (_: Exception) { }
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) { }
        recognizer = null
        tts = null
    }
}

class MainActivity : ComponentActivity() {
    private val updateMessage = mutableStateOf<String?>(null)
    private var pendingUpdate: File? = null
    private var installLaunched = false
    private lateinit var unknownSourcesLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        unknownSourcesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (packageManager.canRequestPackageInstalls()) installPendingUpdate()
        }
        setContent { MiloApp(updateMessage.value, ::startInstallOrRequestPermission) }
        checkForPrivateUpdate()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (!installLaunched && pendingUpdate != null && packageManager.canRequestPackageInstalls()) installPendingUpdate()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun checkForPrivateUpdate() {
        lifecycleScope.launch {
            try {
                val update = withContext(Dispatchers.IO) { downloadPrivateUpdateIfNeeded() }
                if (update != null) {
                    pendingUpdate = update.first
                    updateMessage.value = "Dostępna jest aktualizacja Milo ${update.second}."
                    if (packageManager.canRequestPackageInstalls()) installPendingUpdate()
                }
            } catch (_: Exception) {
                updateMessage.value = null
            }
        }
    }

    private fun startInstallOrRequestPermission() {
        if (packageManager.canRequestPackageInstalls()) {
            installPendingUpdate()
        } else {
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            unknownSourcesLauncher.launch(settingsIntent)
        }
    }

    private fun installPendingUpdate() {
        val apk = pendingUpdate ?: return
        if (!apk.isFile) {
            updateMessage.value = "Plik aktualizacji zniknął. Uruchom Milo ponownie, aby pobrać go jeszcze raz."
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            installLaunched = true
            startActivity(install)
        } catch (e: Exception) {
            updateMessage.value = "Nie można otworzyć instalatora Androida: ${e.message ?: "błąd"}"
        }
    }

    private fun downloadPrivateUpdateIfNeeded(): Pair<File, String>? {
        val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        val baseUrl = preferences.getString(SERVER_URL_KEY, BuildConfig.BACKEND_URL).orEmpty().trimEnd('/')
        val serverToken = preferences.getString(SERVER_TOKEN_KEY, "").orEmpty()
        val manifestConnection = (URL("$baseUrl/update/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Authorization", "Bearer $serverToken")
        }
        val manifestText = try {
            if (manifestConnection.responseCode !in 200..299) return null
            manifestConnection.inputStream.bufferedReader().use { it.readText() }
        } finally { manifestConnection.disconnect() }
        val manifest = JSONObject(manifestText)
        if (!manifest.optBoolean("available")) return null
        if (manifest.optInt("versionCode") <= BuildConfig.VERSION_CODE) return null

        val backendUrl = URL(baseUrl)
        val apkUrl = URL(backendUrl, manifest.getString("apkUrl"))
        if (!BuildConfig.DEBUG) require(backendUrl.protocol == "https") {
            "Wersja produkcyjna wymaga bezpiecznego połączenia HTTPS."
        }
        require(apkUrl.host.equals(backendUrl.host, ignoreCase = true) && apkUrl.port == backendUrl.port && apkUrl.protocol == backendUrl.protocol) {
            "Serwer aktualizacji podał niezgodny adres APK."
        }
        val expectedHash = manifest.getString("sha256").lowercase()
        require(expectedHash.matches(Regex("[a-f0-9]{64}"))) { "Nieprawidłowa suma kontrolna aktualizacji." }
        val updateDir = File(cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updateDir, "milo-update-${manifest.getInt("versionCode")}.apk")
        if (!apkFile.isFile || sha256(apkFile) != expectedHash) {
            val connection = (apkUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 60_000
                setRequestProperty("Authorization", "Bearer $serverToken")
            }
            try {
                if (connection.responseCode !in 200..299) throw IllegalStateException("Serwer nie udostępnił pliku APK.")
                if (connection.contentLengthLong > 150_000_000L) throw IllegalStateException("Plik aktualizacji jest zbyt duży.")
                connection.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 150_000_000L) throw IllegalStateException("Plik aktualizacji jest zbyt duży.")
                            output.write(buffer, 0, count)
                        }
                    }
                }
            } finally { connection.disconnect() }
            if (sha256(apkFile) != expectedHash) {
                apkFile.delete()
                throw IllegalStateException("Suma kontrolna pobranego APK jest niezgodna.")
            }
        }
        return apkFile to manifest.getString("versionName")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiloApp(updateMessage: String?, onInstallUpdate: () -> Unit) {
    val context = LocalContext.current
    var chatGptAccount by remember { mutableStateOf(ChatGptPlanClient.currentAccount(context)) }
    var connecting by remember { mutableStateOf(false) }
    var settingsDialog by remember { mutableStateOf(false) }
    val messages = remember {
        mutableStateListOf<ChatMessage>().apply {
            addAll(loadMessages(context))
            if (isEmpty()) add(ChatMessage("assistant", "Cześć! Jestem Milo. Możemy luźno pogadać — także na głos przyciskiem 🎙️ — poszukać czegoś w sieci albo pokazać mi coś przez kamerę. 🙂"))
        }
    }
    val memories = remember { mutableStateListOf<String>().apply { addAll(loadMemories(context)) } }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var cameraOpen by remember { mutableStateOf(false) }
    var chatOpen by remember { mutableStateOf(false) }
    var cameraAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var photoBase64 by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var memoryDialog by remember { mutableStateOf(false) }
    var voiceEnabled by remember {
        mutableStateOf(context.getSharedPreferences(PREFS, 0).getBoolean(VOICE_ENABLED_KEY, true))
    }
    var voiceReady by remember { mutableStateOf(false) }
    var liveConversation by remember { mutableStateOf(false) }
    var voiceListening by remember { mutableStateOf(false) }
    var voiceSpeaking by remember { mutableStateOf(false) }
    var voiceController by remember { mutableStateOf<MiloVoiceController?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val latestVoiceEnabled = rememberUpdatedState(voiceEnabled)
    val submitMessage: (String) -> Unit = { rawText ->
        val text = rawText.trim()
        if (text.isNotEmpty() && !busy) {
            draft = ""
            notice = null
            voiceController?.pauseForResponse()
            messages.add(ChatMessage("user", text))
            rememberRequestedFact(text)?.let { fact ->
                if (memories.none { it.equals(fact, ignoreCase = true) }) memories.add(fact)
            }
            saveMessages(context, messages)
            saveMemories(context, memories)
            val image = photoBase64
            photoBase64 = null
            busy = true
            scope.launch {
                var answerToSpeak = ""
                try {
                    answerToSpeak = requestMilo(
                        context, messages.dropLast(1), text, image, memories.joinToString("\n")
                    )
                    messages.add(ChatMessage("assistant", answerToSpeak))
                    saveMessages(context, messages)
                } catch (e: Exception) {
                    answerToSpeak = "Nie udało mi się uzyskać odpowiedzi z ChatGPT. Sprawdź logowanie Milo i internet. Szczegóły: ${e.message ?: "brak odpowiedzi"}"
                    messages.add(ChatMessage("assistant", answerToSpeak))
                    saveMessages(context, messages)
                } finally {
                    busy = false
                    val spoken = if (latestVoiceEnabled.value) voiceController?.speak(answerToSpeak) == true else false
                    if (!spoken) voiceController?.resumeAfterResponse()
                }
            }
        }
    }
    val latestSubmitMessage = rememberUpdatedState(submitMessage)
    DisposableEffect(context) {
        val controller = MiloVoiceController(
            context = context,
            onTranscript = { words -> latestSubmitMessage.value(words) },
            onListeningChanged = { voiceListening = it },
            onSpeakingChanged = { voiceSpeaking = it },
            onVoiceReady = { ready ->
                voiceReady = ready
                if (!ready) notice = "Nie znaleziono polskiego głosu w Androidzie. Zainstaluj polski głos w ustawieniach syntezy mowy."
            },
            onError = { notice = it }
        )
        voiceController = controller
        onDispose {
            controller.shutdown()
            voiceController = null
            liveConversation = false
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraAllowed = granted
        if (granted) cameraOpen = true else notice = "Bez zgody na kamerę nie mogę zobaczyć zdjęcia. Możesz ją włączyć w ustawieniach aplikacji."
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            liveConversation = voiceController?.startLiveConversation() == true
            notice = if (liveConversation) "Rozmowa na żywo włączona. Android rozpoznaje mowę; Milo wysyła do ChatGPT rozpoznany tekst. Milo będzie słuchał, odpowiadał i znów słuchał." else "Nie udało się uruchomić rozmowy głosowej."
        } else {
            liveConversation = false
            notice = "Aby rozmawiać na żywo, zezwól Milo na dostęp do mikrofonu."
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Teal, background = Cream, surface = Color.White, onSurface = Ink)) {
        Box(Modifier.fillMaxSize().background(Cream)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                updateMessage?.let { status ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SoftMint).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(status, color = Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        if (status.startsWith("Dostępna")) TextButton(onClick = onInstallUpdate) { Text("Aktualizuj") }
                    }
                }
                if (cameraOpen) {
                    CameraPanel(
                        memory = memories.joinToString("\n"),
                        liveConversation = liveConversation,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        onCapture = { encoded -> photoBase64 = encoded; cameraOpen = false; chatOpen = true; notice = "Zdjęcie gotowe. Napisz, co mam na nim sprawdzić." },
                        onClose = { cameraOpen = false },
                        onError = { notice = it }
                    )
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                            MiloHero(
                                Modifier.weight(1f).fillMaxWidth(),
                                mood = when { busy -> RobotMood.THINKING; voiceSpeaking -> RobotMood.SPEAKING; voiceListening -> RobotMood.LISTENING; else -> RobotMood.FRIENDLY }
                            )
                            Text(
                                when { busy -> "Myślę…"; voiceSpeaking -> "Mówię"; liveConversation && voiceListening -> "Słucham"; liveConversation -> "Nasłuchuję"; else -> "Gotowy" },
                                color = Teal, fontSize = 15.sp, fontWeight = FontWeight.Medium
                            )
                            if (notice != null) Text(notice.orEmpty(), color = Color(0xFF698087), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 2)
                            if (photoBase64 != null) Text("📷 Zdjęcie czeka w czacie", color = Teal, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { chatOpen = true }, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(50.dp)) { Text("💬", fontSize = 21.sp) }
                    FilledTonalButton(onClick = { notice = null; if (cameraAllowed) cameraOpen = true else permission.launch(Manifest.permission.CAMERA) }, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(50.dp)) { Text("📷", fontSize = 20.sp) }
                    FilledTonalButton(
                        onClick = {
                            if (liveConversation) { voiceController?.stopLiveConversation(); liveConversation = false; notice = "Zakończono rozmowę na żywo." }
                            else if (chatGptAccount == null) { notice = "Najpierw połącz Milo z ChatGPT w ustawieniach."; settingsDialog = true }
                            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                liveConversation = voiceController?.startLiveConversation() == true
                                notice = if (liveConversation) "Rozmowa na żywo włączona." else "Nie udało się uruchomić rozmowy głosowej."
                            } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        enabled = !busy || liveConversation, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(54.dp),
                        colors = if (liveConversation) ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFF7D9D3)) else ButtonDefaults.filledTonalButtonColors()
                    ) { Text(if (liveConversation) "⏹" else "🎙️", fontSize = 22.sp) }
                    FilledTonalButton(onClick = { voiceEnabled = !voiceEnabled; context.getSharedPreferences(PREFS, 0).edit().putBoolean(VOICE_ENABLED_KEY, voiceEnabled).apply(); if (!voiceEnabled) { voiceController?.stopSpeaking(); voiceController?.resumeAfterResponse() } }, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(50.dp)) { Text(if (voiceEnabled) "🔊" else "🔇", fontSize = 19.sp) }
                    FilledTonalButton(onClick = { memoryDialog = true }, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(50.dp)) { Text("🧠", fontSize = 20.sp) }
                    FilledTonalButton(onClick = { chatGptAccount = ChatGptPlanClient.currentAccount(context); settingsDialog = true }, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(50.dp)) { Text("⚙️", fontSize = 20.sp) }
                }
            }
        }
        if (chatOpen) {
            ModalBottomSheet(onDismissRequest = { chatOpen = false }, containerColor = Cream, dragHandle = { BottomSheetDefaults.DragHandle() }) {
                Column(Modifier.fillMaxWidth().fillMaxHeight(.82f).imePadding().padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Rozmowa z Milo", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            val transcript = messages.joinToString("\n\n") { "${if (it.role == "user") "Ty" else "Milo"}: ${it.text}" }
                            val share = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Rozmowa z Milo"); putExtra(Intent.EXTRA_TEXT, transcript) }
                            context.startActivity(Intent.createChooser(share, "Przekaż rozmowę do aplikacji czatu"))
                        }) { Text("Udostępnij") }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(messages) { message ->
                            val fromMilo = message.role == "assistant"
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromMilo) Arrangement.Start else Arrangement.End, verticalAlignment = Alignment.Bottom) {
                                if (fromMilo) { Image(painterResource(R.drawable.milo_hero), contentDescription = "Milo", modifier = Modifier.size(34.dp), contentScale = ContentScale.Fit); Spacer(Modifier.width(7.dp)) }
                                Text(message.text, modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp, 18.dp, if (fromMilo) 18.dp else 4.dp, if (fromMilo) 4.dp else 18.dp)).background(if (fromMilo) Color.White else SoftMint).padding(horizontal = 15.dp, vertical = 12.dp), color = Ink, fontSize = 15.sp, lineHeight = 21.sp)
                            }
                        }
                        if (busy) item { Text("Milo zastanawia się…", color = Color(0xFF698087)) }
                    }
                    if (photoBase64 != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("📷 Zdjęcie dołączone", color = Teal, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { photoBase64 = null }) { Text("Usuń") }
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(horizontal = 16.dp, vertical = 14.dp), textStyle = TextStyle(color = Ink, fontSize = 15.sp), decorationBox = { inner -> if (draft.isEmpty()) Text("Napisz do Milo…", color = Color(0xFF92A3A5), fontSize = 15.sp); inner() })
                        Spacer(Modifier.width(8.dp))
                        Button(enabled = draft.isNotBlank() && !busy, onClick = { submitMessage(draft) }, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)) { Text("➤", fontSize = 18.sp) }
                    }
                }
            }
        }
    }
    if (memoryDialog) {
        AlertDialog(
            onDismissRequest = { memoryDialog = false },
            title = { Text("Czego Milo się nauczył") },
            text = {
                Text(
                    if (memories.isEmpty()) "Na razie brak zapamiętanych informacji. Napisz np. „Zapamiętaj, że lubię góry”, a Milo zachowa tę informację na tym telefonie. Pamięć jest dołączana do rozmów z AI."
                    else memories.joinToString("\n\n") { "• $it" } + "\n\nPamięć jest zapisana na tym telefonie i przekazywana do AI podczas rozmowy."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    memories.clear()
                    saveMemories(context, memories)
                    memoryDialog = false
                    notice = "Usunięto zapamiętane informacje."
                }) { Text("Wyczyść pamięć") }
            },
            dismissButton = { TextButton(onClick = { memoryDialog = false }) { Text("Gotowe") } }
        )
    }
    if (settingsDialog) {
        AlertDialog(
            onDismissRequest = { settingsDialog = false },
            title = { Text("Połączenie z ChatGPT") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (chatGptAccount == null) "Zaloguj Milo przez oficjalne okno OpenAI. Milo otrzyma wyłącznie zgodę na własne zapytania; nie zobaczy historii rozmów w ChatGPT. Jego pamięć jest osobna i przechowywana na tym telefonie."
                        else "Połączone konto: ${chatGptAccount?.email}\nModel: ${chatGptAccount?.model}\nZużycie Milo podlega limitom planu ChatGPT.",
                        fontSize = 14.sp
                    )
                    notice?.let { Text(it, color = if (it.contains("DNS", true)) Color(0xFF9A4B31) else Color(0xFF526C71), fontSize = 13.sp) }
                    if (notice?.contains("DNS", true) == true) TextButton(onClick = {
                        try { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                        catch (_: Exception) { notice = "Otwórz Ustawienia Androida → Sieć i internet." }
                    }) { Text("Otwórz ustawienia sieci") }
                    if (connecting) Text("Oczekiwanie na logowanie w przeglądarce…", color = Teal)
                    TextButton(onClick = {
                        try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                        catch (_: Exception) { notice = "Otwórz w ustawieniach Androida: Synteza mowy / Głos." }
                    }) { Text("Wybierz głos Androida") }
                }
            },
            confirmButton = {
                Row {
                    if (chatGptAccount != null) TextButton(onClick = {
                        scope.launch {
                            connecting = true
                            try {
                                ChatGptPlanClient.disconnect(context)
                                chatGptAccount = null
                                notice = "Odłączono konto ChatGPT Milo."
                            } catch (e: Exception) { notice = e.message }
                            finally { connecting = false }
                        }
                    }, enabled = !connecting) { Text("Odłącz") }
                    TextButton(onClick = {
                        val activity = context as? android.app.Activity
                        if (activity == null) { notice = "Nie udało się otworzyć logowania."; return@TextButton }
                        connecting = true
                        notice = null
                        scope.launch {
                            try {
                                chatGptAccount = ChatGptPlanClient.authorize(activity)
                                notice = "Milo połączył się z planem ChatGPT."
                            } catch (e: Exception) { notice = loginFailureMessage(e) }
                            finally { connecting = false }
                        }
                    }, enabled = !connecting) { Text(if (notice?.contains("DNS", true) == true) "Spróbuj ponownie" else if (chatGptAccount == null) "Continue with ChatGPT" else "Połącz ponownie") }
                }
            },
            dismissButton = { TextButton(onClick = { settingsDialog = false }) { Text("Gotowe") } }
        )
    }
    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex + if (busy) 1 else 0)
    }
}

private fun loginFailureMessage(error: Throwable): String {
    val causeChain = generateSequence(error) { it.cause }.toList()
    val dnsFailure = causeChain.any { it is UnknownHostException } ||
        causeChain.any { it.message?.contains("Unable to resolve host", ignoreCase = true) == true }
    return if (dnsFailure) {
        val detail = causeChain.firstNotNullOfOrNull { cause ->
            cause.message?.takeIf { it.contains("DNS", ignoreCase = true) || it.contains("Unable to resolve host", ignoreCase = true) }
        }
        "Milo nie mógł odnaleźć serwera potrzebnego do logowania. Jeśli strona OpenAI otwiera się w przeglądarce, problem dotyczy połączenia aplikacji. Sprawdź aktualizację Milo i spróbuj ponownie. ${detail.orEmpty()}"
    } else "Logowanie nie powiodło się: ${error.message ?: "sprawdź połączenie i spróbuj ponownie"}"
}

@Composable
private fun MiloHero(modifier: Modifier = Modifier, mood: RobotMood = RobotMood.FRIENDLY) {
    val motion = rememberInfiniteTransition(label = "Milo hero motion")
    val bob by motion.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(2_700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "Milo floating"
    )
    val glow by motion.animateFloat(
        initialValue = .30f,
        targetValue = .58f,
        animationSpec = infiniteRepeatable(tween(2_200), RepeatMode.Reverse),
        label = "Milo glow"
    )
    val blink by motion.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = keyframes {
            durationMillis = 4_600
            1f at 0
            1f at 3_950
            .04f at 4_015
            .04f at 4_095
            1f at 4_180
            1f at 4_600
        }),
        label = "Milo blink"
    )
    val talkingMouth by motion.animateFloat(
        initialValue = .012f,
        targetValue = .052f,
        animationSpec = infiniteRepeatable(tween(240, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "Milo talking mouth"
    )
    val sway by motion.animateFloat(
        initialValue = -1.15f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(3_100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "Milo body sway"
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(300.dp).background(
                Brush.radialGradient(listOf(Color(0xFF8DE9DA).copy(alpha = glow), Color(0xFFE2F2ED).copy(alpha = .2f), Color.Transparent)),
                CircleShape
            )
        )
        Box(Modifier.fillMaxHeight(.98f).aspectRatio(.721f).offset(y = bob.dp).rotate(sway + when (mood) { RobotMood.CURIOUS -> -1.8f; RobotMood.THINKING -> 1.2f; else -> 0f }), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.milo_hero_blankface),
                contentDescription = "Przyjazny biały robot z niebieskim wizjerem",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val face = Color(0xFF82F4FF)
                val highlight = Color(0xFFE7FFFF)
                val eyeY = when (mood) { RobotMood.THINKING -> .285f; RobotMood.CURIOUS -> .278f; else -> .292f }
                val eyeScale = if (mood == RobotMood.CURIOUS) 1.16f else 1f
                val eyeHeight = .047f * blink * if (mood == RobotMood.LISTENING) 1.22f else 1f
                val eyeWidth = .118f * eyeScale
                val gaze = if (mood == RobotMood.THINKING) .012f else 0f
                listOf(.402f, .628f).forEach { x ->
                    val centerX = (x + gaze) * w
                    val centerY = eyeY * h
                    if (mood == RobotMood.SPEAKING || mood == RobotMood.LISTENING) {
                        drawOval(face, topLeft = androidx.compose.ui.geometry.Offset(centerX - eyeWidth * w / 2f, centerY - eyeHeight * h / 2f), size = androidx.compose.ui.geometry.Size(eyeWidth * w, eyeHeight * h))
                        drawCircle(Color(0xFF075481), radius = w * .014f, center = androidx.compose.ui.geometry.Offset(centerX, centerY))
                    } else if (mood == RobotMood.THINKING) {
                        drawArc(face, startAngle = 195f, sweepAngle = 150f, useCenter = false, topLeft = androidx.compose.ui.geometry.Offset(centerX - eyeWidth * w / 2f, centerY - .018f * h), size = androidx.compose.ui.geometry.Size(eyeWidth * w, .042f * h), style = Stroke(width = w * .009f, cap = StrokeCap.Round))
                    } else {
                        drawRoundRect(face, topLeft = androidx.compose.ui.geometry.Offset(centerX - eyeWidth * w / 2f, centerY - eyeHeight * h / 2f), size = androidx.compose.ui.geometry.Size(eyeWidth * w, eyeHeight * h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .018f))
                        drawCircle(highlight, radius = w * .009f, center = androidx.compose.ui.geometry.Offset(centerX - w * .018f, centerY - h * .008f))
                    }
                }
                when (mood) {
                    RobotMood.CURIOUS -> {
                        drawArc(face, 190f, 155f, false, androidx.compose.ui.geometry.Offset(.355f * w, .252f * h), androidx.compose.ui.geometry.Size(.095f * w, .035f * h), style = Stroke(w * .009f, cap = StrokeCap.Round))
                        drawOval(face, topLeft = androidx.compose.ui.geometry.Offset(.478f * w, .337f * h), size = androidx.compose.ui.geometry.Size(.052f * w, .048f * h))
                    }
                    RobotMood.THINKING -> {
                        drawLine(face.copy(alpha = .8f), androidx.compose.ui.geometry.Offset(.38f * w, .253f * h), androidx.compose.ui.geometry.Offset(.45f * w, .26f * h), w * .008f, cap = StrokeCap.Round)
                        drawArc(face, 205f, 130f, false, androidx.compose.ui.geometry.Offset(.47f * w, .34f * h), androidx.compose.ui.geometry.Size(.07f * w, .03f * h), style = Stroke(w * .007f, cap = StrokeCap.Round))
                    }
                    RobotMood.SPEAKING -> drawOval(face, topLeft = androidx.compose.ui.geometry.Offset(.474f * w, .339f * h), size = androidx.compose.ui.geometry.Size(.058f * w, talkingMouth * h))
                    RobotMood.LISTENING -> drawArc(face, 20f, 140f, false, androidx.compose.ui.geometry.Offset(.465f * w, .337f * h), androidx.compose.ui.geometry.Size(.07f * w, .04f * h), style = Stroke(w * .008f, cap = StrokeCap.Round))
                    RobotMood.FRIENDLY -> drawArc(face, 20f, 140f, false, androidx.compose.ui.geometry.Offset(.451f * w, .329f * h), androidx.compose.ui.geometry.Size(.105f * w, .058f * h), style = Stroke(w * .009f, cap = StrokeCap.Round))
                }
            }
        }
    }
}

private enum class RobotMood { FRIENDLY, THINKING, CURIOUS, LISTENING, SPEAKING }

@Composable
private fun FriendlyRobotFace(modifier: Modifier = Modifier, mood: RobotMood = RobotMood.FRIENDLY) {
    val motion = rememberInfiniteTransition(label = "Milo robot face")
    val blink = motion.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 4_800
                1f at 0
                1f at 4_050
                .08f at 4_110
                .08f at 4_180
                1f at 4_250
                1f at 4_800
            }
        ),
        label = "blink"
    )
    val bob by motion.animateFloat(
        initialValue = -1.2f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "idle bob"
    )
    val mouthPulse by motion.animateFloat(
        initialValue = .055f,
        targetValue = .095f,
        animationSpec = infiniteRepeatable(tween(260, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "speech mouth"
    )
    Canvas(modifier.offset(y = bob.dp)) {
        val s = size.minDimension
        val x = (size.width - s) / 2f
        val y = (size.height - s) / 2f
        fun p(px: Float, py: Float) = androidx.compose.ui.geometry.Offset(x + s * px, y + s * py)
        fun faceSize(w: Float, h: Float) = androidx.compose.ui.geometry.Size(s * w, s * h)

        // Milo's own rounded helmet, side audio pods, glass face and glowing antenna.
        drawRoundRect(Color(0xFF93CFC1), topLeft = p(.04f, .28f), size = faceSize(.10f, .26f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * .09f))
        drawRoundRect(Color(0xFF93CFC1), topLeft = p(.86f, .28f), size = faceSize(.10f, .26f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * .09f))
        drawRoundRect(Color(0xFFBFE9DE), topLeft = p(.09f, .20f), size = faceSize(.82f, .62f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * .25f))
        drawRoundRect(Color(0xFF78B9AA), topLeft = p(.09f, .20f), size = faceSize(.82f, .62f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * .25f), style = Stroke(s * .025f))
        drawRoundRect(Color(0xFF173F48), topLeft = p(.16f, .30f), size = faceSize(.68f, .46f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * .18f))
        drawLine(Color(0xFF5A938B), p(.50f, .20f), p(.50f, .10f), s * .035f, cap = StrokeCap.Round)
        drawCircle(Color(0xFFFFB870), s * .065f, p(.50f, .085f))
        drawCircle(Color(0xFFFFD39A), s * .026f, p(.48f, .065f))

        val eyeColor = Color(0xFF8FF5DD)
        val eyeY = when (mood) {
            RobotMood.THINKING -> .445f
            RobotMood.CURIOUS -> .425f
            RobotMood.LISTENING, RobotMood.SPEAKING, RobotMood.FRIENDLY -> .445f
        }
        val eyeHeight = when (mood) {
            RobotMood.CURIOUS, RobotMood.LISTENING -> .115f
            else -> .085f
        } * blink.value
        val gazeX = if (mood == RobotMood.THINKING) .018f else 0f
        listOf(.35f, .65f).forEach { eyeX ->
            drawOval(eyeColor, topLeft = p(eyeX - .065f, eyeY - eyeHeight / 2f), size = faceSize(.13f, eyeHeight))
            val pupilY = eyeY + if (mood == RobotMood.THINKING) -.012f else 0f
            drawCircle(Color(0xFF173F48), s * .023f * blink.value, p(eyeX + gazeX, pupilY))
            if (mood != RobotMood.THINKING) drawCircle(Color.White.copy(alpha = .82f), s * .012f * blink.value, p(eyeX + gazeX - .008f, pupilY - .009f))
        }

        val browColor = Color(0xFF8FF5DD)
        if (mood == RobotMood.CURIOUS) {
            drawLine(browColor, p(.25f, .345f), p(.39f, .315f), s * .025f, cap = StrokeCap.Round)
            drawLine(browColor, p(.61f, .315f), p(.75f, .345f), s * .025f, cap = StrokeCap.Round)
            drawOval(browColor, topLeft = p(.465f, .56f), size = faceSize(.07f, .07f))
        } else if (mood == RobotMood.LISTENING) {
            drawLine(browColor, p(.27f, .34f), p(.40f, .32f), s * .024f, cap = StrokeCap.Round)
            drawLine(browColor, p(.60f, .32f), p(.73f, .34f), s * .024f, cap = StrokeCap.Round)
            drawArc(browColor, 20f, 140f, false, topLeft = p(.40f, .545f), size = faceSize(.20f, .105f), style = Stroke(width = s * .028f, cap = StrokeCap.Round))
        } else if (mood == RobotMood.THINKING) {
            drawLine(browColor.copy(alpha = .8f), p(.27f, .35f), p(.40f, .34f), s * .022f, cap = StrokeCap.Round)
            drawLine(browColor.copy(alpha = .8f), p(.60f, .34f), p(.73f, .35f), s * .022f, cap = StrokeCap.Round)
            drawArc(browColor, 205f, 130f, false, topLeft = p(.43f, .56f), size = faceSize(.14f, .07f), style = Stroke(width = s * .025f, cap = StrokeCap.Round))
        } else if (mood == RobotMood.SPEAKING) {
            drawArc(browColor, 205f, 130f, false, topLeft = p(.27f, .33f), size = faceSize(.18f, .07f), style = Stroke(width = s * .022f, cap = StrokeCap.Round))
            drawArc(browColor, 205f, 130f, false, topLeft = p(.55f, .33f), size = faceSize(.18f, .07f), style = Stroke(width = s * .022f, cap = StrokeCap.Round))
            drawOval(browColor, topLeft = p(.5f - .07f, .555f), size = faceSize(.14f, mouthPulse))
        } else {
            drawArc(browColor.copy(alpha = .9f), 205f, 130f, false, topLeft = p(.245f, .335f), size = faceSize(.21f, .07f), style = Stroke(width = s * .022f, cap = StrokeCap.Round))
            drawArc(browColor.copy(alpha = .9f), 205f, 130f, false, topLeft = p(.545f, .335f), size = faceSize(.21f, .07f), style = Stroke(width = s * .022f, cap = StrokeCap.Round))
            drawArc(browColor, 20f, 140f, false, topLeft = p(.37f, .535f), size = faceSize(.26f, .16f), style = Stroke(width = s * .032f, cap = StrokeCap.Round))
        }
        if (mood == RobotMood.THINKING) {
            drawCircle(Color(0xFFFFB870).copy(alpha = .8f), s * .018f, p(.79f, .55f))
        } else {
            drawCircle(Color(0xFFFF9F9A).copy(alpha = .45f), s * .034f, p(.245f, .53f))
            drawCircle(Color(0xFFFF9F9A).copy(alpha = .45f), s * .034f, p(.755f, .53f))
        }
    }
}

@Composable
private fun CameraPanel(
    memory: String,
    liveConversation: Boolean,
    modifier: Modifier = Modifier,
    onCapture: (String) -> Unit,
    onClose: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var liveAnalysis by remember { mutableStateOf(false) }
    var liveDescription by remember { mutableStateOf<String?>(null) }
    var savedFrameCount by remember { mutableIntStateOf(File(context.filesDir, "milo_camera_history").listFiles()?.count { it.isFile } ?: 0) }
    var latestFrame by remember { mutableStateOf<String?>(null) }
    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            } catch (e: Exception) { onError("Nie udało się uruchomić kamery: ${e.message}") }
        }
        providerFuture.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose { try { providerFuture.get().unbindAll(); executor.shutdown() } catch (_: Exception) { } }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            val previewBitmap = previewView.bitmap
            val frame = previewBitmap?.let { bitmap ->
                withContext(Dispatchers.Default) {
                    val scaled = if (bitmap.width > 640) {
                        Bitmap.createScaledBitmap(bitmap, 640, (bitmap.height * (640f / bitmap.width)).toInt().coerceAtLeast(1), true)
                    } else bitmap
                    ByteArrayOutputStream().use { output ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, 62, output)
                        if (scaled !== bitmap) scaled.recycle()
                        Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                    }
                }
            }
            if (frame != null) {
                latestFrame = frame
                try {
                    savedFrameCount = withContext(Dispatchers.IO) {
                        val frameDir = File(context.filesDir, "milo_camera_history").apply { mkdirs() }
                        val saved = File(frameDir, "frame-${System.currentTimeMillis()}.jpg")
                        saved.writeBytes(Base64.decode(frame, Base64.NO_WRAP))
                        frameDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                            ?.drop(300)?.forEach { it.delete() }
                        frameDir.listFiles()?.count { it.isFile } ?: 0
                    }
                } catch (_: Exception) { }
            }
        }
    }
    LaunchedEffect(liveAnalysis) {
        while (liveAnalysis) {
            delay(5_000)
            val frame = latestFrame
            if (frame != null) {
                liveDescription = "Milo analizuje obraz…"
                try {
                    liveDescription = requestMilo(
                        context,
                        emptyList(),
                        "Opisz krótko, co widać teraz na obrazie. Jeśli są widoczne liczby, tekst lub dane, odczytaj je ostrożnie i zaznacz niepewność.",
                        frame,
                        memory,
                        webSearch = false
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    liveDescription = "Analiza chwilowo niedostępna: ${e.message ?: "brak odpowiedzi"}"
                }
            } else liveDescription = "Czekam na pierwszą zapisaną klatkę."
        }
    }
    Column(modifier.background(Color(0xFF17363C)).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Klatki zapisywane co sekundę · zachowuję ostatnie 5 min. ${if (liveConversation) "Mikrofon jest aktywny." else "Mikrofon dostępny na dole."}", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Zamknij", color = Color(0xFF9EE8D5)) }
        }
        AndroidView(factory = { previewView }, modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(18.dp)))
        Text("Zapisane klatki: $savedFrameCount / 300", color = Color(0xFF9CC9C3), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        liveDescription?.let { result ->
            Text(result, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        }
        OutlinedButton(
            onClick = { liveAnalysis = !liveAnalysis },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
        ) { Text(if (liveAnalysis) "Zatrzymaj analizę AI · co 5 s" else "Włącz analizę AI · co 5 s") }
        Button(onClick = {
            val file = File.createTempFile("milo-camera-", ".jpg", context.cacheDir)
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            capture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                    file.delete()
                    ContextCompat.getMainExecutor(context).execute { onCapture(encoded) }
                }
                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    ContextCompat.getMainExecutor(context).execute { onError("Nie udało się zrobić zdjęcia: ${exception.message}") }
                }
            })
        }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Zrób zdjęcie dla Milo") }
    }
}

private suspend fun requestMilo(
    context: android.content.Context,
    previous: List<ChatMessage>,
    current: String,
    image: String?,
    memory: String,
    webSearch: Boolean = true
): String = ChatGptPlanClient.reply(context, previous, current, image, memory, webSearch)

private const val PREFS = "milo_data"
private const val MESSAGES_KEY = "messages"
private const val MEMORIES_KEY = "memories"
private const val VOICE_ENABLED_KEY = "voice_enabled"
private const val SERVER_URL_KEY = "server_url"
private const val SERVER_TOKEN_KEY = "server_token"

private fun loadServerConfig(context: android.content.Context): Pair<String, String> {
    val preferences = context.getSharedPreferences(PREFS, 0)
    return preferences.getString(SERVER_URL_KEY, BuildConfig.BACKEND_URL).orEmpty() to
        preferences.getString(SERVER_TOKEN_KEY, "").orEmpty()
}

private fun loadMessages(context: android.content.Context): List<ChatMessage> = try {
    val json = JSONArray(context.getSharedPreferences(PREFS, 0).getString(MESSAGES_KEY, "[]"))
    (0 until json.length()).map { index ->
        val item = json.getJSONObject(index)
        ChatMessage(item.getString("role"), item.getString("text"))
    }
} catch (_: Exception) { emptyList() }

private fun loadMemories(context: android.content.Context): List<String> = try {
    val json = JSONArray(context.getSharedPreferences(PREFS, 0).getString(MEMORIES_KEY, "[]"))
    (0 until json.length()).map { json.getString(it) }
} catch (_: Exception) { emptyList() }

private fun saveMessages(context: android.content.Context, messages: List<ChatMessage>) {
    val json = JSONArray()
    messages.takeLast(200).forEach { json.put(JSONObject().put("role", it.role).put("text", it.text)) }
    context.getSharedPreferences(PREFS, 0).edit().putString(MESSAGES_KEY, json.toString()).apply()
}

private fun saveMemories(context: android.content.Context, memories: List<String>) {
    val json = JSONArray()
    memories.take(100).forEach { json.put(it) }
    context.getSharedPreferences(PREFS, 0).edit().putString(MEMORIES_KEY, json.toString()).apply()
}

private fun rememberRequestedFact(text: String): String? {
    val match = Regex("(?i)^\\s*(?:milo[, ]+)?zapamiętaj(?:, że| że|:)?\\s*(.+)$").find(text) ?: return null
    return match.groupValues[1].trim().takeIf { it.length >= 3 }?.take(300)
}
