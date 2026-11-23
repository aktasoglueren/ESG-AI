package com.esgmuhendislik.esgai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

private data class ChatLine(val role: String, val text: String)
private data class ChatSession(val id: String, val title: String, val lines: List<ChatLine>)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val modelDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Engine and Conversation are created, used and closed on modelDispatcher.
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        setContent { ESGApp() }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale("tr", "TR")
        }
    }

    @Composable
    private fun ESGApp() {
        val prefs = remember { getSharedPreferences("esg_ai", MODE_PRIVATE) }
        val assistantName = "VOLTARA"
        var showChatsDialog by remember { mutableStateOf(false) }
        var isDarkTheme by remember { mutableStateOf(prefs.getBoolean("dark_theme", true)) }
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val drawerScope = rememberCoroutineScope()
        var modelReady by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Başlamak için .litertlm model dosyanı seç.") }
        var input by remember { mutableStateOf("") }
        var speakReplies by remember { mutableStateOf(true) }
        var showOnlineDialog by remember { mutableStateOf(false) }
        var showEditor by remember { mutableStateOf(false) }
        var useOnline by remember { mutableStateOf(false) }
        var onlineConfig by remember { mutableStateOf(HybridAiClient.loadConfig(this@MainActivity)) }
        var endpointInput by remember { mutableStateOf(onlineConfig?.endpoint ?: "https://api.openai.com/v1/chat/completions") }
        var modelInput by remember { mutableStateOf(onlineConfig?.model ?: "") }
        var apiKeyInput by remember { mutableStateOf("") }
        var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }

        val sessions = remember { mutableStateListOf<ChatSession>().apply { addAll(readSessions(prefs)) } }
        var activeChatId by remember {
            mutableStateOf(prefs.getString("active_chat_id", null) ?: sessions.firstOrNull()?.id ?: "chat-${System.currentTimeMillis()}")
        }
        val startingSession = sessions.firstOrNull { it.id == activeChatId }
        val lines = remember { mutableStateListOf<ChatLine>().apply { addAll(startingSession?.lines ?: readHistory(prefs)) } }
        if (sessions.isEmpty()) sessions.add(ChatSession(activeChatId, "Yeni sohbet", lines.toList()))
        val listState = rememberLazyListState()

        LaunchedEffect(lines.size, lines.lastOrNull()?.text) {
            if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
        }

        fun persistHistory() {
            val title = lines.firstOrNull { it.role == "Sen" }?.text?.replace("\\n", " ")?.take(34)?.ifBlank { "Yeni sohbet" } ?: "Yeni sohbet"
            val index = sessions.indexOfFirst { it.id == activeChatId }
            val updated = ChatSession(activeChatId, title, lines.toList())
            if (index >= 0) sessions[index] = updated else sessions.add(0, updated)
            val all = JSONArray()
            sessions.forEach { session ->
                val messages = JSONArray()
                session.lines.forEach { line -> messages.put(JSONObject().put("role", line.role).put("text", line.text)) }
                all.put(JSONObject().put("id", session.id).put("title", session.title).put("lines", messages))
            }
            val legacy = JSONArray()
            lines.forEach { line -> legacy.put(JSONObject().put("role", line.role).put("text", line.text)) }
            prefs.edit().putString("chat_sessions", all.toString()).putString("active_chat_id", activeChatId)
                .putString("chat_history", legacy.toString()).putString("assistant_name", assistantName).apply()
        }

        fun restoreConversation(history: List<ChatLine>) {
            uiScope.launch {
                withContext(modelDispatcher) {
                    conversation?.close()
                    conversation = engine?.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of("Senin adın VOLTARA. Türkçe konuş. Açık, sade ve kısa yanıt ver. Kullanıcıya yardımcı ol. Bilmediğin bilgiyi uydurma."),
                            initialMessages = history.mapNotNull { line ->
                                when (line.role) {
                                    "Sen" -> Message.user(line.text)
                                    assistantName -> Message.model(line.text)
                                    else -> null
                                }
                            }
                        )
                    )
                }
            }
        }

        fun createNewChat() {
            if (busy) return
            persistHistory()
            activeChatId = "chat-${System.currentTimeMillis()}"
            lines.clear()
            sessions.add(0, ChatSession(activeChatId, "Yeni sohbet", emptyList()))
            persistHistory()
            restoreConversation(emptyList())
        }

        fun openChat(session: ChatSession) {
            if (busy || session.id == activeChatId) return
            persistHistory()
            activeChatId = session.id
            lines.clear()
            lines.addAll(session.lines)
            persistHistory()
            restoreConversation(session.lines)
            showChatsDialog = false
        }

        fun launchSpeech(launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "ESG AI ile konuş")
            }
            if (SpeechRecognizer.isRecognitionAvailable(this@MainActivity)) {
                launcher.launch(intent)
            } else {
                status = "Telefonda konuşma tanıma hizmeti bulunamadı."
            }
        }

        val modelPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                uiScope.launch {
                    status = "Model dosyası uygulama hafızasına aktarılıyor..."
                    modelReady = false
                    try {
                        val modelFile = File(filesDir, "assistant.litertlm")
                        contentResolver.openInputStream(uri)?.use { source ->
                            modelFile.outputStream().use { target -> source.copyTo(target) }
                        } ?: error("Seçilen dosya okunamadı.")

                        status = "Model yükleniyor. İlk açılış biraz sürebilir."
                        withContext(modelDispatcher) {
                            conversation?.close()
                            conversation = null
                            engine?.close()
                            engine = null

                            val newEngine = Engine(
                                EngineConfig(
                                    modelPath = modelFile.absolutePath,
                                    backend = Backend.CPU(),
                                    cacheDir = cacheDir.absolutePath
                                )
                            )
                            try {
                                newEngine.initialize()
                                val historyMessages = lines.mapNotNull { line ->
                                    when (line.role) {
                                        "Sen" -> Message.user(line.text)
                                        assistantName -> Message.model(line.text)
                                        else -> null
                                    }
                                }
                                val newConversation = newEngine.createConversation(
                                    ConversationConfig(
                                        systemInstruction = Contents.of(
                                            "Senin adın $assistantName. Türkçe konuş. " +
                                            "Açık, sade ve kısa yanıt ver. Kullanıcıya doğrudan yardımcı ol. " +
                                            "Bilmediğin bilgiyi uydurma."
                                        ),
                                        initialMessages = historyMessages
                                    )
                                )
                                engine = newEngine
                                conversation = newConversation
                            } catch (error: Exception) {
                                // Do not close an Engine whose initialize() failed.
                                throw error
                            }
                        }
                        modelReady = true
                        status = "Hazır. Model telefonda çalışıyor."
                    } catch (error: Exception) {
                        status = "Model yüklenemedi: ${error.message ?: "Bilinmeyen hata"}"
                    }
                }
            }
        }

        // Reuse the model already copied into app-private filesDir.
        // This avoids asking the user to pick the same model on every launch.
        LaunchedEffect(Unit) {
            val savedModelFile = File(filesDir, "assistant.litertlm")
            if (savedModelFile.isFile && savedModelFile.length() > 0L) {
                busy = true
                status = "Kayıtlı model otomatik yükleniyor..."
                try {
                    withContext(modelDispatcher) {
                        conversation?.close()
                        conversation = null
                        engine?.close()
                        engine = null

                        val restoredEngine = Engine(
                            EngineConfig(
                                modelPath = savedModelFile.absolutePath,
                                backend = Backend.CPU(),
                                cacheDir = cacheDir.absolutePath
                            )
                        )
                        restoredEngine.initialize()
                        val historyMessages = lines.mapNotNull { line ->
                            when (line.role) {
                                "Sen" -> Message.user(line.text)
                                assistantName -> Message.model(line.text)
                                else -> null
                            }
                        }
                        val restoredConversation = restoredEngine.createConversation(
                            ConversationConfig(
                                systemInstruction = Contents.of(
                                    "Senin adın $assistantName. Türkçe konuş. " +
                                        "Açık, sade ve kısa yanıt ver. Kullanıcıya doğrudan yardımcı ol. " +
                                        "Bilmediğin bilgiyi uydurma."
                                ),
                                initialMessages = historyMessages
                            )
                        )
                        engine = restoredEngine
                        conversation = restoredConversation
                    }
                    modelReady = true
                    status = "Hazır. Kayıtlı model otomatik yüklendi."
                } catch (error: Exception) {
                    modelReady = false
                    status = "Kayıtlı model açılamadı: ${error.message ?: "Bilinmeyen hata"}. Modeli yeniden seçebilirsin."
                } finally {
                    busy = false
                }
            } else {
                status = "Başlamak için .litertlm model dosyanı bir kez seç."
            }
        }

        val imagePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) {
                try {
                    val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    if (bitmap != null) {
                        selectedBitmap = bitmap
                        originalBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                        showEditor = true
                    } else status = "Görsel açılamadı."
                } catch (error: Exception) {
                    status = "Görsel açılamadı: ${error.message}"
                }
            }
        }

        val speechLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) input = spoken
        }

        val microphonePermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) launchSpeech(speechLauncher)
            else status = "Sesli giriş için mikrofon izni gerekli."
        }

        val bg = if (isDarkTheme) Color(0xFF171717) else Color(0xFFF7F7F8)
        val panel = if (isDarkTheme) Color(0xFF212121) else Color(0xFFFFFFFF)
        val panelAlt = if (isDarkTheme) Color(0xFF2A2A2A) else Color(0xFFF0F1F3)
        val fg = if (isDarkTheme) Color(0xFFF5F5F5) else Color(0xFF202124)
        val muted = if (isDarkTheme) Color(0xFFA1A1AA) else Color(0xFF686B73)
        val accent = Color(0xFF4D8DFF)

        MaterialTheme(
            colorScheme = if (isDarkTheme) darkColorScheme(
                primary = accent, background = bg, surface = panel, onBackground = fg, onSurface = fg
            ) else lightColorScheme(
                primary = Color(0xFF2563EB), background = bg, surface = panel, onBackground = fg, onSurface = fg
            )
        ) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = panel,
                        drawerContentColor = fg
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(painter = painterResource(R.drawable.ic_esg), contentDescription = "ESG AI", modifier = Modifier.size(44.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("ESG AI", color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text("VOLTARA", color = muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        HorizontalDivider(color = muted.copy(alpha = 0.2f))
                        NavigationDrawerItem(
                            label = { Text("Yeni sohbet") },
                            selected = false,
                            icon = { Text("+", fontSize = 20.sp, color = accent) },
                            onClick = { createNewChat(); drawerScope.launch { drawerState.close() } },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            label = { Text("Sohbet geçmişi") },
                            selected = false,
                            icon = { Text("◷", fontSize = 19.sp, color = muted) },
                            onClick = { showChatsDialog = true; drawerScope.launch { drawerState.close() } },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            label = { Text("Model seçimi") },
                            selected = false,
                            icon = { Text("✦", fontSize = 18.sp, color = accent) },
                            onClick = { modelPicker.launch(arrayOf("*/*")); drawerScope.launch { drawerState.close() } },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            label = { Text("Çevrimiçi bağlantı") },
                            selected = false,
                            icon = { Text("◎", fontSize = 18.sp, color = muted) },
                            onClick = { showOnlineDialog = true; drawerScope.launch { drawerState.close() } },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        HorizontalDivider(color = muted.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 8.dp))
                        NavigationDrawerItem(
                            label = { Text(if (isDarkTheme) "Açık temaya geç" else "Koyu temaya geç") },
                            selected = false,
                            icon = { Text(if (isDarkTheme) "☀" else "☾", fontSize = 18.sp, color = accent) },
                            onClick = {
                                isDarkTheme = !isDarkTheme
                                prefs.edit().putBoolean("dark_theme", isDarkTheme).apply()
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        Spacer(Modifier.weight(1f))
                        Text("Yapay zekân, her an yanında.", color = muted, fontSize = 11.sp, modifier = Modifier.padding(20.dp))
                    }
                }
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().background(bg).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { drawerScope.launch { drawerState.open() } }) {
                            Text("☰", color = fg, fontSize = 23.sp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("ESG AI", color = fg, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text("Yapay zekân, her an yanında.", color = muted, fontSize = 10.sp)
                        }
                        Surface(shape = RoundedCornerShape(50), color = if (modelReady) Color(0xFFDCFCE7) else panelAlt) {
                            Text(if (modelReady) "● HAZIR" else "● BEKLEMEDE",
                                color = if (modelReady) Color(0xFF16834A) else muted,
                                fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                        }
                    }

                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = panel) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(painter = painterResource(R.drawable.ic_voltara), contentDescription = "VOLTARA", modifier = Modifier.size(34.dp))
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text("VOLTARA", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(if (useOnline && onlineConfig != null) "Çevrimiçi model" else if (modelReady) "Cihazdaki model" else "Model seçilmedi",
                                    color = muted, fontSize = 10.sp)
                            }
                            TextButton(onClick = { showOnlineDialog = true }) { Text("Çevrimiçi", fontSize = 10.sp) }
                            TextButton(onClick = { modelPicker.launch(arrayOf("*/*")) }) { Text("Model seç", fontSize = 10.sp) }
                        }
                    }

                    Surface(modifier = Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = panel) {
                        Column(Modifier.fillMaxSize().padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (lines.isEmpty()) "Nasıl yardımcı olabilirim?" else "VOLTARA", color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    Text("Sor, üret, keşfet.", color = muted, fontSize = 10.sp)
                                }
                                TextButton(onClick = { createNewChat() }) { Text("+ Yeni", color = accent, fontWeight = FontWeight.Bold) }
                            }
                            HorizontalDivider(color = muted.copy(alpha = 0.18f))
                            Spacer(Modifier.height(8.dp))
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                state = listState,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (lines.isEmpty()) {
                                    item {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(top = 34.dp, bottom = 20.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Surface(shape = RoundedCornerShape(20.dp), color = panelAlt, modifier = Modifier.size(62.dp)) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text("✦", color = accent, fontSize = 30.sp)
                                                }
                                            }
                                            Spacer(Modifier.height(15.dp))
                                            Text("Bugün ne yapmak istersin?", color = fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                            Spacer(Modifier.height(6.dp))
                                            Text("Bir soru sorabilir, görsel ekleyebilir veya dosyanı inceletebilirsin.",
                                                color = muted, fontSize = 11.sp)
                                        }
                                    }
                                }
                                items(lines) { line ->
                                    val isUser = line.role == "Sen"
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                                    ) {
                                        Surface(
                                            color = if (isUser) panelAlt else bg,
                                            shape = RoundedCornerShape(18.dp),
                                            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 0.94f)
                                        ) {
                                            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                                if (!isUser) Text("VOLTARA", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                Text(line.text, color = fg, fontSize = 14.sp, lineHeight = 21.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = panel) {
                        Row(
                            modifier = Modifier.padding(start = 8.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("VOLTARA'ya mesaj yaz...", fontSize = 12.sp) },
                                maxLines = 4,
                                shape = RoundedCornerShape(17.dp)
                            )
                            IconButton(onClick = { imagePicker.launch("image/*") }) { Text("+", color = accent, fontSize = 24.sp) }
                            IconButton(onClick = {
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    launchSpeech(speechLauncher)
                                } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }) { Text("🎙", fontSize = 20.sp) }
                            Button(
                                enabled = (modelReady || (useOnline && onlineConfig != null)) && !busy && (input.isNotBlank() || selectedBitmap != null),
                                onClick = {
                                    val prompt = input.trim().ifBlank { "Bu görseli incele ve yardımcı ol." }
                                    val imageForRequest = selectedBitmap
                                    input = ""
                                    selectedBitmap = null
                                    lines.add(ChatLine("Sen", if (imageForRequest != null) "📷 Görsel eklendi. $prompt" else prompt))
                                    persistHistory()
                                    busy = true
                                    val replyIndex = lines.size
                                    lines.add(ChatLine(assistantName, "Yanıt hazırlanıyor..."))
                                    uiScope.launch {
                                        try {
                                            val answer = if (useOnline && onlineConfig != null) {
                                                val bytes = imageForRequest?.let { bitmap ->
                                                    java.io.ByteArrayOutputStream().use { out ->
                                                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                                        out.toByteArray()
                                                    }
                                                }
                                                HybridAiClient.ask(onlineConfig!!, prompt, bytes)
                                            } else {
                                                require(imageForRequest == null) { "Görsel analizi için çevrimiçi modu etkinleştirip API bağlantısı kur." }
                                                withContext(modelDispatcher) {
                                                    val output = StringBuilder()
                                                    checkNotNull(conversation).sendMessageAsync(prompt).collect { chunk -> output.append(chunk.toString()) }
                                                    output.toString()
                                                }
                                            }
                                            lines[replyIndex] = ChatLine(assistantName, answer.ifBlank { "Yanıt alınamadı." })
                                            persistHistory()
                                            if (speakReplies && ttsReady && answer.isNotBlank()) {
                                                tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "esg_ai_reply")
                                            }
                                        } catch (error: Exception) {
                                            lines[replyIndex] = ChatLine("Sistem", "Hata: ${error.message ?: "Yanıt alınamadı."}")
                                            persistHistory()
                                        } finally { busy = false }
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 12.dp)
                            ) { Text("↑", fontSize = 20.sp) }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { createNewChat() }) { Text("Yeni sohbet", color = muted, fontSize = 10.sp) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Sesli yanıt", color = muted, fontSize = 10.sp)
                            Switch(checked = speakReplies, onCheckedChange = { speakReplies = it })
                            TextButton(onClick = {
                                val lastReply = lines.lastOrNull { it.role != "Sen" }?.text
                                if (!lastReply.isNullOrBlank() && ttsReady) tts?.speak(lastReply, TextToSpeech.QUEUE_FLUSH, null, "esg_ai_manual")
                            }) { Text("Oku", color = accent, fontSize = 10.sp) }
                        }
                    }
                }
            }
        }

        if (showChatsDialog) {
            AlertDialog(
                onDismissRequest = { showChatsDialog = false },
                title = { Text("Sohbet geçmişi") },
                text = {
                    Column(modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        sessions.forEach { session ->
                            TextButton(onClick = { openChat(session) }, modifier = Modifier.fillMaxWidth()) {
                                Text((if (session.id == activeChatId) "●  " else "") + session.title,
                                    color = if (session.id == activeChatId) Color(0xFFFF9857) else Color.White, maxLines = 2)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showChatsDialog = false }) { Text("Kapat") } },
                dismissButton = { TextButton(onClick = { showChatsDialog = false; createNewChat() }) { Text("Yeni sohbet") } }
            )
        }

        if (showOnlineDialog) {
            AlertDialog(
                onDismissRequest = { showOnlineDialog = false },
                title = { Text("Çevrimiçi yapay zekâ bağlantısı") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("OpenAI uyumlu HTTPS servis bilgilerini gir. API anahtarı cihazında şifreli saklanır.", fontSize = 12.sp)
                        OutlinedTextField(value = endpointInput, onValueChange = { endpointInput = it }, label = { Text("API adresi") }, singleLine = true)
                        OutlinedTextField(value = modelInput, onValueChange = { modelInput = it }, label = { Text("Model adı") }, singleLine = true)
                        OutlinedTextField(value = apiKeyInput, onValueChange = { apiKeyInput = it }, label = { Text("API anahtarı") }, singleLine = true)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = useOnline, onCheckedChange = { useOnline = it }, enabled = onlineConfig != null || modelInput.isNotBlank())
                            Text("Çevrimiçi modu kullan", fontSize = 12.sp)
                        }
                        if (onlineConfig != null) Text("Bağlantı ayarlı. Yeni anahtar girerek değiştirebilirsin.", color = Color(0xFF2E9D73), fontSize = 11.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        try {
                            if (apiKeyInput.isNotBlank()) {
                                HybridAiClient.saveConfig(this@MainActivity, endpointInput.trim(), modelInput.trim(), apiKeyInput.trim())
                                onlineConfig = HybridAiClient.loadConfig(this@MainActivity)
                                apiKeyInput = ""
                            }
                            if (onlineConfig == null) error("Önce API adresi, model ve API anahtarı kaydet.")
                            useOnline = true
                            showOnlineDialog = false
                            status = "Çevrimiçi bağlantı ayarlandı. İlk mesajında servis bağlantısı sınanacak."
                        } catch (error: Exception) { status = error.message ?: "Ayar kaydedilemedi." }
                    }) { Text("Kaydet ve etkinleştir") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        useOnline = false
                        showOnlineDialog = false
                    }) { Text("Kapat") }
                }
            )
        }

        if (showEditor && selectedBitmap != null) {
            AlertDialog(
                onDismissRequest = { showEditor = false },
                title = { Text("Görsel Atölyesi") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(
                            bitmap = selectedBitmap!!.asImageBitmap(),
                            contentDescription = "Düzenlenen fotoğraf",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                        )
                        Text("Fotoğrafı düzenle ve cihazına kaydet.", fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = {
                                selectedBitmap = transformBitmap(selectedBitmap!!, rotate = 90f)
                            }) { Text("Döndür") }
                            OutlinedButton(onClick = {
                                selectedBitmap = transformBitmap(selectedBitmap!!, grayscale = true)
                            }) { Text("Siyah-beyaz") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = {
                                selectedBitmap = transformBitmap(selectedBitmap!!, brightness = 1.15f)
                            }) { Text("Parlat") }
                            OutlinedButton(onClick = {
                                originalBitmap?.let { selectedBitmap = it.copy(Bitmap.Config.ARGB_8888, true) }
                            }) { Text("Sıfırla") }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        try {
                            saveEditedBitmap(selectedBitmap!!)
                            status = "Düzenlenen görsel Galeri'ye kaydedildi."
                            showEditor = false
                        } catch (error: Exception) { status = "Kaydedilemedi: ${error.message}" }
                    }) { Text("Galeriye kaydet") }
                },
                dismissButton = {
                    TextButton(onClick = { showEditor = false }) { Text("Kapat") }
                }
            )
        }

    }

    private fun transformBitmap(source: Bitmap, rotate: Float = 0f, grayscale: Boolean = false, brightness: Float = 1f): Bitmap {
        val matrix = Matrix().apply { postRotate(rotate) }
        val rotated = if (rotate != 0f) Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true) else source
        val output = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val colorMatrix = ColorMatrix()
        if (grayscale) colorMatrix.setSaturation(0f)
        val values = floatArrayOf(
            brightness,0f,0f,0f,0f,
            0f,brightness,0f,0f,0f,
            0f,0f,brightness,0f,0f,
            0f,0f,0f,1f,0f
        )
        val brightMatrix = ColorMatrix(values)
        if (grayscale) colorMatrix.postConcat(brightMatrix) else colorMatrix.set(brightMatrix)
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        Canvas(output).drawBitmap(rotated, 0f, 0f, paint)
        return output
    }

    private fun saveEditedBitmap(bitmap: Bitmap) {
        val name = "ESG_AI_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ESG AI")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Galeri kaydı oluşturulamadı.")
        contentResolver.openOutputStream(uri)?.use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 94, stream)) error("Görsel sıkıştırılamadı.")
        } ?: error("Dosya akışı açılamadı.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
    }


    private fun readSessions(prefs: android.content.SharedPreferences): List<ChatSession> {
        return try {
            val raw = prefs.getString("chat_sessions", "[]") ?: "[]"
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val title = item.optString("title", "Yeni sohbet")
                val messages = item.optJSONArray("lines") ?: JSONArray()
                val history = (0 until messages.length()).mapNotNull { messageIndex ->
                    val message = messages.optJSONObject(messageIndex) ?: return@mapNotNull null
                    val role = message.optString("role")
                    val text = message.optString("text")
                    if (role.isNotBlank() && text.isNotBlank()) ChatLine(role, text) else null
                }
                if (id.isNotBlank()) ChatSession(id, title, history) else null
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun readHistory(prefs: android.content.SharedPreferences): List<ChatLine> {
        return try {
            val raw = prefs.getString("chat_history", "[]") ?: "[]"
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val role = item.optString("role")
                val text = item.optString("text")
                if (role.isNotBlank() && text.isNotBlank()) ChatLine(role, text) else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        tts?.stop()
        tts?.shutdown()
        runBlocking {
            withContext(modelDispatcher) {
                conversation?.close()
                conversation = null
                engine?.close()
                engine = null
            }
        }
        modelDispatcher.close()
        super.onDestroy()
    }
}
