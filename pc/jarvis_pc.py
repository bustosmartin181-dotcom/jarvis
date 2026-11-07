# -*- coding: utf-8 -*-
"""
JARVIS PC - Asistente que mira la pantalla y da consejos útiles.
Hecho para Martín (Mendoza, Argentina). Funciona en Windows.

- Cada X segundos saca una captura de la pantalla (solo si cambió algo).
- Se la manda a Gemini, que entiende qué estás haciendo.
- Si tiene un consejo útil, lo muestra en la ventanita flotante (y lo dice en voz alta si querés).
- Botón de PAUSA: mientras está en pausa NO saca capturas ni manda nada.
"""

import base64
import io
import json
import os
import queue
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime

import mss
from PIL import Image, ImageChops, ImageStat

VERSION = "1.0"
CARPETA = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "JarvisPC")
ARCHIVO_CFG = os.path.join(CARPETA, "config.json")

CFG_DEFECTO = {
    "clave": "",
    "nombre": "Martín",
    "intervalo": 45,            # segundos entre capturas
    "hablar": True,             # decir los consejos en voz alta
    "contexto": "Estoy programando y armando aplicaciones (Android, páginas web, GitHub) con ayuda de Claude. "
                "No soy programador profesional.",
    "memoria": "",
    "modelo": "gemini-flash-latest",
    "insistencia": "normal",    # poca | normal | mucha
}


# ======================= Configuración =======================
def cargar_cfg():
    cfg = dict(CFG_DEFECTO)
    try:
        with open(ARCHIVO_CFG, "r", encoding="utf-8") as f:
            cfg.update(json.load(f))
    except Exception:
        pass
    return cfg


def guardar_cfg(cfg):
    os.makedirs(CARPETA, exist_ok=True)
    with open(ARCHIVO_CFG, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)


# ======================= Pantalla =======================
def capturar():
    """Captura el monitor principal y la achica (máx 1280 px de ancho)."""
    with mss.mss() as s:
        mon = s.monitors[1]
        img = s.grab(mon)
        im = Image.frombytes("RGB", img.size, img.bgra, "raw", "BGRX")
    if im.width > 1280:
        alto = int(im.height * 1280 / im.width)
        im = im.resize((1280, alto), Image.LANCZOS)
    return im


def cuanto_cambio(a, b):
    """0 = igual, 100 = totalmente distinta."""
    if a is None or b is None:
        return 100.0
    pa = a.resize((160, 90)).convert("L")
    pb = b.resize((160, 90)).convert("L")
    dif = ImageChops.difference(pa, pb)
    return ImageStat.Stat(dif).mean[0] / 2.55


def a_jpeg_b64(im):
    buf = io.BytesIO()
    im.save(buf, format="JPEG", quality=70)
    return base64.b64encode(buf.getvalue()).decode("ascii")


# ======================= Gemini =======================
class ErrorIA(Exception):
    def __init__(self, codigo, msg):
        super().__init__(msg)
        self.codigo = codigo


def _post(cfg, modelo, cuerpo):
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{modelo}:generateContent"
    req = urllib.request.Request(url, data=json.dumps(cuerpo).encode("utf-8"), method="POST",
                                 headers={"Content-Type": "application/json", "x-goog-api-key": cfg["clave"]})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            data = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            msg = json.loads(e.read().decode("utf-8"))["error"]["message"]
        except Exception:
            msg = f"Error {e.code}"
        raise ErrorIA(e.code, msg)
    except Exception as e:
        raise ErrorIA(0, f"Sin conexión: {e}")
    try:
        partes = data["candidates"][0]["content"]["parts"]
        return "".join(p.get("text", "") for p in partes).strip()
    except Exception:
        raise ErrorIA(0, "La IA no devolvió respuesta.")


