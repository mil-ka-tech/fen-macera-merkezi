package com.milkatech.ruscaaltyazi

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var playButton: Button
    private lateinit var statusText: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var levelText: TextView
    private lateinit var segmentText: TextView

    @Volatile private var listening = false
    private var audioRecord: AudioRecord? = null
    private var lastFile: File? = null
    private var segmentCount = 0

    private val sampleRate = 16000
    private val silenceMs = 900L
    private val startThreshold = 900.0
    private val stopThreshold = 520.0

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else statusText.text = "Mikrofon izni verilmedi."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        playButton = findViewById(R.id.playButton)
        statusText = findViewById(R.id.statusText)
        levelBar = findViewById(R.id.levelBar)
        levelText = findViewById(R.id.levelText)
        segmentText = findViewById(R.id.segmentText)

        startButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        stopButton.setOnClickListener { stopListening() }
        playButton.setOnClickListener { playLast() }
    }

    private fun startListening() {
        if (listening) return
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(sampleRate)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            statusText.text = "Mikrofon başlatılamadı."
            record.release()
            return
        }

        audioRecord = record
        listening = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusText.text = "🎧 Dinliyor… Rusça film sesini bekliyorum."
        record.startRecording()

        thread(start = true, name = "audio-listener") {
            val buffer = ShortArray(1024)
            var inSpeech = false
            var silenceStart = 0L
            var segmentStart = 0L
            var pcm = ByteArrayOutputStream()

            while (listening) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) { val v = buffer[i].toDouble(); sum += v * v }
                val rms = sqrt(sum / n)
                val percent = (rms / 45.0).toInt().coerceIn(0, 100)
                runOnUiThread { levelBar.progress = percent; levelText.text = "$percent%" }
                val now = System.currentTimeMillis()

                if (!inSpeech && rms >= startThreshold) {
                    inSpeech = true
                    segmentStart = now
                    silenceStart = 0L
                    pcm = ByteArrayOutputStream()
                    runOnUiThread { statusText.text = "🟢 Konuşma algılandı — kayıt alınıyor." }
                }

                if (inSpeech) {
                    for (i in 0 until n) {
                        val s = buffer[i].toInt()
                        pcm.write(s and 0xff)
                        pcm.write((s shr 8) and 0xff)
                    }
                    if (rms < stopThreshold) {
                        if (silenceStart == 0L) silenceStart = now
                        if (now - silenceStart >= silenceMs) {
                            if (now - segmentStart > 350L) saveSegment(pcm.toByteArray())
                            inSpeech = false
                            silenceStart = 0L
                            runOnUiThread { statusText.text = "✅ Replik bitti. Yeni konuşmayı bekliyorum." }
                        }
                    } else silenceStart = 0L
                }
            }

            if (inSpeech && pcm.size() > 0) saveSegment(pcm.toByteArray())
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
    }

    private fun stopListening() {
        listening = false
        startButton.isEnabled = true
        stopButton.isEnabled = false
        levelBar.progress = 0
        levelText.text = "0%"
        statusText.text = "Durduruldu."
    }

    private fun saveSegment(pcm: ByteArray) {
        segmentCount++
        val file = File(filesDir, "replik_$segmentCount.wav")
        writeWav(file, pcm, sampleRate)
        lastFile = file
        runOnUiThread {
            segmentText.text = "Replik $segmentCount yakalandı\n\${file.name}\n\${pcm.size / 32000.0} sn yaklaşık"
            playButton.isEnabled = true
        }
    }

    private fun writeWav(file: File, pcm: ByteArray, rate: Int) {
        FileOutputStream(file).use { out ->
            val dataLen = pcm.size
            val totalLen = dataLen + 36
            val byteRate = rate * 2
            fun writeIntLE(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff); out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff) }
            fun writeShortLE(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
            out.write("RIFF".toByteArray()); writeIntLE(totalLen); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); writeIntLE(16); writeShortLE(1); writeShortLE(1)
            writeIntLE(rate); writeIntLE(byteRate); writeShortLE(2); writeShortLE(16)
            out.write("data".toByteArray()); writeIntLE(dataLen); out.write(pcm)
        }
    }

    private fun playLast() {
        val f = lastFile ?: return
        playButton.isEnabled = false
        statusText.text = "▶ Replik çalınıyor…"
        val player = MediaPlayer()
        player.setDataSource(f.absolutePath)
        player.prepare()
        player.setOnCompletionListener {
            it.release()
            playButton.isEnabled = true
            statusText.text = if (listening) "🎧 Dinlemeye devam ediyorum." else "Hazır."
        }
        player.start()
    }

    override fun onDestroy() {
        listening = false
        try { audioRecord?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
