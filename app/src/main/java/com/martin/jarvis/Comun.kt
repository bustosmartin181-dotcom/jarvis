package com.martin.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ajustes guardados en el teléfono */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    var clave: String get() = sp.getString("clave", "")!!; set(v) = sp.edit().putString("clave", v).apply()
    var nombre: String get() = sp.getString("nombre", "Martín")!!; set(v) = sp.edit().putString("nombre", v).apply()
    var memoria: String get() = sp.getString("memoria", "")!!; set(v) = sp.edit().putString("memoria", v).apply()
    var modelo: String get() = sp.getString("modelo", "gemini-flash-latest")!!; set(v) = sp.edit().putString("modelo", v).apply()
    var velocidad: Float get() = sp.getFloat("vel", 1.0f); set(v) = sp.edit().putFloat("vel", v).apply()
    var voz: String get() = sp.getString("voz", "")!!; set(v) = sp.edit().putString("voz", v).apply()
    var historial: String get() = sp.getString("hist", "[]")!!; set(v) = sp.edit().putString("hist", v).apply()
}

/** Canal de mensajes entre el servicio y la pantalla */
object Bus {
    private val main = Handler(Looper.getMainLooper())
    var oyente: ((tipo: String, texto: String) -> Unit)? = null
    val registro = ArrayList<String>()
    @Volatile var estado = "Detenido"
    @Volatile var activo = false

    fun estado(t: String) { estado = t; main.post { oyente?.invoke("estado", t) } }

    fun log(t: String) {
        synchronized(registro) {
            registro.add(t)
            while (registro.size > 80) registro.removeAt(0)
        }
        main.post { oyente?.invoke("log", t) }
    }
}

/** Utilidades de texto */
fun normalizar(s: String): String =
    Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

/** Cerebro: habla con Gemini */
object Cerebro {

