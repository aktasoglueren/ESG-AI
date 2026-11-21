package com.esgmuhendislik.esgai

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
        var assistantName by remember {
            mutableStateOf(prefs.getString("assistant_name", "ESG") ?: "ESG")
        }
        var showNameDialog by remember { mutableStateOf(false) }
        var modelReady by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Başlamak için .litertlm model dosyanı seç.") }
        var input by remember { mutableStateOf("") }
        var speakReplies by remember { mutableStateOf(true) }
        var aiMode by remember { mutableStateOf(prefs.getString("ai_mode", "offline") ?: "offline") }
        var onlineConfigured by remember { mutableStateOf(HybridAiClient.loadConfig(this@MainActivity) != null) }
        var showOnlineSettings by remember { mutableStateOf(false) }
        var endpoint by remember { mutableStateOf("https://api.openai.com/v1/chat/completions") }
        var onlineModel by remember { mutableStateOf("gpt-4o-mini") }
        var apiKey by remember { mutableStateOf("") }
        var selectedImage by remember { mutableStateOf<ByteArray?>(null) }
        var selectedImageMime by remember { mutableStateOf("image/jpeg") }

        val lines = remember {
            mutableStateListOf<ChatLine>().apply {
                addAll(readHistory(prefs))
            }
        }
        val listState = rememberLazyListState()

        fun persistHistory() {
            val array = JSONArray()
            lines.forEach { line ->
                array.put(JSONObject().put("role", line.role).put("text", line.text))
            }
            prefs.edit().putString("chat_history", array.toString()).apply()
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

        val imagePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Fotoğraf okunamadı.")
                    selectedImage = bytes
                    selectedImageMime = contentResolver.getType(uri) ?: "image/jpeg"
                    status = "Fotoğraf eklendi. Çevrimiçi modda analiz edebilirsin."
                } catch (error: Exception) {
                    status = "Fotoğraf açılamadı: ${error.message}"
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
            else status = "Sesli giriş için mikrofon izni gerekiyor."
        }

        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFFFF650B),
                background = Color(0xFF071B2C),
                surface = Color(0xFF102B40)
            )
        ) {
            Column(
                Modifier.fillMaxSize()
                    .background(Color(0xFF071B2C))
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "E⚡G  ESG AI",
                            color = Color.White,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Kişisel yerel yapay zekâ",
                            color = Color(0xFFFF9A55),
                            fontSize = 13.sp
                        )
                    }
                    TextButton(onClick = { showNameDialog = true }) {
                        Text("Asistan: $assistantName", color = Color.White)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF102B40))) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(status, color = Color.White, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { modelPicker.launch(arrayOf("*/*")) }) {
                                Text(if (modelReady) "Modeli değiştir" else "Model seç")
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("Sesli yanıt", color = Color.White, fontSize = 12.sp)
                            Switch(
                                checked = speakReplies,
                                onCheckedChange = { speakReplies = it }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF102B40))) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text("Yapay zekâ modu", color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { aiMode = "offline"; prefs.edit().putString("ai_mode", aiMode).apply() },
                                colors = ButtonDefaults.buttonColors(containerColor = if (aiMode == "offline") Color(0xFFFF650B) else Color(0xFF29465A)),
                                modifier = Modifier.weight(1f)
                            ) { Text("Çevrimdışı") }
                            Button(
                                onClick = { aiMode = "online"; prefs.edit().putString("ai_mode", aiMode).apply() },
                                colors = ButtonDefaults.buttonColors(containerColor = if (aiMode == "online") Color(0xFFFF650B) else Color(0xFF29465A)),
                                modifier = Modifier.weight(1f)
                            ) { Text("Çevrimiçi") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (aiMode == "offline") { if (modelReady) "Yerel model hazır" else "Yerel model seçilmedi" }
                                else { if (onlineConfigured) "Çevrimiçi bağlantı ayarlı" else "API bağlantısı ayarlanmalı" },
                                color = Color.LightGray, modifier = Modifier.weight(1f), fontSize = 12.sp
                            )
                            TextButton(onClick = { showOnlineSettings = true }) {
                                Text("Bağlantı ayarları", color = Color(0xFFFF9A55))
                            }
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (lines.isEmpty()) {
                        item {
                            Text(
                                "Merhaba! Önce bir .litertlm model dosyası seç. " +
                                    "Model yüklenince çevrimdışı sohbet başlayacak.",
                                color = Color.LightGray,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    items(lines) { line ->
                        val isUser = line.role == "Sen"
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                        ) {
                            Surface(
                                color = if (isUser) Color(0xFF8D3A0B) else Color(0xFF173B54),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth(0.88f)
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        line.role,
                                        color = Color(0xFFFFB27C),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(line.text, color = Color.White)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (selectedImage != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📷 Fotoğraf eklendi", color = Color(0xFFFFB27C), modifier = Modifier.weight(1f))
                        TextButton(onClick = { selectedImage = null }) { Text("Kaldır") }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Mesajını yaz...") },
                        maxLines = 4
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) {
                        Text("📷", fontSize = 22.sp)
                    }
                    IconButton(onClick = {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            launchSpeech(speechLauncher)
                        } else {
                            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }) {
                        Text("🎙", fontSize = 22.sp)
                    }
                    Button(
                        enabled = !busy && input.isNotBlank() && (if (aiMode == "offline") modelReady else onlineConfigured),
                        onClick = {
                            val prompt = input.trim()
                            val imageForRequest = selectedImage
                            val mimeForRequest = selectedImageMime
                            input = ""
                            selectedImage = null
                            lines.add(ChatLine("Sen", prompt + if (imageForRequest != null) " [Fotoğraf]" else ""))
                            persistHistory()
                            busy = true
                            val replyIndex = lines.size
                            lines.add(ChatLine(assistantName, "Yanıt hazırlanıyor..."))
                            uiScope.launch {
                                try {
                                    val answer = if (aiMode == "online") {
                                        val config = checkNotNull(HybridAiClient.loadConfig(this@MainActivity))
                                        HybridAiClient.ask(config, prompt, imageForRequest, mimeForRequest)
                                    } else if (imageForRequest != null) {
                                        "Bu yerel model görüntü analizini desteklemiyor. Fotoğraf analizi için Çevrimiçi mod ve görüntü destekleyen model seç."
                                    } else {
                                        withContext(modelDispatcher) {
                                            val output = StringBuilder()
                                            checkNotNull(conversation)
                                                .sendMessageAsync(prompt)
                                                .collect { chunk -> output.append(chunk.toString()) }
                                            output.toString()
                                        }
                                    }
                                    lines[replyIndex] = ChatLine(
                                        assistantName,
                                        answer.ifBlank { "Yanıt alınamadı." }
                                    )
                                    persistHistory()
                                    if (speakReplies && ttsReady && answer.isNotBlank()) {
                                        tts?.speak(
                                            answer,
                                            TextToSpeech.QUEUE_FLUSH,
                                            null,
                                            "esg_ai_reply"
                                        )
                                    }
                                } catch (error: Exception) {
                                    lines[replyIndex] = ChatLine(
                                        "Sistem",
                                        "Hata: ${error.message ?: "Yanıt alınamadı."}"
                                    )
                                    persistHistory()
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    ) {
                        Text("Gönder")
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = {
                        lines.clear()
                        persistHistory()
                        uiScope.launch {
                            withContext(modelDispatcher) {
                                conversation?.close()
                                conversation = engine?.createConversation(
                                    ConversationConfig(
                                        systemInstruction = Contents.of(
                                            "Senin adın $assistantName. Türkçe konuş. Açık, sade ve kısa yanıt ver."
                                        )
                                    )
                                )
                            }
                        }
                    }) {
                        Text("Sohbeti temizle", color = Color.LightGray)
                    }
                    TextButton(onClick = {
                        val lastReply = lines.lastOrNull { it.role != "Sen" }?.text
                        if (!lastReply.isNullOrBlank() && ttsReady) {
                            tts?.speak(lastReply, TextToSpeech.QUEUE_FLUSH, null, "esg_ai_manual")
                        }
                    }) {
                        Text("Son yanıtı oku", color = Color.LightGray)
                    }
                }
            }
        }

        if (showNameDialog) {
            var newName by remember { mutableStateOf(assistantName) }
            AlertDialog(
                onDismissRequest = { showNameDialog = false },
                title = { Text("Asistan adını değiştir") },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Asistan adı") }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        assistantName = newName.trim().ifBlank { "ESG" }
                        prefs.edit().putString("assistant_name", assistantName).apply()
                        showNameDialog = false
                        status = "Ad kaydedildi. Yeni adın modele geçmesi için modeli yeniden yükle."
                    }) {
                        Text("Kaydet")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNameDialog = false }) {
                        Text("Vazgeç")
                    }
                }
            )
        }

        if (showOnlineSettings) {
            AlertDialog(
                onDismissRequest = { showOnlineSettings = false },
                title = { Text("Çevrimiçi AI bağlantısı") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Kendi servis anahtarını gir. Kullanım ücretleri sağlayıcıya ait olabilir.", fontSize = 12.sp)
                        OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("HTTPS API adresi") }, singleLine = true)
                        OutlinedTextField(value = onlineModel, onValueChange = { onlineModel = it }, label = { Text("Model adı") }, singleLine = true)
                        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API anahtarı") }, singleLine = true)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        try {
                            HybridAiClient.saveConfig(this@MainActivity, endpoint, onlineModel, apiKey)
                            onlineConfigured = true
                            apiKey = ""
                            showOnlineSettings = false
                            status = "Çevrimiçi bağlantı bilgileri güvenli şekilde kaydedildi."
                        } catch (error: Exception) {
                            status = "Ayar kaydedilemedi: ${error.message}"
                        }
                    }) { Text("Kaydet") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            HybridAiClient.clearConfig(this@MainActivity)
                            onlineConfigured = false
                            apiKey = ""
                            showOnlineSettings = false
                            status = "Çevrimiçi bağlantı kaldırıldı."
                        }) { Text("Bağlantıyı sil") }
                        TextButton(onClick = { showOnlineSettings = false }) { Text("Kapat") }
                    }
                }
            )
        }
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