def _listar_modelos(cfg):
    base = ["gemini-flash-latest", "gemini-flash-lite-latest"]
    try:
        req = urllib.request.Request("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200",
                                     headers={"x-goog-api-key": cfg["clave"]})
        with urllib.request.urlopen(req, timeout=20) as r:
            modelos = json.loads(r.read().decode("utf-8")).get("models", [])
        lista = []
        for m in modelos:
            n = m["name"].replace("models/", "")
            if "generateContent" in m.get("supportedGenerationMethods", []) and "flash" in n.lower() \
                    and not any(x in n.lower() for x in ["image", "tts", "live", "audio", "embed"]):
                lista.append(n)
        estables = [n for n in lista if "preview" not in n and "exp" not in n]
        vistos, res = set(), []
        for n in base + estables + lista:
            if n not in vistos:
                vistos.add(n)
                res.append(n)
        return res[:5]
    except Exception:
        return base


def preguntar_gemini(cfg, cuerpo):
    """Llama a Gemini; si el modelo está saturado o no existe, prueba con otros."""
    try:
        return _post(cfg, cfg["modelo"], cuerpo)
    except ErrorIA as e:
        m = str(e).lower()
        saturado = e.codigo in (500, 503) or "high demand" in m or "overloaded" in m or "unavailable" in m
        no_existe = e.codigo == 404 or "not found" in m or "not supported" in m
        if not (saturado or no_existe):
            raise
        ultimo = e
        for alt in _listar_modelos(cfg):
            if alt == cfg["modelo"]:
                continue
            try:
                r = _post(cfg, alt, cuerpo)
                if no_existe:
                    cfg["modelo"] = alt
                    guardar_cfg(cfg)
                return r
            except ErrorIA as e2:
                ultimo = e2
                if e2.codigo in (400, 403):
                    raise
        raise ultimo


def instrucciones(cfg, consejos_previos):
    n = cfg.get("nombre") or "Martín"
    umbral = {
        "poca": "Hablá SOLO si ves un error claro, un problema serio o algo que le va a hacer perder mucho tiempo.",
        "normal": "Hablá cuando tengas un consejo concreto y realmente útil para lo que está haciendo ahora.",
        "mucha": "Podés dar consejos, atajos y mejoras con más frecuencia, siempre que sean útiles.",
    }.get(cfg.get("insistencia", "normal"))
    prev = "\n".join(f"- {c}" for c in consejos_previos[-6:]) or "(ninguno todavía)"
    mem = f"\nDatos de {n}: {cfg['memoria']}" if cfg.get("memoria") else ""
    ahora = datetime.now().strftime("%d/%m/%Y %H:%M")
    return f"""Sos JARVIS, el asistente personal de {n}, al estilo del mayordomo digital de las películas: elegante, leal, preciso, con humor seco.
Estás viendo una captura de la pantalla de su computadora (Windows) mientras trabaja. Hablás en español rioplatense, con tono refinado. Llamalo por su nombre, nunca "señor".
Contexto que te dio {n}: {cfg.get('contexto','')}{mem}
Fecha y hora: {ahora}.

Tu tarea: mirar la pantalla, entender qué está haciendo y decidir si vale la pena interrumpirlo con UN consejo.
{umbral}
Ejemplos de cosas útiles: un error visible en pantalla y cómo solucionarlo, un paso que se está salteando, un riesgo (por ejemplo una clave o contraseña a la vista, borrar algo importante), un atajo que le ahorra tiempo, una mejor forma de hacer lo que está haciendo.
NO describas la pantalla. NO repitas consejos que ya diste. NO interrumpas por cosas obvias o triviales.
Si no hay nada realmente útil para decir, respondé exactamente: NADA
Si hay algo, respondé en 1 a 3 oraciones cortas, directo al grano, sin markdown ni emojis.

Consejos que ya diste hace poco (no los repitas):
{prev}"""


