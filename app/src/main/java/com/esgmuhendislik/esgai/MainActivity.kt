package com.esgmuhendislik.esgai

import android.Manifest
import android.content.Intent
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
                modifier = Modifier.fillMaxSize()
                    .background(Color(0xFF07131F))
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(17.dp),
                        color = Color(0xFFFF650B),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("E⚡G", color = Color(0xFF07131F), fontSize = 17.sp, fontWeight = FontWeight.Black)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("ESG AI", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                        Text("INTELLIGENCE • YOUR WORKSPACE", color = Color(0xFF8FA7B9), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (modelReady) Color(0xFF123B32) else Color(0xFF263442)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(7.dp).background(if (modelReady) Color(0xFF42D6A0) else Color(0xFFFFA24A), RoundedCornerShape(50)))
                            Spacer(Modifier.width(7.dp))
                            Text(if (modelReady) "HAZIR" else "BEKLEMEDE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(25.dp),
                    color = Color(0xFF10283B)
                ) {
                    Column(Modifier.padding(19.dp)) {
                        Text("KİŞİSEL YAPAY ZEKÂ ALANIN", color = Color(0xFFFF9857), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Fikirlerini\nharekete geçir.", color = Color.White, fontSize = 27.sp, lineHeight = 31.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(7.dp))
                        Text("Sor, üret, keşfet. ESG AI yanında.", color = Color(0xFFB4C4D1), fontSize = 13.sp)
                        Spacer(Modifier.height(15.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { modelPicker.launch(arrayOf("*/*")) },
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 17.dp, vertical = 11.dp)
                            ) {
                                Text(if (modelReady) "Modeli değiştir" else "Modelini yükle", fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (modelReady) "YEREL MOTOR AKTİF" else "YEREL MOTOR", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Text(if (modelReady) "Cihazında çalışıyor" else "Başlamak için model seç", color = Color(0xFF91A9B9), fontSize = 10.sp)
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    shape = RoundedCornerShape(23.dp),
                    color = Color(0xFF0C2030)
                ) {
                    Column(Modifier.fillMaxSize().padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("SOHBET ALANI", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                                Text("Asistanınla konuşmaya başla", color = Color(0xFF829BAE), fontSize = 10.sp)
                            }
                            Text("✦  $assistantName", color = Color(0xFFFF9857), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            TextButton(onClick = { showNameDialog = true }) {
                                Text("Düzenle", color = Color(0xFF9CB1C1), fontSize = 10.sp)
                            }
                        }
                        HorizontalDivider(color = Color(0xFF20394B), thickness = 1.dp)
                        Spacer(Modifier.height(9.dp))
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (lines.isEmpty()) {
                                item {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(22.dp),
                                            color = Color(0xFF172F42),
                                            modifier = Modifier.size(62.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text("✦", color = Color(0xFFFF8B43), fontSize = 30.sp)
                                            }
                                        }
                                        Spacer(Modifier.height(14.dp))
                                        Text("Nasıl yardımcı olabilirim?", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "Sohbete başlamak için önce yerel modelini yükle.",
                                            color = Color(0xFF91A8B8),
                                            fontSize = 11.sp
                                        )
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
                                        color = if (isUser) Color(0xFF71350F) else Color(0xFF183247),
                                        shape = RoundedCornerShape(
                                            topStart = 17.dp,
                                            topEnd = 17.dp,
                                            bottomStart = if (isUser) 17.dp else 5.dp,
                                            bottomEnd = if (isUser) 5.dp else 17.dp
                                        ),
                                        modifier = Modifier.fillMaxWidth(0.88f)
                                    ) {
                                        Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
                                            Text(
                                                line.role.uppercase(Locale.ROOT),
                                                color = if (isUser) Color(0xFFFFC08D) else Color(0xFFFF9857),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(line.text, color = Color(0xFFF4F7FA), fontSize = 13.sp, lineHeight = 19.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(19.dp),
                    color = Color(0xFF132A3C)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 10.dp, end = 7.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("ESG AI'ya mesaj yaz...", fontSize = 12.sp) },
                            maxLines = 3,
                            shape = RoundedCornerShape(14.dp)
                        )
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
                            Text("🎙", fontSize = 21.sp)
                        }
                        Button(
                            enabled = modelReady && !busy && input.isNotBlank(),
                            onClick = {
                                val prompt = input.trim()
                                input = ""
                                lines.add(ChatLine("Sen", prompt))
                                persistHistory()
                                busy = true
                                val replyIndex = lines.size
                                lines.add(ChatLine(assistantName, "Yanıt hazırlanıyor..."))
                                uiScope.launch {
                                    try {
                                        val answer = withContext(modelDispatcher) {
                                            val output = StringBuilder()
                                            checkNotNull(conversation)
                                                .sendMessageAsync(prompt)
                                                .collect { chunk -> output.append(chunk.toString()) }
                                            output.toString()
                                        }
                                        lines[replyIndex] = ChatLine(assistantName, answer.ifBlank { "Yanıt alınamadı." })
                                        persistHistory()
                                        if (speakReplies && ttsReady && answer.isNotBlank()) {
                                            tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "esg_ai_reply")
                                        }
                                    } catch (error: Exception) {
                                        lines[replyIndex] = ChatLine("Sistem", "Hata: ${error.message ?: "Yanıt alınamadı."}")
                                        persistHistory()
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            shape = RoundedCornerShape(13.dp),
                            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 12.dp)
                        ) {
                            Text("➤", fontSize = 17.sp)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
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
                        Text("⌫  Sohbeti temizle", color = Color(0xFF91A8B8), fontSize = 10.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sesli yanıt", color = Color(0xFF91A8B8), fontSize = 10.sp)
                        Switch(
                            checked = speakReplies,
                            onCheckedChange = { speakReplies = it }
                        )
                        TextButton(onClick = {
                            val lastReply = lines.lastOrNull { it.role != "Sen" }?.text
                            if (!lastReply.isNullOrBlank() && ttsReady) {
                                tts?.speak(lastReply, TextToSpeech.QUEUE_FLUSH, null, "esg_ai_manual")
                            }
                        }) {
                            Text("Oku", color = Color(0xFFFF9857), fontSize = 10.sp)
                        }
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
