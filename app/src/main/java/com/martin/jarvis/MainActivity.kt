package com.martin.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.speech.tts.TextToSpeech
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val CIAN = Color.parseColor("#29D8FF")
    private val FONDO = Color.parseColor("#020814")
    private val CAJA = Color.parseColor("#051A2B")
    private val TEXTO = Color.parseColor("#D9F6FF")
    private val REQ_VOZ = 7

    private lateinit var p: Prefs
    private lateinit var txtEstado: TextView
    private lateinit var txtLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnActivar: Button
    private lateinit var panelAjustes: LinearLayout
    private lateinit var contenedor: ScrollView
    private var ttsPrueba: TextToSpeech? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        window.statusBarColor = FONDO
        window.navigationBarColor = FONDO
        setContentView(construirPantalla())
        pedirPermisos()
    }

    override fun onResume() {
        super.onResume()
        Bus.oyente = { tipo, texto ->
            if (tipo == "estado") txtEstado.text = texto.uppercase()
            if (tipo == "log") agregarLog(texto)
            refrescarBoton()
        }
        txtEstado.text = Bus.estado.uppercase()
        txtLog.text = ""
        synchronized(Bus.registro) { Bus.registro.forEach { agregarLog(it) } }
        if (Bus.registro.isEmpty()) agregarLog("JARVIS: Buenas, ${p.nombre}. ${if (p.clave.isBlank()) "Primero cargue la clave de Gemini en Ajustes, por favor." else "Toque \"Activar\" y dígame \"Jarvis\" cuando me necesite."}")
        refrescarBoton()
    }

    override fun onPause() {
        super.onPause()
        Bus.oyente = null
    }

    // ---------------- Pantalla ----------------
    private fun caja(color: Int, borde: Int = Color.parseColor("#0B3550")) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(10).toFloat(); setStroke(dp(1), borde)
    }

    private fun boton(texto: String, principal: Boolean = true, accion: () -> Unit) = Button(this).apply {
        text = texto
        isAllCaps = false
        textSize = 16f
        setTextColor(if (principal) Color.parseColor("#001122") else CIAN)
        background = caja(if (principal) CIAN else Color.TRANSPARENT, CIAN)
        setOnClickListener { accion() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) }
    }

    private fun etiqueta(texto: String) = TextView(this).apply {
        text = texto; setTextColor(Color.parseColor("#7FE9FF")); textSize = 14f
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun campo(valor: String, pista: String, multilinea: Boolean = false, clave: Boolean = false) = EditText(this).apply {
        setText(valor); hint = pista
        setTextColor(TEXTO); setHintTextColor(Color.parseColor("#557788"))
        background = caja(CAJA); setPadding(dp(12), dp(10), dp(12), dp(10))
        inputType = when {
            clave -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            multilinea -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multilinea) { minLines = 3; gravity = Gravity.TOP }
    }

    @SuppressLint("SetTextI18n")
    private fun construirPantalla(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FONDO)
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }

        raiz.addView(TextView(this).apply {
            text = "J.A.R.V.I.S."; setTextColor(CIAN); textSize = 24f
            letterSpacing = 0.3f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        txtEstado = TextView(this).apply {
            setTextColor(Color.parseColor("#7FE9FF")); textSize = 12f; gravity = Gravity.CENTER
            letterSpacing = 0.15f; setPadding(0, dp(4), 0, dp(4))
        }
        raiz.addView(txtEstado)

        btnActivar = boton("▶  Activar Jarvis (escucha siempre)") { alternarServicio() }
        raiz.addView(btnActivar)
        raiz.addView(boton("🎤  Hablar ahora", principal = false) { hablarAhora() })

        // Registro de la charla
        txtLog = TextView(this).apply {
            setTextColor(TEXTO); textSize = 15f; setLineSpacing(0f, 1.15f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        scrollLog = ScrollView(this).apply {
            background = caja(Color.parseColor("#06182A"))
            addView(txtLog)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) }
        }
        raiz.addView(scrollLog)

        // Escribir
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        val entrada = campo("", "O escribí acá...").apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f)
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        val enviar = Button(this).apply {
            text = "➤"; textSize = 18f; setTextColor(Color.parseColor("#001122")); background = caja(CIAN, CIAN)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(50)).apply { leftMargin = dp(8) }
        }
        val mandar = {
            val t = entrada.text.toString().trim()
            if (t.isNotEmpty()) { entrada.setText(""); preguntar(t) }
        }
        enviar.setOnClickListener { mandar() }
        entrada.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { mandar(); true } else false }
        fila.addView(entrada); fila.addView(enviar)
        raiz.addView(fila)

        raiz.addView(boton("⚙  Ajustes", principal = false) {
            panelAjustes.visibility = if (panelAjustes.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (panelAjustes.visibility == View.VISIBLE) contenedor.post { contenedor.smoothScrollTo(0, panelAjustes.top) }
        })
        panelAjustes = construirAjustes().apply { visibility = if (p.clave.isBlank()) View.VISIBLE else View.GONE }

        // El panel de ajustes va en un scroll que se superpone
        contenedor = ScrollView(this).apply { setBackgroundColor(FONDO); isFillViewport = true }
        val todo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raiz.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.displayMetrics.heightPixels - dp(40))
        todo.addView(raiz)
        todo.addView(panelAjustes)
        contenedor.addView(todo)
        return contenedor
    }

    @SuppressLint("SetTextI18n")
    private fun construirAjustes(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(40))
            setBackgroundColor(FONDO)
        }
        panel.addView(TextView(this).apply { text = "AJUSTES"; setTextColor(CIAN); textSize = 18f; letterSpacing = 0.2f })

        panel.addView(etiqueta("Clave de API de Gemini (gratis en aistudio.google.com/apikey)"))
        val fClave = campo(p.clave, "Pegá tu clave acá", clave = true); panel.addView(fClave)

        panel.addView(etiqueta("Tu nombre"))
        val fNombre = campo(p.nombre, "Martín"); panel.addView(fNombre)

        panel.addView(etiqueta("Memoria permanente (cosas que Jarvis siempre va a saber de vos)"))
        val fMemoria = campo(p.memoria, "Ej: Tengo un Fiat Duna 1.3 MPI. Vivo en Mendoza.", multilinea = true); panel.addView(fMemoria)

        val lblVel = etiqueta("Velocidad de la voz: ${"%.1f".format(p.velocidad)}"); panel.addView(lblVel)
        val barra = SeekBar(this).apply {
            max = 10; progress = ((p.velocidad - 0.6f) * 10).toInt().coerceIn(0, 10)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { lblVel.text = "Velocidad de la voz: ${"%.1f".format(0.6f + v / 10f)}" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        panel.addView(barra)

        panel.addView(etiqueta("Voz de Jarvis"))
        panel.addView(boton("🗣  Elegir voz (probalas y elegí una de hombre)", principal = false) { elegirVoz() })

        panel.addView(etiqueta("Modelo de IA (dejalo así salvo que te diga otra cosa)"))
        val fModelo = campo(p.modelo, "gemini-flash-latest"); panel.addView(fModelo)

        panel.addView(boton("Guardar") {
            p.clave = fClave.text.toString().trim()
            p.nombre = fNombre.text.toString().trim().ifBlank { "Martín" }
            p.memoria = fMemoria.text.toString().trim()
            p.modelo = fModelo.text.toString().trim().ifBlank { "gemini-flash-latest" }
            p.velocidad = 0.6f + barra.progress / 10f
            Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show()
            panelAjustes.visibility = View.GONE
            contenedor.post { contenedor.smoothScrollTo(0, 0) }
        })
        panel.addView(boton("🔋  Batería sin restricciones (para que no lo cierre)", principal = false) { pedirBateria() })
        panel.addView(boton("🗑  Borrar la charla", principal = false) {
            Cerebro.borrarHistorial(this)
            synchronized(Bus.registro) { Bus.registro.clear() }
            txtLog.text = ""
            agregarLog("JARVIS: Memoria de la conversación borrada, ${p.nombre}.")
        })
        return panel
    }

    // ---------------- Elegir voz ----------------
    private fun elegirVoz() {
        val t = ttsPrueba
        if (t == null) {
            Toast.makeText(this, "Cargando voces...", Toast.LENGTH_SHORT).show()
            ttsPrueba = TextToSpeech(this) { st ->
                if (st == TextToSpeech.SUCCESS) runOnUiThread { mostrarVoces() }
                else runOnUiThread { Toast.makeText(this, "No hay motor de voz", Toast.LENGTH_LONG).show() }
            }
        } else mostrarVoces()
    }

    private fun mostrarVoces() {
        val t = ttsPrueba ?: return
        val voces = try {
            t.voices.orEmpty().filter { it.locale.language == "es" && !it.features.contains("notInstalled") }
                .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.locale.country }, { it.name }))
        } catch (e: Exception) { emptyList() }
        if (voces.isEmpty()) {
            Toast.makeText(this, "No encontré voces en español. Instalalas en Ajustes del teléfono → Texto a voz.", Toast.LENGTH_LONG).show()
            return
        }
        val nombres = voces.mapIndexed { i, v ->
            val pais = v.locale.displayCountry.ifBlank { "Español" }
            "Voz ${i + 1} · $pais${if (v.isNetworkConnectionRequired) " · necesita internet" else ""}\n${v.name}"
        }.toTypedArray()
        var elegida = voces.indexOfFirst { it.name == p.voz }
        AlertDialog.Builder(this)
            .setTitle("Tocá una voz para escucharla")
            .setSingleChoiceItems(nombres, elegida) { _, i ->
                elegida = i
                t.voice = voces[i]
                t.setSpeechRate(p.velocidad); t.setPitch(0.9f)
                t.speak("A su servicio, ${p.nombre}. Todos los sistemas funcionando.", TextToSpeech.QUEUE_FLUSH, null, "prueba")
            }
            .setPositiveButton("Usar esta") { _, _ ->
                if (elegida >= 0) {
                    p.voz = voces[elegida].name
                    Toast.makeText(this, "Voz guardada ✔", Toast.LENGTH_SHORT).show()
                }
                t.stop()
            }
            .setNegativeButton("Cancelar") { _, _ -> t.stop() }
            .show()
    }

    override fun onDestroy() {
        ttsPrueba?.shutdown()
        super.onDestroy()
    }

    private fun agregarLog(t: String) {
        txtLog.append(if (txtLog.text.isEmpty()) t else "\n\n$t")
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refrescarBoton() {
        btnActivar.text = if (Bus.activo) "■  Detener Jarvis" else "▶  Activar Jarvis (escucha siempre)"
    }

    // ---------------- Acciones ----------------
    private fun tienePermisoMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun pedirPermisos() {
        val faltan = mutableListOf<String>()
        if (!tienePermisoMic()) faltan.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            faltan.add(Manifest.permission.POST_NOTIFICATIONS)
        if (faltan.isNotEmpty()) requestPermissions(faltan.toTypedArray(), 1)
    }

    private fun iniciarServicio(intent: Intent) {
        if (!tienePermisoMic()) {
            Toast.makeText(this, "Primero dale permiso de micrófono a JARVIS", Toast.LENGTH_LONG).show()
            pedirPermisos(); return
        }
        try { startForegroundService(intent) } catch (e: Exception) {
            agregarLog("⚠ No pude iniciar: ${e.message}")
        }
    }

    private fun alternarServicio() {
        if (Bus.activo) {
            startService(Intent(this, JarvisService::class.java).setAction(JarvisService.DETENER))
            Bus.activo = false
        } else {
            if (p.clave.isBlank()) { panelAjustes.visibility = View.VISIBLE; Toast.makeText(this, "Falta la clave de Gemini", Toast.LENGTH_LONG).show(); return }
            iniciarServicio(Intent(this, JarvisService::class.java).setAction(JarvisService.INICIAR))
            Bus.activo = true
        }
        refrescarBoton()
    }

    private fun preguntar(texto: String) {
        iniciarServicio(Intent(this, JarvisService::class.java)
            .setAction(JarvisService.PREGUNTAR).putExtra(JarvisService.EXTRA_TEXTO, texto))
    }

    private fun hablarAhora() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Hable, ${p.nombre}")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_VOZ)
        } catch (e: Exception) {
            Toast.makeText(this, "No encontré el reconocedor de voz de Google", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOZ && resultCode == RESULT_OK) {
            val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!t.isNullOrBlank()) preguntar(t)
        }
    }

    @SuppressLint("BatteryLife")
    private fun pedirBateria() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Ya está sin restricciones de batería ✔", Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}