def analizar_pantalla(cfg, im, consejos_previos):
    cuerpo = {
        "systemInstruction": {"parts": [{"text": instrucciones(cfg, consejos_previos)}]},
        "contents": [{"role": "user", "parts": [
            {"inline_data": {"mime_type": "image/jpeg", "data": a_jpeg_b64(im)}},
            {"text": "Esta es mi pantalla ahora. ¿Tenés algún consejo útil? Si no, respondé NADA."},
        ]}],
        "generationConfig": {"temperature": 0.4, "maxOutputTokens": 300},
    }
    r = preguntar_gemini(cfg, cuerpo).strip()
    limpio = r.strip(" .\n\"'*").upper()
    if not r or limpio == "NADA" or limpio.startswith("NADA"):
        return None
    return r.replace("*", "").strip()


def pregunta_directa(cfg, im, texto, consejos_previos):
    cuerpo = {
        "systemInstruction": {"parts": [{"text": instrucciones(cfg, consejos_previos) +
                                         "\n\nAHORA el usuario te hace una pregunta directa: respondela siempre (no digas NADA), "
                                         "usando la captura si sirve. Respuesta clara y breve (hasta 6 oraciones)."}]},
        "contents": [{"role": "user", "parts": [
            {"inline_data": {"mime_type": "image/jpeg", "data": a_jpeg_b64(im)}},
            {"text": texto},
        ]}],
        "generationConfig": {"temperature": 0.5, "maxOutputTokens": 600},
    }
    return preguntar_gemini(cfg, cuerpo).replace("*", "").strip()


# ======================= Voz =======================
class Voz:
    def __init__(self):
        self.cola = queue.Queue()
        threading.Thread(target=self._bucle, daemon=True).start()

    def decir(self, texto):
        self.cola.put(texto)

    def _bucle(self):
        while True:
            texto = self.cola.get()
            try:
                import pyttsx3
                motor = pyttsx3.init()
                for v in motor.getProperty("voices"):
                    nombre = (getattr(v, "name", "") + " " + str(getattr(v, "languages", ""))).lower()
                    if "spanish" in nombre or "español" in nombre or "es-" in nombre or "es_" in nombre:
                        motor.setProperty("voice", v.id)
                        break
                motor.setProperty("rate", 175)
                motor.say(texto)
                motor.runAndWait()
                motor.stop()
            except Exception:
                pass


# ======================= Vigilante (hilo de trabajo) =======================
class Vigilante:
    def __init__(self, cfg, avisar):
        self.cfg = cfg
        self.avisar = avisar            # función(tipo, texto) para la ventana
        self.pausado = False
        self.ultima = None
        self.consejos = []
        self.proxima = time.time() + 5
        self.pedidos = queue.Queue()
        threading.Thread(target=self._bucle, daemon=True).start()

    def preguntar(self, texto):
        self.pedidos.put(texto)

    def _bucle(self):
        while True:
            try:
                texto = self.pedidos.get(timeout=1)
            except queue.Empty:
                texto = None
            try:
                if texto:
                    self._responder(texto)
                elif not self.pausado and time.time() >= self.proxima:
                    self.proxima = time.time() + max(15, int(self.cfg.get("intervalo", 45)))
                    self._mirar()
            except ErrorIA as e:
                self._error(e)
            except Exception as e:
                self.avisar("estado", f"Error: {e}")

    def _error(self, e):
        if e.codigo == 429:
            self.avisar("estado", "Límite gratis alcanzado; espero un rato")
            self.proxima = time.time() + 120
        elif (e.codigo == 400 and "key" in str(e).lower()) or e.codigo == 403:
            self.avisar("consejo", "La clave de Gemini parece incorrecta. Revísela en Ajustes (⚙).")
            self.pausado = True
            self.avisar("pausa", "1")
        else:
            self.avisar("estado", f"Error: {e}")

    def _mirar(self):
        if not self.cfg.get("clave"):
            self.avisar("estado", "Falta la clave de Gemini (⚙)")
            return
        im = capturar()
        cambio = cuanto_cambio(self.ultima, im)
        if cambio < 1.5:
            self.avisar("estado", "Observando (sin cambios)")
            return
        self.ultima = im
        self.avisar("estado", "Analizando la pantalla...")
        consejo = analizar_pantalla(self.cfg, im, self.consejos)
        if consejo:
            self.consejos.append(consejo)
            self.consejos = self.consejos[-20:]
            self.avisar("consejo", consejo)
        self.avisar("estado", "Observando · " + datetime.now().strftime("%H:%M"))

    def _responder(self, texto):
        if not self.cfg.get("clave"):
            self.avisar("consejo", "Primero cargue la clave de Gemini en Ajustes (⚙).")
            return
        self.avisar("estado", "Pensando...")
        im = capturar()
        r = pregunta_directa(self.cfg, im, texto, self.consejos)
        self.avisar("consejo", r)
        self.avisar("estado", "Observando" if not self.pausado else "En pausa")


