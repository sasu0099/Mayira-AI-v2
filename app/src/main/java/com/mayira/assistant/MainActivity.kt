package com.mayira.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var web: WebView
    private val prefs by lazy { getSharedPreferences("mayira_secure", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private val exportCode = 801
    private val importCode = 802

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Mayira")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
    }

    inner class Bridge {
        @JavascriptInterface fun getApiKey(): String = prefs.getString("gemini_key", "") ?: ""
        @JavascriptInterface fun setApiKey(key: String) { prefs.edit().putString("gemini_key", key.trim()).apply() }
        @JavascriptInterface fun getAssistantName(): String = prefs.getString("assistant_name", "Mayira") ?: "Mayira"
        @JavascriptInterface fun setAssistantName(name: String) { prefs.edit().putString("assistant_name", name.take(32)).apply() }

        @JavascriptInterface fun saveMemory(json: String) {
            if (json.length <= 2_500_000) prefs.edit().putString("memory_json", json).apply()
        }
        @JavascriptInterface fun loadMemory(): String =
            prefs.getString("memory_json", "{\"schemaVersion\":1,\"history\":[]}") ?: "{\"schemaVersion\":1,\"history\":[]}"

        @JavascriptInterface fun speak(text: String) {
            runOnUiThread { tts?.speak(text.take(3000), TextToSpeech.QUEUE_FLUSH, null, "mayira-tts") }
        }
        @JavascriptInterface fun stopSpeaking() { runOnUiThread { tts?.stop() } }

        @JavascriptInterface fun listen() {
            runOnUiThread {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 803)
                    return@runOnUiThread
                }
                startListening()
            }
        }

        @JavascriptInterface fun ask(prompt: String, callbackId: String) {
            val key = getApiKey()
            if (key.isBlank()) {
                deliver(callbackId, "Gemini API key সেট করা নেই। Settings থেকে API key যোগ করুন।", true)
                return
            }
            executor.execute {
                try {
                    val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" +
                        URLEncoder.encode(key, "UTF-8"))
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 20000
                        readTimeout = 60000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                    val system = "You are Mayira, a helpful personal AI assistant. You are also a coding expert. " +
                        "When debugging, explain the cause, then provide a corrected complete code or precise patch and exact placement. " +
                        "For messages, draft natural replies but never claim to send them. Suggest useful project improvements occasionally, not repeatedly. " +
                        "Before changes to the user's projects, explain the proposed changes and ask permission. " +
                        "Use respectful Bangla when the user writes Bangla. Be mildly playful but never insulting, shaming, threatening, or pressuring."
                    val body = JSONObject().put("system_instruction",
                        JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", system))))
                        .put("contents", org.json.JSONArray().put(JSONObject().put("parts",
                            org.json.JSONArray().put(JSONObject().put("text", prompt)))))
                        .put("generationConfig", JSONObject().put("temperature", 0.7))
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val raw = stream.bufferedReader().use { it.readText() }
                    val result = if (code in 200..299) {
                        val obj = JSONObject(raw)
                        obj.getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                    } else "Gemini API error ($code): ${raw.take(700)}"
                    deliver(callbackId, result, code !in 200..299)
                    conn.disconnect()
                } catch (e: Exception) {
                    deliver(callbackId, "সংযোগে সমস্যা হয়েছে: ${e.message ?: "Unknown error"}", true)
                }
            }
        }

        @JavascriptInterface fun exportBackup(json: String) {
            if (json.length > 2_500_000) return
            prefs.edit().putString("pending_export", json).apply()
            runOnUiThread {
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "Mayira-Delta3-Backup.json")
                }
                startActivityForResult(i, exportCode)
            }
        }
        @JavascriptInterface fun importBackup() {
            runOnUiThread {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                }
                startActivityForResult(i, importCode)
            }
        }
        @JavascriptInterface fun checkUpdate(ownerRepo: String) {
            executor.execute {
                try {
                    val c = URL("https://api.github.com/repos/$ownerRepo/releases/latest").openConnection() as HttpURLConnection
                    c.setRequestProperty("Accept", "application/vnd.github+json")
                    c.connectTimeout = 12000; c.readTimeout = 12000
                    val raw = c.inputStream.bufferedReader().use { it.readText() }
                    val obj = JSONObject(raw)
                    val tag = obj.optString("tag_name", "unknown")
                    val page = obj.optString("html_url", "https://github.com/$ownerRepo/releases")
                    web.post { web.evaluateJavascript("window.onUpdateResult && window.onUpdateResult(${JSONObject.quote(tag)},${JSONObject.quote(page)})", null) }
                } catch (e: Exception) {
                    web.post { web.evaluateJavascript("window.onUpdateError && window.onUpdateError(${JSONObject.quote(e.message ?: "Update check failed")})", null) }
                }
            }
        }
        @JavascriptInterface fun openUrl(url: String) {
            runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        }
    }

    private fun deliver(id: String, text: String, isError: Boolean) {
        web.post {
            web.evaluateJavascript(
                "window.onMayiraReply && window.onMayiraReply(${JSONObject.quote(id)},${JSONObject.quote(text)},$isError)", null
            )
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition is unavailable", Toast.LENGTH_SHORT).show(); return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                web.evaluateJavascript("window.onVoiceText && window.onVoiceText(${JSONObject.quote(text)})", null)
            }
            override fun onError(error: Int) { web.evaluateJavascript("window.onVoiceError && window.onVoiceError()", null) }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Mayira-কে বলুন")
        }
        recognizer?.startListening(i)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        try {
            when (requestCode) {
                exportCode -> {
                    val payload = prefs.getString("pending_export", "{}") ?: "{}"
                    contentResolver.openOutputStream(data.data!!)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    Toast.makeText(this, "Backup exported", Toast.LENGTH_LONG).show()
                }
                importCode -> {
                    val payload = contentResolver.openInputStream(data.data!!)?.bufferedReader()?.use { it.readText() } ?: ""
                    if (payload.length <= 2_500_000) web.evaluateJavascript(
                        "window.importMayiraBackup && window.importMayiraBackup(${JSONObject.quote(payload)})", null
                    )
                }
            }
        } catch (_: Exception) { Toast.makeText(this, "Backup operation failed", Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        recognizer?.destroy(); tts?.shutdown(); executor.shutdown()
        super.onDestroy()
    }
}
