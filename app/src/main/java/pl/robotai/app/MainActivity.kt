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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
                ?.maxWithOrNull(compareBy<Voice> { if (it.quality >= Voice.QUALITY_HIGH) 1 else 0 }
                    .thenBy { if (!it.isNetworkConnectionRequired) 1 else 0 })
                ?.let(engine::setVoice)
            engine.setPitch(.94f)
            engine.setSpeechRate(.91f)
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
        unknownSourcesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (packageManager.canRequestPackageInstalls()) installPendingUpdate()
        }
        setContent { MiloApp(updateMessage.value, ::startInstallOrRequestPermission) }
        checkForPrivateUpdate()
    }

    override fun onResume() {
        super.onResume()
        if (!installLaunched && pendingUpdate != null && packageManager.canRequestPackageInstalls()) installPendingUpdate()
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
        Column(Modifier.fillMaxSize().background(Cream).imePadding()) {
            Row(
                Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FriendlyRobotFace(
                    Modifier.size(58.dp),
                    mood = when {
                        busy -> RobotMood.THINKING
                        voiceSpeaking -> RobotMood.SPEAKING
                        voiceListening -> RobotMood.LISTENING
                        cameraOpen || photoBase64 != null -> RobotMood.CURIOUS
                        else -> RobotMood.FRIENDLY
                    }
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Milo", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("Twój przyjazny robot AI", fontSize = 13.sp, color = Color(0xFF698087))
                }
                TextButton(onClick = {
                    val transcript = messages.joinToString("\n\n") { message ->
                        "${if (message.role == "user") "Ty" else "Milo"}: ${message.text}"
                    }
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Rozmowa z Milo")
                        putExtra(Intent.EXTRA_TEXT, transcript)
                    }
                    context.startActivity(Intent.createChooser(share, "Przekaż rozmowę do aplikacji czatu"))
                }) { Text("📤", fontSize = 19.sp) }
                TextButton(onClick = {
                    voiceEnabled = !voiceEnabled
                    context.getSharedPreferences(PREFS, 0).edit().putBoolean(VOICE_ENABLED_KEY, voiceEnabled).apply()
                    if (!voiceEnabled) {
                        voiceController?.stopSpeaking()
                        voiceController?.resumeAfterResponse()
                    }
                }) { Text(if (voiceEnabled) "🔊" else "🔇", fontSize = 18.sp) }
                TextButton(onClick = { memoryDialog = true }) { Text("🧠", fontSize = 19.sp) }
                TextButton(onClick = {
                    chatGptAccount = ChatGptPlanClient.currentAccount(context)
                    settingsDialog = true
                }) { Text("⚙️", fontSize = 19.sp) }
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (chatGptAccount != null) Teal else Color(0xFFDA9D45)))
                Spacer(Modifier.width(6.dp))
                Text(if (chatGptAccount != null) "gotowy" else "zaloguj", fontSize = 12.sp, color = Color(0xFF698087))
            }

            updateMessage?.let { status ->
                Row(Modifier.fillMaxWidth().background(SoftMint).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(status, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (status.startsWith("Dostępna")) Button(onClick = onInstallUpdate) { Text("Aktualizuj") }
                }
            }

            if (cameraOpen) {
                CameraPanel(
                    memory = memories.joinToString("\n"),
                    onCapture = { encoded ->
                        photoBase64 = encoded
                        cameraOpen = false
                        notice = "Zdjęcie gotowe. Napisz, co mam na nim sprawdzić."
                    },
                    onClose = { cameraOpen = false },
                    onError = { notice = it }
                )
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                state = listState,
                contentPadding = PaddingValues(top = 18.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages) { message ->
                    val fromMilo = message.role == "assistant"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromMilo) Arrangement.Start else Arrangement.End, verticalAlignment = Alignment.Bottom) {
                        if (fromMilo) {
                            FriendlyRobotFace(
                                Modifier.size(30.dp),
                                mood = when {
                                    busy -> RobotMood.THINKING
                                    voiceSpeaking -> RobotMood.SPEAKING
                                    else -> RobotMood.FRIENDLY
                                }
                            )
                            Spacer(Modifier.width(7.dp))
                        }
                        Text(
                            text = message.text,
                            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp, 18.dp, if (fromMilo) 18.dp else 4.dp, if (fromMilo) 4.dp else 18.dp))
                                .background(if (fromMilo) Color.White else SoftMint).padding(horizontal = 15.dp, vertical = 12.dp),
                            color = Ink, fontSize = 15.sp, lineHeight = 21.sp
                        )
                    }
                }
                if (busy) item { Text("Milo zastanawia się…", color = Color(0xFF698087), modifier = Modifier.padding(start = 38.dp)) }
            }

            notice?.let { note ->
                Text(note, color = Color(0xFF526C71), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp))
            }
            if (photoBase64 != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📷 Zdjęcie dołączone", color = Teal, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { photoBase64 = null; notice = "Usunięto zdjęcie z wiadomości." }) { Text("Usuń") }
                }
            }
            if (liveConversation) {
                Text(
                    when {
                        busy -> "Milo układa odpowiedź…"
                        voiceSpeaking -> "Milo mówi…"
                        voiceListening -> "Milo słucha…"
                        else -> "Rozmowa na żywo aktywna"
                    },
                    color = Teal,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)
                )
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = {
                        notice = null
                        if (cameraAllowed) cameraOpen = true else permission.launch(Manifest.permission.CAMERA)
                    },
                    shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)
                ) { Text("📷", fontSize = 20.sp) }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = {
                        if (liveConversation) {
                            voiceController?.stopLiveConversation()
                            liveConversation = false
                            notice = "Zakończono rozmowę na żywo."
                        } else if (chatGptAccount == null) {
                            notice = "Najpierw połącz Milo z ChatGPT w ustawieniach."
                        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            liveConversation = voiceController?.startLiveConversation() == true
                            notice = if (liveConversation) "Rozmowa na żywo włączona. Android rozpoznaje mowę; Milo wysyła do ChatGPT rozpoznany tekst. Milo będzie słuchał, odpowiadał i znów słuchał." else "Nie udało się uruchomić rozmowy głosowej."
                        } else {
                            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !busy || liveConversation,
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(48.dp),
                    colors = if (liveConversation) ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFF7D9D3)) else ButtonDefaults.filledTonalButtonColors()
                ) { Text(if (liveConversation) "⏹" else "🎙️", fontSize = 19.sp) }
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(horizontal = 16.dp, vertical = 14.dp),
                    textStyle = TextStyle(color = Ink, fontSize = 15.sp),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) Text("Napisz do Milo…", color = Color(0xFF92A3A5), fontSize = 15.sp)
                        inner()
                    }
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = draft.isNotBlank() && !busy,
                    onClick = { submitMessage(draft) },
                    shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)
                ) { Text("➤", fontSize = 18.sp) }
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
                    if (connecting) Text("Oczekiwanie na logowanie w przeglądarce…", color = Teal)
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
                        scope.launch {
                            try {
                                chatGptAccount = ChatGptPlanClient.authorize(activity)
                                notice = "Milo połączył się z planem ChatGPT."
                            } catch (e: Exception) { notice = "Logowanie nie powiodło się: ${e.message ?: "błąd"}" }
                            finally { connecting = false }
                        }
                    }, enabled = !connecting) { Text(if (chatGptAccount == null) "Continue with ChatGPT" else "Połącz ponownie") }
                }
            },
            dismissButton = { TextButton(onClick = { settingsDialog = false }) { Text("Gotowe") } }
        )
    }
    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex + if (busy) 1 else 0)
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
    onCapture: (String) -> Unit,
    onClose: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var liveAnalysis by remember { mutableStateOf(false) }
    var liveDescription by remember { mutableStateOf<String?>(null) }
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
    LaunchedEffect(liveAnalysis) {
        while (liveAnalysis) {
            delay(5_000)
            val previewBitmap = previewView.bitmap
            val frame = previewBitmap?.let { bitmap ->
                withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output)
                        Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                    }
                }
            }
            if (frame == null) {
                liveDescription = "Nie udało się pobrać klatki z podglądu."
            } else {
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
            }
        }
    }
    Column(Modifier.fillMaxWidth().background(Color(0xFF17363C)).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Podgląd jest lokalny. W trybie na żywo obraz trafia do ChatGPT co 5 s i zużywa limit planu.", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Zamknij", color = Color(0xFF9EE8D5)) }
        }
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp)))
        liveDescription?.let { result ->
            Text(result, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        }
        OutlinedButton(
            onClick = { liveAnalysis = !liveAnalysis },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
        ) { Text(if (liveAnalysis) "Zatrzymaj analizę na żywo" else "Włącz analizę na żywo") }
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