# ======================= Ventana =======================
def iniciar_ventana():
    import tkinter as tk
    from tkinter import messagebox

    CIAN, FONDO, CAJA, TEXTO = "#29d8ff", "#020814", "#06182a", "#d9f6ff"
    cfg = cargar_cfg()
    voz = Voz()
    eventos = queue.Queue()

    root = tk.Tk()
    root.title("JARVIS")
    root.overrideredirect(True)
    root.attributes("-topmost", True)
    try:
        root.attributes("-alpha", 0.95)
    except Exception:
        pass
    root.configure(bg=FONDO)

    ancho, alto = 380, 230
    sw, sh = root.winfo_screenwidth(), root.winfo_screenheight()
    root.geometry(f"{ancho}x{alto}+{sw - ancho - 20}+{sh - alto - 70}")

    marco = tk.Frame(root, bg=FONDO, highlightbackground=CIAN, highlightthickness=1)
    marco.pack(fill="both", expand=True)

    # --- barra superior (también sirve para arrastrar la ventana) ---
    barra = tk.Frame(marco, bg=FONDO)
    barra.pack(fill="x", padx=8, pady=(6, 0))
    lienzo = tk.Canvas(barra, width=26, height=26, bg=FONDO, highlightthickness=0)
    lienzo.pack(side="left")
    anillo = lienzo.create_oval(2, 2, 24, 24, outline=CIAN, width=2)
    centro = lienzo.create_oval(8, 8, 18, 18, fill=CIAN, outline="")
    tk.Label(barra, text="J.A.R.V.I.S.", fg=CIAN, bg=FONDO, font=("Segoe UI", 11, "bold")).pack(side="left", padx=6)

    def boton(padre, texto, cmd):
        b = tk.Label(padre, text=texto, fg=CIAN, bg=FONDO, font=("Segoe UI", 11), cursor="hand2", padx=4)
        b.bind("<Button-1>", lambda e: cmd())
        return b

    estado = tk.Label(marco, text="Iniciando...", fg="#7fe9ff", bg=FONDO, font=("Segoe UI", 8), anchor="w")
    estado.pack(fill="x", padx=10)

    texto = tk.Label(marco, text=f"Buenas, {cfg['nombre']}. Estoy mirando; si veo algo útil, le aviso.",
                     fg=TEXTO, bg=CAJA, font=("Segoe UI", 10), wraplength=ancho - 30, justify="left", anchor="nw",
                     padx=8, pady=6)
    texto.pack(fill="both", expand=True, padx=8, pady=4)

    fila = tk.Frame(marco, bg=FONDO)
    fila.pack(fill="x", padx=8, pady=(0, 8))
    entrada = tk.Entry(fila, bg=CAJA, fg=TEXTO, insertbackground=TEXTO, relief="flat", font=("Segoe UI", 10))
    entrada.pack(side="left", fill="x", expand=True, ipady=4)
    entrada.insert(0, "Preguntale algo sobre tu pantalla...")
    entrada.bind("<FocusIn>", lambda e: entrada.delete(0, "end") if entrada.get().startswith("Preguntale") else None)

    vig = Vigilante(cfg, lambda t, x: eventos.put((t, x)))

    def enviar(_=None):
        t = entrada.get().strip()
        if t and not t.startswith("Preguntale"):
            entrada.delete(0, "end")
            texto.config(text=f"Vos: {t}")
            vig.preguntar(t)
    entrada.bind("<Return>", enviar)
    boton(fila, "➤", enviar).pack(side="left", padx=(6, 0))

    lbl_pausa = boton(barra, "⏸", lambda: alternar_pausa())
    lbl_pausa.pack(side="right")

    def alternar_pausa(forzar=None):
        vig.pausado = (not vig.pausado) if forzar is None else forzar
        lbl_pausa.config(text="▶" if vig.pausado else "⏸")
        estado.config(text="EN PAUSA · no estoy mirando" if vig.pausado else "Observando")
        lienzo.itemconfig(centro, fill="#ffb340" if vig.pausado else CIAN)
        if not vig.pausado:
            vig.proxima = time.time() + 2

    def cerrar():
        if messagebox.askyesno("JARVIS", "¿Cerrar JARVIS?"):
            root.destroy()
    boton(barra, "✕", cerrar).pack(side="right")
    boton(barra, "⚙", lambda: abrir_ajustes()).pack(side="right")
    boton(barra, "—", lambda: minimizar()).pack(side="right")

    # --- minimizar a una bolita ---
    def minimizar():
        marco.pack_forget()
        mini.pack(fill="both", expand=True)
        root.geometry(f"60x60+{sw - 80}+{sh - 130}")

    mini = tk.Canvas(root, width=60, height=60, bg=FONDO, highlightthickness=0, cursor="hand2")
    mini.create_oval(6, 6, 54, 54, outline=CIAN, width=2)
    mini_c = mini.create_oval(20, 20, 40, 40, fill=CIAN, outline="")

    def restaurar(_=None):
        mini.pack_forget()
        marco.pack(fill="both", expand=True)
        root.geometry(f"{ancho}x{alto}+{sw - ancho - 20}+{sh - alto - 70}")
    mini.bind("<Double-Button-1>", restaurar)

    # --- arrastrar ---
    pos = {"x": 0, "y": 0}
    def ini_arrastre(e): pos.update(x=e.x_root - root.winfo_x(), y=e.y_root - root.winfo_y())
    def arrastrar(e): root.geometry(f"+{e.x_root - pos['x']}+{e.y_root - pos['y']}")
    for w in (barra, mini):
        w.bind("<Button-1>", ini_arrastre, add="+")
        w.bind("<B1-Motion>", arrastrar)

    # --- ajustes ---
    def abrir_ajustes():
        v = tk.Toplevel(root)
        v.title("JARVIS · Ajustes")
        v.configure(bg=FONDO)
        v.attributes("-topmost", True)
        v.geometry(f"460x620+{max(0, sw//2-230)}+{max(0, sh//2-330)}")

        def etiqueta(t):
            tk.Label(v, text=t, fg="#7fe9ff", bg=FONDO, font=("Segoe UI", 9), anchor="w").pack(fill="x", padx=14, pady=(10, 2))

        def campo(valor, ocultar=False):
            e = tk.Entry(v, bg=CAJA, fg=TEXTO, insertbackground=TEXTO, relief="flat", font=("Segoe UI", 10),
                         show="•" if ocultar else "")
            e.insert(0, str(valor))
            e.pack(fill="x", padx=14, ipady=4)
            return e

        etiqueta("Clave de API de Gemini (gratis en aistudio.google.com/apikey)")
        e_clave = campo(cfg["clave"], ocultar=True)
        etiqueta("Tu nombre")
        e_nombre = campo(cfg["nombre"])
        etiqueta("¿Qué hacés normalmente en esta PC? (le da contexto)")
        t_ctx = tk.Text(v, height=4, bg=CAJA, fg=TEXTO, insertbackground=TEXTO, relief="flat", font=("Segoe UI", 10), wrap="word")
        t_ctx.insert("1.0", cfg["contexto"]); t_ctx.pack(fill="x", padx=14)
        etiqueta("Memoria permanente (datos tuyos que Jarvis siempre sabe)")
        t_mem = tk.Text(v, height=3, bg=CAJA, fg=TEXTO, insertbackground=TEXTO, relief="flat", font=("Segoe UI", 10), wrap="word")
        t_mem.insert("1.0", cfg["memoria"]); t_mem.pack(fill="x", padx=14)
        etiqueta("Cada cuántos segundos mira la pantalla (mínimo 15)")
        e_int = campo(cfg["intervalo"])

        etiqueta("¿Cuánto habla?")
        var_ins = tk.StringVar(value=cfg.get("insistencia", "normal"))
        fila_i = tk.Frame(v, bg=FONDO); fila_i.pack(fill="x", padx=14)
        for val, txt in (("poca", "Poco"), ("normal", "Normal"), ("mucha", "Mucho")):
            tk.Radiobutton(fila_i, text=txt, variable=var_ins, value=val, bg=FONDO, fg=TEXTO, selectcolor=CAJA,
                           activebackground=FONDO, activeforeground=CIAN).pack(side="left", padx=(0, 12))

        var_hablar = tk.BooleanVar(value=cfg["hablar"])
        tk.Checkbutton(v, text="Decir los consejos en voz alta", variable=var_hablar, bg=FONDO, fg=TEXTO,
                       selectcolor=CAJA, activebackground=FONDO, activeforeground=CIAN).pack(anchor="w", padx=14, pady=(10, 0))

        def guardar():
            cfg["clave"] = e_clave.get().strip()
            cfg["nombre"] = e_nombre.get().strip() or "Martín"
            cfg["contexto"] = t_ctx.get("1.0", "end").strip()
            cfg["memoria"] = t_mem.get("1.0", "end").strip()
            try:
                cfg["intervalo"] = max(15, int(e_int.get().strip()))
            except ValueError:
                cfg["intervalo"] = 45
            cfg["insistencia"] = var_ins.get()
            cfg["hablar"] = bool(var_hablar.get())
            guardar_cfg(cfg)
            v.destroy()
            if vig.pausado and cfg["clave"]:
                alternar_pausa(False)

        tk.Button(v, text="Guardar", command=guardar, bg=CIAN, fg="#001122", relief="flat",
                  font=("Segoe UI", 11, "bold"), cursor="hand2").pack(fill="x", padx=14, pady=16, ipady=4)

    # --- animación del núcleo y eventos ---
    paso = {"n": 0}
    def latir():
        paso["n"] = (paso["n"] + 1) % 20
        r = 3 + abs(10 - paso["n"]) / 5
        lienzo.coords(centro, 13 - r, 13 - r, 13 + r, 13 + r)
        mini.coords(mini_c, 30 - r * 2, 30 - r * 2, 30 + r * 2, 30 + r * 2)
        root.after(80, latir)

    def procesar_eventos():
        try:
            while True:
                tipo, x = eventos.get_nowait()
                if tipo == "estado":
                    estado.config(text=x)
                elif tipo == "consejo":
                    texto.config(text=x)
                    if mini.winfo_ismapped():
                        restaurar()
                    if cfg.get("hablar"):
                        voz.decir(x)
                elif tipo == "pausa":
                    alternar_pausa(True)
        except queue.Empty:
            pass
        root.after(200, procesar_eventos)

    latir()
    procesar_eventos()
    if not cfg["clave"]:
        alternar_pausa(True)
        texto.config(text=f"Hola, {cfg['nombre']}. Para empezar toque ⚙ y pegue la clave de Gemini.")
        root.after(800, abrir_ajustes)
    else:
        estado.config(text="Observando")
    root.mainloop()


if __name__ == "__main__":
    iniciar_ventana()
