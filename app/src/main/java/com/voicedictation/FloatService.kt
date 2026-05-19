package com.voicedictation

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class FloatService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var floatView: View? = null
    private var speechLayer: View? = null
    private var isListening = false
    private var speechRecognizer: android.speech.SpeechRecognizer? = null
    private var currentTranscript = ""
    private var currentFocusedNode: android.view.accessibility.AccessibilityNodeInfo? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val DEEPSEEK_KEY = "sk-6fb9ffc6ebd747a0a14b5992adc5944e"

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(1, createNotification())
        showFloatBall()
    }

    // ===== 無障礙服務 =====
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 記錄當前焦點節點
            currentFocusedNode = event?.source
        }
    }

    override fun onInterrupt() {}

    // ===== 懸浮球 =====
    private fun showFloatBall() {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatView = inflater.inflate(R.layout.float_ball, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 400

        floatView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatView, params)
                        if (Math.abs(event.rawX - initialTouchX) > 10 ||
                            Math.abs(event.rawY - initialTouchY) > 10) {
                            isDragging = true
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            // 點擊 → 開啟語音層
                            showSpeechLayer()
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(floatView, params)
    }

    // ===== 語音層 =====
    private fun showSpeechLayer() {
        if (speechLayer != null) return

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        speechLayer = inflater.inflate(R.layout.speech_layer, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(speechLayer, params)
        startListening()
    }

    private fun startListening() {
        speechLayer?.findViewById<TextView>(R.id.statusText)?.text = "聆聽中..."
        speechLayer?.findViewById<TextView>(R.id.transcriptText)?.text = ""

        // 使用 Android 內建語音辨識
        speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "cmn-Hans-CN")
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(android.speech.RecognizerIntent.EXTRA_RESULTS)
                if (!matches.isNullOrEmpty()) {
                    currentTranscript = matches[0]
                    speechLayer?.findViewById<TextView>(R.id.transcriptText)?.text = currentTranscript
                    isListening = false
                    speechLayer?.findViewById<TextView>(R.id.statusText)?.text = "AI 潤飾中..."
                    polishAndPaste(currentTranscript)
                }
            }

            override fun onPartialResults(partialResults: android.os.Bundle?) {
                val matches = partialResults?.getStringArrayList(android.speech.RecognizerIntent.EXTRA_RESULTS)
                if (!matches.isNullOrEmpty()) {
                    speechLayer?.findViewById<TextView>(R.id.transcriptText)?.text = matches[0]
                }
            }

            override fun onError(error: Int) {
                val msg = when (error) {
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "沒聽到語音，請重試"
                    android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "說話超時，請重試"
                    android.speech.SpeechRecognizer.ERROR_NETWORK -> "網路錯誤"
                    android.speech.SpeechRecognizer.ERROR_AUDIO -> "麥克風錯誤"
                    else -> "語音辨識錯誤 ($error)"
                }
                speechLayer?.findViewById<TextView>(R.id.statusText)?.text = msg
                isListening = false
                scope.launch {
                    delay(1500)
                    dismissSpeechLayer()
                }
            }

            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() { isListening = true }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    // ===== DeepSeek 潤稿 =====
    private fun polishAndPaste(text: String) {
        scope.launch {
            try {
                val polished = withContext(Dispatchers.IO) {
                    callDeepSeek(text)
                }
                // 貼入當前焦點輸入框
                pasteToFocusedField(polished)
            } catch (e: Exception) {
                // 失敗就直接貼原文
                pasteToFocusedField(text)
            }
            delay(500)
            dismissSpeechLayer()
        }
    }

    private fun callDeepSeek(text: String): String {
        val url = URL("https://api.deepseek.com/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $DEEPSEEK_KEY")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        val body = JSONObject().apply {
            put("model", "deepseek-chat")
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是一個專業的文字潤稿助手。請將使用者的語音辨識結果潤飾為通順、流暢、符合語意的文字。保留原意，修正口語贅詞、錯字、標點。只輸出潤稿後的文字，不要加任何說明。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            })
            put("temperature", 0.3)
            put("max_tokens", 1024)
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
        val json = JSONObject(response)
        return json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }

    // ===== 貼入輸入框 =====
    private fun pasteToFocusedField(text: String) {
        try {
            // 透過無障礙服務貼上
            if (currentFocusedNode != null) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("text", text))

                // 嘗試直接 setText
                val args = android.os.Bundle().apply {
                    putCharSequence(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                }
                currentFocusedNode?.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args
                )
            }
        } catch (e: Exception) {
            // fallback: 複製到剪貼簿
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
        }
    }

    private fun dismissSpeechLayer() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        speechLayer?.let { windowManager.removeView(it) }
        speechLayer = null
        currentTranscript = ""
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissSpeechLayer()
        floatView?.let { windowManager.removeView(it) }
        floatView = null
        scope.cancel()
    }

    // ===== 通知 =====
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "voice_dictation",
                "語音輸入",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "voice_dictation")
            .setContentTitle("語音輸入")
            .setContentText("全域懸浮球已啟動")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
