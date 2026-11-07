package com.martin.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale

/**
 * Servicio que queda siempre escuchando la palabra "Jarvis" (sin internet, con Vosk).
 * Cuando la escucha: pita, graba la orden, se la pasa a Gemini y responde en voz alta.
 */
class JarvisService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    companion object {
        const val INICIAR = "com.martin.jarvis.INICIAR"
        const val DETENER = "com.martin.jarvis.DETENER"
        const val PREGUNTAR = "com.martin.jarvis.PREGUNTAR"
        const val EXTRA_TEXTO = "texto"
        private const val CANAL = "jarvis_escucha"
        private const val GRAMATICA_PALABRA = "[\"jarvis\", \"[unk]\"]"
        private const val SEG_ESPERA_ORDEN = 8L
    }

    private enum class Modo { NADA, PALABRA, ORDEN, OCUPADO }

    private val main = Handler(Looper.getMainLooper())
    private var modelo: Model? = null
    private var escucha: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var ttsListo = false
    private var escucharPalabra = false
    private var modo = Modo.NADA
    private var wakeLock: PowerManager.WakeLock? = null
    private val sinOrden = Runnable {
        if (modo == Modo.ORDEN) {
            Bus.log("(No escuché ninguna orden)")
            volverAEsperar()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanal()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, notificacion("Iniciando..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, notificacion("Iniciando..."))
            }
        } catch (e: Exception) {
            Bus.log("⚠ Android no dejó iniciar el servicio: ${e.message}")
            stopSelf()
            return
        }
        Bus.activo = true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:escucha").apply {
            setReferenceCounted(false); acquire()
        }
        tts = TextToSpeech(this, this)
        cargarModelo()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            DETENER -> { stopSelf(); return START_NOT_STICKY }
            PREGUNTAR -> intent.getStringExtra(EXTRA_TEXTO)?.let { procesar(it) }
            else -> {
                escucharPalabra = true
                if (modo == Modo.NADA) volverAEsperar()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        escucharPalabra = false
        main.removeCallbacksAndMessages(null)
        detenerMicrofono()
        tts?.shutdown()
        try { modelo?.close() } catch (_: Exception) {}
        wakeLock?.release()
        Bus.activo = false
        Bus.estado("Detenido")
        super.onDestroy()
    }

    // ---------- Modelo de voz (Vosk) ----------
    private fun cargarModelo() {
        Bus.estado("Preparando el oído (la primera vez tarda unos segundos)...")
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        StorageService.unpack(this, "model-es", "model",
            { m ->
                modelo = m
                Bus.log("Oído listo.")
                if (modo == Modo.NADA) volverAEsperar()
            },
            { e -> Bus.log("⚠ No pude cargar el modelo de voz: ${e.message}") })
    }

    private fun prenderMicrofono(gramatica: String?): Boolean {
        detenerMicrofono()
        val m = modelo ?: return false
        return try {
            val rec = if (gramatica != null) Recognizer(m, 16000f, gramatica) else Recognizer(m, 16000f)
            escucha = SpeechService(rec, 16000f)
            escucha!!.startListening(this)
        } catch (e: Exception) {
            Bus.log("⚠ Error del micrófono: ${e.message}")
            false
        }
    }

    private fun detenerMicrofono() {
        try { escucha?.stop(); escucha?.shutdown() } catch (_: Exception) {}
        escucha = null
    }

    /** Vuelve a quedarse esperando la palabra "Jarvis" (o en reposo si la escucha está apagada) */
    private fun volverAEsperar() {
        main.removeCallbacks(sinOrden)
        if (escucharPalabra && modelo != null) {
            modo = Modo.PALABRA
            if (prenderMicrofono(GRAMATICA_PALABRA)) {
                Bus.estado("Esperando que digas \"Jarvis\"")
                actualizarNotificacion("Esperando que digas \"Jarvis\"")
            }
        } else {
            modo = Modo.NADA
            detenerMicrofono()
            if (modelo == null && escucharPalabra) return // se activa solo cuando cargue el modelo
            Bus.estado(if (escucharPalabra) "Esperando" else "En línea")
        }
    }

    private fun despertar() {
        if (modo != Modo.PALABRA) return
        modo = Modo.ORDEN
        main.post {
            prenderMicrofono(null)
            pitido()
            Bus.estado("Te escucho...")
            actualizarNotificacion("Te escucho...")
            main.postDelayed(sinOrden, SEG_ESPERA_ORDEN * 1000)
        }
    }

    private fun pitido() {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (_: Exception) {}
    }

    private fun textoDe(json: String?, campo: String): String =
        try { JSONObject(json ?: "{}").optString(campo, "").replace("[unk]", "").trim() } catch (e: Exception) { "" }

    // ---------- Resultados de Vosk (llegan en el hilo principal) ----------
    override fun onPartialResult(hypothesis: String?) {
        val t = textoDe(hypothesis, "partial")
        when (modo) {
            Modo.PALABRA -> if (normalizar(t).contains("jarvis")) despertar()
            Modo.ORDEN -> if (t.isNotEmpty()) {
                main.removeCallbacks(sinOrden)
                main.postDelayed(sinOrden, SEG_ESPERA_ORDEN * 1000)
                Bus.estado("Oyendo: $t")
            }
            else -> {}
        }
    }

    override fun onResult(hypothesis: String?) {
        val t = textoDe(hypothesis, "text")
        when (modo) {
            Modo.PALABRA -> if (normalizar(t).contains("jarvis")) despertar()
            Modo.ORDEN -> if (t.isNotEmpty() && normalizar(t) != "jarvis") {
                val orden = t.replace(Regex("^(oye\\s+)?jarvis[,\\s]*", RegexOption.IGNORE_CASE), "").trim()
                if (orden.isNotEmpty()) main.post { procesar(orden) }
            }
            else -> {}
        }
    }

    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) {
        Bus.log("⚠ Error de escucha: ${exception?.message}")
        main.postDelayed({ if (modo == Modo.PALABRA || modo == Modo.ORDEN) volverAEsperar() }, 2000)
    }
    override fun onTimeout() { volverAEsperar() }

    // ---------- Procesar una orden ----------
    private fun procesar(texto: String) {
        if (modo == Modo.OCUPADO) return
        modo = Modo.OCUPADO
        main.removeCallbacks(sinOrden)
        detenerMicrofono()
        Bus.log("Vos: $texto")
        Bus.estado("Pensando...")
        actualizarNotificacion("Pensando...")
        Thread {
            val resp = Cerebro.preguntar(this, texto)
            main.post {
                Bus.log("JARVIS: $resp")
                hablar(resp)
            }
        }.start()
    }

    // ---------- Voz (hablar) ----------
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { Bus.log("⚠ No hay motor de voz disponible"); return }
        val t = tts ?: return
        val opciones = listOf(Locale("es", "AR"), Locale("es", "US"), Locale("es", "MX"), Locale("es", "ES"), Locale("es"))
        for (loc in opciones) {
            val r = t.setLanguage(loc)
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) break
        }
        t.setPitch(0.9f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { Bus.estado("Hablando...") }
            override fun onDone(id: String?) { main.post { volverAEsperar() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { volverAEsperar() } }
        })
        ttsListo = true
    }

    private fun hablar(texto: String) {
        val t = tts
        if (!ttsListo || t == null) { volverAEsperar(); return }
        t.setSpeechRate(Prefs(this).velocidad)
        val limpio = texto.replace(Regex("[*#_`>]"), "")
        t.speak(limpio, TextToSpeech.QUEUE_FLUSH, Bundle(), "resp")
    }

    // ---------- Notificación fija ----------
    private fun crearCanal() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "JARVIS escuchando", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notificacion(texto: String): Notification {
        val abrir = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val parar = PendingIntent.getService(this, 1,
            Intent(this, JarvisService::class.java).setAction(DETENER), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CANAL)
            .setContentTitle("JARVIS")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(abrir)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Detener", parar).build())
            .build()
    }

    private fun actualizarNotificacion(texto: String) {
        try { getSystemService(NotificationManager::class.java).notify(1, notificacion(texto)) } catch (_: Exception) {}
    }
}
