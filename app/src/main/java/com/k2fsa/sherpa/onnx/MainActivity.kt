package com.k2fsa.sherpa.onnx.vad.asr

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.R
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

private const val TAG = "sherpa-onnx"
private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

class MainActivity : AppCompatActivity() {

    private lateinit var recordButton: Button
    private lateinit var textView: TextView
    private lateinit var statusText: TextView

    private lateinit var vad: Vad

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val audioSource = MediaRecorder.AudioSource.MIC
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val permissions: Array<String> =
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CALENDAR)

    private lateinit var recAuto: OfflineRecognizer
    private lateinit var recHi: OfflineRecognizer
    @Volatile private var hinglishMode = false
    @Volatile private var isProcessingResponse = false

    private var idx: Int = 0
    private var lastText: String = ""

    @Volatile
    private var isRecording: Boolean = false

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permissionToRecordAccepted = if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

        if (!permissionToRecordAccepted) {
            Log.e(TAG, "Audio record is disallowed")
            finish()
        }

        Log.i(TAG, "Audio record is permitted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)

        textView = findViewById(R.id.my_text)
        textView.movementMethod = ScrollingMovementMethod()

        statusText = findViewById(R.id.status_text)
        statusText.text = "Initializing models... Please wait."

        recordButton = findViewById(R.id.record_button)
        recordButton.isEnabled = false
        recordButton.setOnClickListener { onclick() }

        lifecycleScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Start to initialize VAD model")
            initVadModel()
            Log.i(TAG, "Finished initializing VAD model")

            Log.i(TAG, "Start to initialize non-streaming recognizer")
            initOfflineRecognizer()
            Log.i(TAG, "Finished initializing non-streaming recognizer")

            initTts()

            withContext(Dispatchers.Main) {
                recordButton.isEnabled = true
                statusText.text = "Tap button below to start"
                textView.text = ""
                Log.i(TAG, "Model initialization completed, button enabled")
            }
            speak("नमस्ते, मैं तैयार हूँ।")
        }
    }

    private fun onclick() {
        if (!isRecording) {
            val ret = initMicrophone()
            if (!ret) {
                Log.e(TAG, "Failed to initialize microphone")
                return
            }
            Log.i(TAG, "state: ${audioRecord?.state}")
            audioRecord!!.startRecording()
            recordButton.setText(R.string.stop)
            recordButton.backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent_red))
            statusText.text = "Listening..."
            isRecording = true

            textView.text = ""
            lastText = ""
            idx = 0

            vad.reset()
            recordingThread = thread(true) {
                processSamples()
            }
            Log.i(TAG, "Started recording")
        } else {
            isRecording = false

            audioRecord!!.stop()
            audioRecord!!.release()
            audioRecord = null

            recordButton.setText(R.string.start)
            recordButton.backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent_indigo))
            statusText.text = "Tap button below to start"
            Log.i(TAG, "Stopped recording")
        }
    }

    private fun initVadModel() {
        Log.i(TAG, "Initializing Silero VAD model")

        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.6f,
                minSilenceDuration = 0.8f,
                minSpeechDuration = 0.5f,
                maxSpeechDuration = 12f,
                windowSize = 512
            ),
            sampleRate = sampleRateInHz,
            numThreads = 1,
            provider = "cpu"
        )

        vad = Vad(
            assetManager = application.assets,
            config = config
        )
    }

    private fun initMicrophone(): Boolean {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
            return false
        }

        val numBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
        Log.i(
            TAG, "buffer size in milliseconds: ${numBytes * 1000.0f / sampleRateInHz}"
        )

        audioRecord = AudioRecord(
            audioSource,
            sampleRateInHz,
            channelConfig,
            audioFormat,
            numBytes * 2
        )
        return true
    }

    private fun processSamples() {
        Log.i(TAG, "processing samples")

        val bufferSize = 512
        val buffer = ShortArray(bufferSize)
        val coroutineScope = CoroutineScope(Dispatchers.IO)

        while (isRecording) {
            val ret = audioRecord?.read(buffer, 0, buffer.size)
            if (ret != null && ret > 0) {
                val samples = FloatArray(ret) { buffer[it] / 32768.0f }
                if (speaking || isProcessingResponse) {
                    vad.reset()
                    continue
                }

                vad.acceptWaveform(samples)
                while (!vad.empty()) {
                    val segment = vad.front()
                    val tSeg = System.nanoTime()
                    vad.pop()
                    vad.reset()

                    coroutineScope.launch {
                        isProcessingResponse = true
                        try {
                            val text = runSecondPass(segment.samples)
                            if (text.isNotBlank() && !isJunk(text)) {
                                synchronized(respondLock) {
                                    runOnUiThread { statusText.text = "Thinking..." }
                                    val reply = try { askLlm(text) } catch (e: Exception) { Log.e(TAG, "LLM failed", e); "" }
                                    val rawReply = if (reply.isBlank()) "Sorry, I could not reach the assistant." else reply
                                    val finalReply = handleActions(rawReply)
                                    runOnUiThread {
                                        statusText.text = "Speaking..."
                                        lastText = if (lastText.isEmpty()) "AGENT : $finalReply" else "$lastText\n\nAGENT : $finalReply"
                                        textView.text = lastText
                                    }
                                    speak(finalReply, tSeg)
                                    runOnUiThread {
                                        statusText.text = if (isRecording) "Listening..." else "Tap button below to start"
                                    }
                                }
                            } else if (text.isNotBlank()) {
                                Log.i(TAG, "STT gate rejected junk/malformed transcript: '$text'")
                            }
                        } finally {
                            vad.reset()
                            isProcessingResponse = false
                        }
                    }
                }
            }
        }

        coroutineScope.cancel()
    }

    private fun makeRecognizer(lang: String, size: String = "base"): OfflineRecognizer {
        val whisper = OfflineWhisperModelConfig(
            encoder = "sherpa-onnx-whisper-$size/$size-encoder.int8.onnx",
            decoder = "sherpa-onnx-whisper-$size/$size-decoder.int8.onnx",
            language = lang,
            task = "transcribe"
        )
        val model = OfflineModelConfig(
            whisper = whisper,
            tokens = "sherpa-onnx-whisper-$size/$size-tokens.txt",
            numThreads = 2,
            debug = false,
            provider = "cpu"
        )
        val config = OfflineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = sampleRateInHz, featureDim = 80),
            modelConfig = model
        )
        return OfflineRecognizer(
            assetManager = application.assets,
            config = config
        )
    }

    private fun initOfflineRecognizer() {
        Log.i(TAG, "Initializing Whisper Recognizers (Auto=base, Hi=small)")
        recAuto = makeRecognizer("", "base")
        recHi = makeRecognizer("hi", "small")
    }

    private fun decode(rec: OfflineRecognizer, samples: FloatArray): Pair<String, String> {
        val s = rec.createStream()
        s.acceptWaveform(samples, sampleRateInHz)
        rec.decode(s)
        val r = rec.getResult(s)
        s.release()
        return r.text.trim() to r.lang
    }

    private fun isGarbledLatin(text: String): Boolean {
        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return false

        val commonEnglishWords = setOf(
            "what", "is", "the", "weather", "forecast", "for", "tomorrow", "can", "you",
            "set", "an", "alarm", "reminder", "at", "time", "clock", "hello", "hi", "how", "are",
            "schedule", "meeting", "todays", "today's", "next", "when", "agenda"
        )

        var suspiciousWordCount = 0
        for (word in words) {
            if (word in commonEnglishWords) continue

            val vowelCount = word.count { it in "aeiou" }
            if (word.length >= 4 && vowelCount == 0) suspiciousWordCount++
            if (word.length >= 7 && vowelCount.toFloat() / word.length < 0.25f) suspiciousWordCount++
        }

        return suspiciousWordCount > 0
    }

    private fun isCoherentLatin(text: String): Boolean {
        val words = text.lowercase().replace(Regex("[^a-z\\s]"), "").split(Regex("\\s+")).filter { it.length >= 2 }
        if (words.isEmpty()) return false
        val validWords = setOf(
            "today", "todays", "schedule", "meeting", "next", "when", "what", "is", "the", "weather",
            "forecast", "tomorrow", "can", "you", "set", "an", "alarm", "reminder", "at", "time",
            "clock", "hello", "hi", "how", "are", "aaj", "kal", "mera", "meri", "kab", "kya", "hai",
            "schedules", "meetings", "reminders", "alarms", "events", "calls", "tasks"
        )
        return words.any { it in validWords }
    }

    private fun cleanDevanagari(s: String): String {
        val words = s.split(Regex("\\s+")).map { word ->
            word.dropWhile { ch ->
                ch in '\u0900'..'\u0903' || ch in '\u093C'..'\u094D' || ch in '\u0951'..'\u0957'
            }
        }.filter { it.isNotBlank() }
        return words.joinToString(" ")
    }

    private val decodeLock = Any()

    private fun runSecondPass(samples: FloatArray): String = synchronized(decodeLock) {
        val t0 = System.nanoTime()
        var (text, lang) = decode(if (hinglishMode) recHi else recAuto, samples)
        val l = lang.replace("<|", "").replace("|>", "")
        val arabic = text.any { it in '\u0600'..'\u06FF' }

        val needsHindiRedecode = !hinglishMode && (arabic || l == "ur" || isGarbledLatin(text)) && !isCoherentLatin(text)

        if (needsHindiRedecode) {
            val (t2, _) = decode(recHi, samples)
            val cleanedT2 = cleanDevanagari(t2)
            if (!isJunk(cleanedT2)) {
                Log.i(TAG, "STT re-decode triggered: '$text' -> '$cleanedT2'")
                text = cleanedT2
            } else {
                Log.i(TAG, "STT re-decode produced junk ('$t2'), keeping initial: '$text'")
            }
        } else {
            text = cleanDevanagari(text)
        }

        Log.i(TAG, "STT: %d ms [lang=%s] -> '%s'".format((System.nanoTime() - t0) / 1_000_000, lang, text))
        if (isJunk(text)) "" else text
    }

    private fun handleActions(reply: String): String {
        val m = Regex("\\[\\[ALARM (\\d{1,2}):(\\d{2})(?: ([^\\]]*))?\\]\\]").find(reply) ?: return reply
        executeSetAlarm(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3])
        return reply.replace(m.value, "").trim()
    }

    private fun executeSetAlarm(hour: Int, minute: Int, label: String) {
        try {
            startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Reminder" })
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            Log.i(TAG, "Alarm set via intent: %02d:%02d (%s)".format(hour, minute, label))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set alarm", e)
        }
    }

    private fun isJunk(t: String): Boolean {
        val s = t.trim()
        if (s.length < 2) return true
        if (s.startsWith("[") || s.startsWith("(") || s.startsWith("♪")) return true
        val letters = s.count { it.isLetter() }
        if (letters < 2) return true

        val devanagariChars = s.filter { it in '\u0900'..'\u097F' }
        if (devanagariChars.isNotEmpty()) {
            val baseConsonants = devanagariChars.count { it in '\u0905'..'\u0939' || it in '\u0958'..'\u0961' }
            val combiningMarks = devanagariChars.count {
                it in '\u0900'..'\u0903' || it in '\u093C'..'\u094D' || it in '\u0951'..'\u0957'
            }
            if (baseConsonants == 0 || combiningMarks > baseConsonants) return true
        }

        val tokens = s.split(Regex("\\s+"))
        if (tokens.size >= 3 && tokens.distinct().size * 2 <= tokens.size) return true
        return false
    }

    private var ttsHi: OfflineTts? = null
    private var ttsEn: OfflineTts? = null
    @Volatile private var speaking = false

    private fun copyRecursive(assetPath: String, dest: File) {
        val children = assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { i -> FileOutputStream(dest).use { o -> i.copyTo(o) } }
        } else {
            dest.mkdirs()
            for (c in children) copyRecursive("$assetPath/$c", File(dest, c))
        }
    }

    private fun ensureEspeakData(folder: String): String {
        val root = File(filesDir, "tts-data")
        val target = File(root, "$folder/espeak-ng-data")
        if (!(target.exists() && (target.list()?.isNotEmpty() == true))) {
            copyRecursive("$folder/espeak-ng-data", target)
        }
        return target.absolutePath
    }

    private fun makeTts(folder: String, onnx: String): OfflineTts {
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$folder/$onnx",
                    tokens = "$folder/tokens.txt",
                    dataDir = ensureEspeakData(folder),
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            )
        )
        return OfflineTts(assetManager = application.assets, config = config)
    }

    private fun initTts() {
        Log.i(TAG, "Initializing TTS voices")
        ttsHi = makeTts("vits-piper-hi_IN-priyamvada-medium", "hi_IN-priyamvada-medium.onnx")
        ttsEn = makeTts("vits-piper-en_US-lessac-medium", "en_US-lessac-medium.onnx")
        Log.i(TAG, "Finished initializing TTS voices")
    }

    private fun play(samples: FloatArray, sr: Int) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(samples.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        track.play()
        Thread.sleep(samples.size * 1000L / sr + 200)
        track.release()
    }

    private fun sanitizeForSpeech(text: String): String {
        return text
            .replace(Regex("(\\d{1,2}):00\\b"), "$1")
            .replace(Regex("(\\d{1,2}):(\\d{2})"), "$1 $2")
            .replace(",", "")
            .replace("।", "")
            .replace(".", " ")
            .replace(";", "")
            .replace(":", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun speak(text: String, tStart: Long = 0L) {
        val hasDevanagari = text.any { it in '\u0900'..'\u097F' }
        val tts = (if (hasDevanagari) ttsHi else ttsEn) ?: return
        val rawSpoken = if (hasDevanagari) fixForHindiVoice(text) else text
        val spoken = sanitizeForSpeech(rawSpoken)
        speaking = true
        try {
            val t0 = System.nanoTime()
            val audio = tts.generate(text = spoken, sid = 0, speed = 1.0f)
            val ms = (System.nanoTime() - t0) / 1_000_000
            Log.i(TAG, "TTS: %.1fs audio generated in %d ms (%s)"
                .format(audio.samples.size / audio.sampleRate.toFloat(), ms, if (hasDevanagari) "hi" else "en"))
            if (tStart != 0L) {
                Log.i(TAG, "END-TO-END: %d ms from end of speech to first audio".format((System.nanoTime() - tStart) / 1_000_000))
            }
            play(audio.samples, audio.sampleRate)
        } finally {
            speaking = false
        }
    }

    private fun fetchWeather(lat: Double = 12.9719, lon: Double = 77.5937): String {
        return try {
            val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current_weather=true&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=Asia%2FKolkata&forecast_days=2")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000; readTimeout = 3000
            }
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val now = j.getJSONObject("current_weather").getDouble("temperature")
            val d = j.getJSONObject("daily")
            val hi = d.getJSONArray("temperature_2m_max").getDouble(1)
            val lo = d.getJSONArray("temperature_2m_min").getDouble(1)
            val rain = d.getJSONArray("precipitation_probability_max").getInt(1)
            "Bengaluru now: ${now.toInt()}°C. Tomorrow: high ${hi.toInt()}°C, low ${lo.toInt()}°C, ${rain}% chance of rain."
        } catch (e: Exception) {
            Log.e(TAG, "Weather fetch failed", e)
            "Unable to fetch weather data right now."
        }
    }

    private fun fetchCalendar(): String {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) return "Calendar access not granted."
        val c0 = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val startToday = c0.timeInMillis
        val day = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, startToday); ContentUris.appendId(b, startToday + 2 * day - 1)
        val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH)
        val today = mutableListOf<String>()
        var next: String? = null
        contentResolver.query(
            b.build(),
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
            null, null, "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(2) == 1) continue
                val title = c.getString(0) ?: "Untitled"
                val begin = c.getLong(1)
                val isToday = begin < startToday + day
                val label = "$title at ${fmt.format(java.util.Date(begin))}"
                if (isToday) today.add(label)
                if (next == null && begin >= now) next = label + if (isToday) "" else " tomorrow"
            }
        }
        val t = if (today.isEmpty()) "No events today." else "Today: ${today.joinToString("; ")}."
        return "$t Next upcoming: ${next ?: "none"}."
    }

    private val SYSTEM_PROMPT = """
You are a voice assistant on a phone. Reply in one or two short sentences that sound natural when spoken aloud.
LANGUAGE RULES (CRITICAL):
- If the user's spoken input is in English (e.g. "What is the weather forecast for Bangalore tomorrow?", "Can you set a reminder for my meeting at 5 PM?"), reply strictly in plain English (e.g. "Reminder set for 5 PM.").
- If the user's spoken input is in Hindi or Hinglish (e.g. "Mujhe kal subah 7 baje ka alarm set karna hai.", "Aaj ka schedule kya hai, aur meri next meeting kab hai?"), reply in Hindi using Devanagari script for ALL words (including common English words written in Devanagari, e.g., मीटिंग, अलार्म, रिमाइंडर, शेड्यूल, वेदर, डिग्री).

FORMATTING RULES:
- Write numbers as digits. Never write times with ":00" (write 5 PM or 5 बजे, never 5:00 or 5:00 PM).
- Never use emojis, markdown, lists or quotation marks.

UNCERTAINTY & CLARIFICATION RULES:
- If the transcript is unclear, incomplete, or nonsensical, ask the user to repeat in one short sentence instead of guessing.
- Never invent missing words, times, locations, or actions.
- The user's transcript may contain errors. If the transcript is in Urdu or Arabic script, treat it as spoken Hindi (Hindustani) and reply in Hindi using Devanagari.
""".trimIndent()

    private fun getToolsArray(): JSONArray {
        val tools = JSONArray()

        // Tool 1: get_weather
        val weatherParams = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("location", JSONObject().put("type", "string").put("description", "City name, e.g. Bengaluru")))
        tools.put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "get_weather")
                .put("description", "Get live weather forecast for a location")
                .put("parameters", weatherParams)))

        // Tool 2: get_calendar_events
        val calendarParams = JSONObject().put("type", "object").put("properties", JSONObject())
        tools.put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "get_calendar_events")
                .put("description", "Get today's calendar schedule, agenda, and upcoming meetings")
                .put("parameters", calendarParams)))

        // Tool 3: set_alarm
        val alarmProperties = JSONObject()
            .put("hour", JSONObject().put("type", "integer").put("description", "Hour in 24-hour format (0 to 23)"))
            .put("minute", JSONObject().put("type", "integer").put("description", "Minute (0 to 59)"))
            .put("label", JSONObject().put("type", "string").put("description", "Label for the alarm or reminder"))
        val alarmParams = JSONObject()
            .put("type", "object")
            .put("properties", alarmProperties)
            .put("required", JSONArray().put("hour").put("minute"))
        tools.put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "set_alarm")
                .put("description", "Set an alarm or reminder for a specific time")
                .put("parameters", alarmParams)))

        return tools
    }

    private fun executeToolCall(name: String, args: JSONObject): String {
        return when (name) {
            "get_weather" -> {
                fetchWeather()
            }
            "get_calendar_events" -> {
                fetchCalendar()
            }
            "set_alarm" -> {
                val hour = args.optInt("hour", -1)
                val minute = args.optInt("minute", -1)
                val label = args.optString("label", "Reminder")
                if (hour in 0..23 && minute in 0..59) {
                    executeSetAlarm(hour, minute, label)
                    "Alarm set successfully for %02d:%02d (%s)".format(hour, minute, label)
                } else {
                    "Invalid alarm time requested: hour=$hour, minute=$minute"
                }
            }
            else -> "Unknown tool: $name"
        }
    }

    private val history = mutableListOf<Pair<String, String>>()
    private val respondLock = Any()

    private fun postToGroq(msgs: JSONArray, tools: JSONArray? = null): JSONObject {
        val body = JSONObject()
            .put("model", LLM_MODEL)
            .put("messages", msgs)
            .put("temperature", 0.2)
            .put("max_completion_tokens", 250)
        if (tools != null) {
            body.put("tools", tools)
            body.put("tool_choice", "auto")
        }

        val conn = (URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $GROQ_API_KEY")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (code !in 200..299) {
            Log.e(TAG, "LLM HTTP $code: $resp")
            return JSONObject()
        }
        return JSONObject(resp)
    }

    private fun askLlm(userText: String): String {
        val t0 = System.nanoTime()
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        synchronized(history) {
            history.takeLast(6).forEach {
                msgs.put(JSONObject().put("role", it.first).put("content", it.second))
            }
        }
        msgs.put(JSONObject().put("role", "user").put("content", userText))

        val respObj = postToGroq(msgs, getToolsArray())
        if (!respObj.has("choices")) return ""

        val choiceMsg = respObj.getJSONArray("choices").getJSONObject(0).getJSONObject("message")

        // Handle structured tool calls if requested by LLM
        if (choiceMsg.has("tool_calls") && !choiceMsg.isNull("tool_calls")) {
            val toolCalls = choiceMsg.getJSONArray("tool_calls")
            if (toolCalls.length() > 0) {
                msgs.put(choiceMsg)
                for (i in 0 until toolCalls.length()) {
                    val tc = toolCalls.getJSONObject(i)
                    val callId = tc.getString("id")
                    val fn = tc.getJSONObject("function")
                    val fnName = fn.getString("name")
                    val fnArgsStr = fn.optString("arguments", "{}")
                    val fnArgs = try { JSONObject(fnArgsStr) } catch (e: Exception) { JSONObject() }

                    Log.i(TAG, "LLM requested tool call: $fnName with args: $fnArgsStr")
                    val toolResult = executeToolCall(fnName, fnArgs)

                    val toolMsg = JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", toolResult)
                    msgs.put(toolMsg)
                }

                // Re-call LLM to generate final conversational response
                val secondResp = postToGroq(msgs, null)
                if (secondResp.has("choices")) {
                    val finalMsg = secondResp.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    var reply = if (finalMsg.isNull("content")) "" else finalMsg.getString("content")
                    reply = reply.replace(Regex("(?s)<think>.*?</think>"), "").trim()
                    Log.i(TAG, "LLM (tool final): answered in %d ms -> '%s'".format((System.nanoTime() - t0) / 1_000_000, reply))
                    synchronized(history) {
                        history.add("user" to userText)
                        history.add("assistant" to reply)
                    }
                    return reply
                }
            }
        }

        var reply = if (choiceMsg.isNull("content")) "" else choiceMsg.getString("content")
        reply = reply.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        if (reply.isEmpty()) {
            Log.e(TAG, "LLM returned empty reply")
            return ""
        }
        Log.i(TAG, "LLM: answered in %d ms -> '%s'".format((System.nanoTime() - t0) / 1_000_000, reply))
        synchronized(history) {
            history.add("user" to userText)
            history.add("assistant" to reply)
        }
        return reply
    }

    private val hindiFixes = mapOf(
        "meetings" to "मीटिंग्स", "reminders" to "रिमाइंडर्स", "alarms" to "अलार्म्स",
        "events" to "इवेंट्स", "schedules" to "शेड्यूल्स", "tasks" to "टास्क्स", "calls" to "कॉल्स"
    )

    private fun fixForHindiVoice(text: String): String {
        var out = text
        for ((k, v) in hindiFixes) out = out.replace(Regex("\\b$k\\b", RegexOption.IGNORE_CASE), v)
        return out
    }
}