    private fun personalidad(p: Prefs): String {
        val ahora = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale("es", "AR")).format(Date())
        val n = p.nombre.ifBlank { "Martín" }
        val mem = if (p.memoria.isNotBlank()) "\nDatos que $n te pidió que recuerdes siempre:\n${p.memoria}\n" else ""
        return """Sos JARVIS, un asistente personal de inteligencia artificial al estilo del mayordomo digital de las películas de superhéroes: elegante, leal, muy capaz, educado y con un humor seco e ironía sutil.
Tu usuario se llama $n y vive en Mendoza, Argentina. Llamalo siempre por su nombre, NUNCA le digas "señor".
Hablás en español rioplatense pero con tono formal y refinado de mayordomo (ej: "Por supuesto, $n.", "Entendido, $n. Ya mismo.", "Si me permite la observación, $n...").
Tus respuestas se leen en voz alta: sé breve (1 a 4 oraciones), sin listas largas, sin emojis, sin markdown ni asteriscos, salvo que te pidan algo detallado.
Si no sabés algo o no podés hacerlo, decilo con honestidad y elegancia. No inventes datos.
El texto del usuario viene de un reconocedor de voz y puede tener palabras mal entendidas: interpretá la intención.$mem
Fecha y hora actual: $ahora."""
    }

    class ErrorIA(val codigo: Int, msg: String) : Exception(msg)

    private fun llamar(p: Prefs, modelo: String, hist: JSONArray): String {
        val con = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelo:generateContent")
            .openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.connectTimeout = 15000
        con.readTimeout = 45000
        con.doOutput = true
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        con.setRequestProperty("x-goog-api-key", p.clave)

        val contents = JSONArray()
        val desde = maxOf(0, hist.length() - 20)
        for (i in desde until hist.length()) {
            val m = hist.getJSONObject(i)
            contents.put(JSONObject()
                .put("role", m.getString("r"))
                .put("parts", JSONArray().put(JSONObject().put("text", m.getString("t")))))
        }
        val cuerpo = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", personalidad(p)))))
            .put("contents", contents)
        con.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }

        val codigo = con.responseCode
        val texto = (if (codigo in 200..299) con.inputStream else con.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (codigo !in 200..299) {
            val msg = try { JSONObject(texto).getJSONObject("error").getString("message") } catch (e: Exception) { "Error $codigo" }
            throw ErrorIA(codigo, msg)
        }
        val json = JSONObject(texto)
        val parts = json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        val r = sb.toString().trim()
        if (r.isEmpty()) throw ErrorIA(0, "La IA no devolvió respuesta.")
        return r
    }

    private fun listarModelos(p: Prefs): List<String> {
        val base = listOf("gemini-flash-latest", "gemini-flash-lite-latest")
        return try {
            val con = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
                .openConnection() as HttpURLConnection
            con.setRequestProperty("x-goog-api-key", p.clave)
            con.connectTimeout = 15000
            val arr = JSONObject(con.inputStream.bufferedReader().use { it.readText() }).optJSONArray("models") ?: JSONArray()
            val lista = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val metodos = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                val nombre = m.getString("name").removePrefix("models/")
                if ("generateContent" in metodos && Regex("flash", RegexOption.IGNORE_CASE).containsMatchIn(nombre) &&
                    !Regex("image|tts|live|audio|embed|vision", RegexOption.IGNORE_CASE).containsMatchIn(nombre)) lista.add(nombre)
            }
            val estables = lista.filter { !Regex("preview|exp", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            (base + estables + lista).distinct().take(5)
        } catch (e: Exception) { base }
    }

    /** Llamar SIEMPRE desde un hilo secundario. Devuelve la respuesta o un mensaje de error para decir en voz alta. */
    fun preguntar(ctx: Context, texto: String): String {
        val p = Prefs(ctx)
        if (p.clave.isBlank()) return "Falta la clave de Gemini, ${p.nombre}. Cárguela en los ajustes de la aplicación."
        val hist = try { JSONArray(p.historial) } catch (e: Exception) { JSONArray() }
        hist.put(JSONObject().put("r", "user").put("t", texto))
        return try {
            var resp: String? = null
            try {
                resp = llamar(p, p.modelo, hist)
            } catch (e: ErrorIA) {
                val saturado = e.codigo == 503 || e.codigo == 500 || Regex("high demand|overloaded|unavailable", RegexOption.IGNORE_CASE).containsMatchIn(e.message ?: "")
                val noExiste = e.codigo == 404 || Regex("not found|not supported", RegexOption.IGNORE_CASE).containsMatchIn(e.message ?: "")
                if (!saturado && !noExiste) throw e
                var ultimo: Exception = e
                for (alt in listarModelos(p)) {
                    if (alt == p.modelo) continue
                    try {
                        resp = llamar(p, alt, hist)
                        if (noExiste) p.modelo = alt
                        break
                    } catch (e2: ErrorIA) {
                        ultimo = e2
                        if (e2.codigo == 400 || e2.codigo == 403) throw e2
                    }
                }
                if (resp == null) throw ultimo
            }
            hist.put(JSONObject().put("r", "model").put("t", resp!!))
            // guardar solo los últimos 40 mensajes
            val corto = JSONArray()
            for (i in maxOf(0, hist.length() - 40) until hist.length()) corto.put(hist.get(i))
            p.historial = corto.toString()
            resp!!
        } catch (e: ErrorIA) {
            when {
                e.codigo == 429 -> "Se alcanzó el límite gratuito por ahora, ${p.nombre}. Pruebe de nuevo en un minuto."
                e.codigo == 400 && (e.message ?: "").contains("key", true) -> "La clave de Gemini parece incorrecta. Revísela en los ajustes."
                else -> "Tuve un inconveniente con el servidor: ${e.message}"
            }
        } catch (e: Exception) {
            "No pude conectarme, ${p.nombre}. Revise la conexión a internet."
        }
    }

    fun borrarHistorial(ctx: Context) { Prefs(ctx).historial = "[]" }
}
