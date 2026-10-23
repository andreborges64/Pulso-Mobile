package com.pulso.esportes
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
@Suppress("DEPRECATION")
class MainActivity : Activity() {
    companion object {
        private const val TAG = "PULSO"
        private const val HOST = "appassets.androidplatform.net"
        private const val URL = "https://appassets.androidplatform.net/assets/index.html"
        private const val REQUEST_LOCATION = 8301
        private const val REQUEST_DOWNLOAD = 8302
        private const val REQUEST_UPLOAD = 8303
        private const val REQUEST_SYSTEM_SOUND = 8304
        private const val PREFS_NAME = "pulso_preferences"
        private const val PREF_SYSTEM_SOUND_URI = "system_sound_uri"
    }
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var navegador: WebView
    private var permissaoGps: GeolocationPermissions.Callback? = null
    private var origemGps: String? = null
    private var retornoArquivo: ValueCallback<Array<Uri>>? = null
    private var arquivoPendente: ByteArray? = null
    private var alarmeAtual: AudioTrack? = null
    private var toqueSistemaAtual: Ringtone? = null
    private var geracaoAlarme = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0A0F12.toInt()
        window.navigationBarColor = 0xFF0A0F12.toInt()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        navegador = WebView(this)
        navegador.setBackgroundColor(0xFF0A0F12.toInt())
        setContentView(navegador)
        navegador.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            allowContentAccess = false
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        navegador.webViewClient = ClienteLocal()
        navegador.webChromeClient = ClienteWeb()
        // A ponte nativa é destinada à página local do aplicativo.
        navegador.addJavascriptInterface(PonteAndroid(), "PulsoAndroid")
        if (savedInstanceState != null) navegador.restoreState(savedInstanceState) else navegador.loadUrl(URL)
    }
    private fun origemPermitida(url: String?): Boolean {
        return try { val uri = Uri.parse(url); uri.scheme.equals("https", true) && uri.host.equals(HOST, true) } catch (_: Exception) { false }
    }
    private fun mime(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".json") || path.endsWith(".webmanifest") -> "application/json"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        else -> "application/octet-stream"
    }
    private inner class ClienteLocal : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
            val uri = request.url
            if (!origemPermitida(uri.toString())) return null
            val path = uri.encodedPath ?: return respostaVazia()
            if (!path.startsWith("/assets/")) return respostaVazia()
            val item = Uri.decode(path.removePrefix("/assets/"))
            if (item.contains("..") || item.startsWith("/") || item.contains("\\")) return respostaVazia()
            return try { WebResourceResponse(mime(item), "UTF-8", assets.open("web/$item")) } catch (_: IOException) { respostaVazia() }
        }
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
            if (origemPermitida(request.url.toString())) return false
            if (!request.isForMainFrame) return false
            val uri = request.url
            if (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) {
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) { Toast.makeText(this@MainActivity, "Navegador indisponível", Toast.LENGTH_SHORT).show() }
            }
            return true
        }
        private fun respostaVazia() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
    }
    private inner class ClienteWeb : WebChromeClient() {
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            if (!origemPermitida(origin)) { callback.invoke(origin, false, false); return }
            val permitido = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (permitido) { callback.invoke(origin, true, false); return }
            origemGps = origin
            permissaoGps = callback
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
        }
        override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
            retornoArquivo?.onReceiveValue(null)
            retornoArquivo = callback
            return try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }
                startActivityForResult(intent, REQUEST_UPLOAD)
                true
            } catch (_: Exception) { retornoArquivo?.onReceiveValue(null); retornoArquivo = null; false }
        }
        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
            if (!origemPermitida(url)) { result.cancel(); return true }
            AlertDialog.Builder(this@MainActivity).setMessage(message).setPositiveButton("Sim") { _, _ -> result.confirm() }.setNegativeButton("Cancelar") { _, _ -> result.cancel() }.setCancelable(false).show()
            return true
        }
        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
            AlertDialog.Builder(this@MainActivity).setMessage(message).setPositiveButton("OK") { _, _ -> result.confirm() }.setCancelable(false).show()
            return true
        }
        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            if (!origemPermitida(url)) { result.cancel(); return true }
            val input = EditText(this@MainActivity).apply { isSingleLine = true; setText(defaultValue.orEmpty()) }
            AlertDialog.Builder(this@MainActivity).setTitle(message).setView(input).setPositiveButton("OK") { _, _ -> result.confirm(input.text.toString()) }.setNegativeButton("Cancelar") { _, _ -> result.cancel() }.setCancelable(false).show()
            return true
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            permissaoGps?.invoke(origemGps, grantResults.any { it == PackageManager.PERMISSION_GRANTED }, false)
            permissaoGps = null
            origemGps = null
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SYSTEM_SOUND && resultCode == RESULT_OK) {
            val uri = ringtoneResultUri(data) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (uri != null) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_SYSTEM_SOUND_URI, uri.toString()).apply()
                val title = try { RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "Som do celular" } catch (_: Exception) { "Som do celular" }
                navegador.evaluateJavascript("window.PulsoAndroidSoundSelected&&window.PulsoAndroidSoundSelected(${JSONObject.quote(title)});", null)
            }
        }
        if (requestCode == REQUEST_UPLOAD) {
            retornoArquivo?.onReceiveValue(if (resultCode == RESULT_OK && data?.data != null) arrayOf(data.data!!) else null)
            retornoArquivo = null
        }
        if (requestCode == REQUEST_DOWNLOAD) {
            val bytes = arquivoPendente
            arquivoPendente = null
            val destino = data?.data
            if (resultCode == RESULT_OK && destino != null && bytes != null) {
                executor.execute {
                    try {
                        contentResolver.openOutputStream(destino)?.use { it.write(bytes); it.flush() } ?: throw IOException("Arquivo indisponível")
                        runOnUiThread { Toast.makeText(this, "Arquivo salvo com sucesso", Toast.LENGTH_SHORT).show() }
                    } catch (error: Exception) { Log.e(TAG, "Falha ao salvar arquivo", error); runOnUiThread { Toast.makeText(this, "Falha ao salvar arquivo", Toast.LENGTH_LONG).show() } }
                }
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun ringtoneResultUri(data: Intent?): Uri? = if (Build.VERSION.SDK_INT >= 33) {
        data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
    } else {
        data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
    }
    private fun chooseSystemSound() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val existingUri = prefs.getString(PREF_SYSTEM_SOUND_URI, null)?.let(Uri::parse) ?: defaultUri
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Escolha o som dos alertas")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, defaultUri)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
        }
        try {
            startActivityForResult(intent, REQUEST_SYSTEM_SOUND)
        } catch (_: ActivityNotFoundException) {
            navegador.evaluateJavascript("window.PulsoAndroidSoundPickerFailed&&window.PulsoAndroidSoundPickerFailed();", null)
        }
    }
    inner class PonteAndroid {
        @JavascriptInterface fun keepScreenOn(active: Boolean) {
            runOnUiThread { if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        @JavascriptInterface fun playAlert(kind: String?, sound: Boolean, vibrate: Boolean, strong: Boolean, useSystemSound: Boolean) { runOnUiThread { tocarAlarme(kind, sound, vibrate, strong, useSystemSound) } }
        @JavascriptInterface fun chooseSystemSound() { runOnUiThread { this@MainActivity.chooseSystemSound() } }
        @JavascriptInterface fun saveFile(filename: String?, mimeType: String?, contentBase64: String?) {
            if (filename.isNullOrBlank() || contentBase64 == null || contentBase64.length > 20 * 1024 * 1024) return
            try {
                val bytes = Base64.decode(contentBase64, Base64.DEFAULT)
                if (bytes.size > 15 * 1024 * 1024) return
                val name = filename.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val fileType = if (mimeType?.matches(Regex("[A-Za-z0-9.+_-]+/[A-Za-z0-9.+_-]+")) == true) mimeType else "application/octet-stream"
                runOnUiThread {
                    if (arquivoPendente != null) { Toast.makeText(this@MainActivity, "Conclua o arquivo anterior", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                    arquivoPendente = bytes
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = fileType; putExtra(Intent.EXTRA_TITLE, name) }
                    try { startActivityForResult(intent, REQUEST_DOWNLOAD) } catch (_: Exception) { arquivoPendente = null; Toast.makeText(this@MainActivity, "Não foi possível salvar", Toast.LENGTH_SHORT).show() }
                }
            } catch (_: IllegalArgumentException) { Log.w(TAG, "Arquivo base64 inválido") }
        }
    }
    private fun tocarAlarme(tipo: String?, som: Boolean, vibrar: Boolean, forte: Boolean, usarSomSistema: Boolean) {
        val urgente = tipo in listOf("round_end", "round_start", "complete", "goal")
        if (vibrar) {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) {
                val pattern = if (urgente) { if (forte) longArrayOf(0, 360, 150, 360, 150, 500, 160, 670) else longArrayOf(0, 240, 120, 240, 100, 350) } else longArrayOf(0, 150, 80, 150)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            }
        }
        if (!som) return
        val generation = ++geracaoAlarme
        try { alarmeAtual?.stop(); alarmeAtual?.release() } catch (_: Exception) {}
        alarmeAtual = null
        try { toqueSistemaAtual?.stop() } catch (_: Exception) {}
        toqueSistemaAtual = null
        if (usarSomSistema && tocarToqueSistema()) return
        // O alerta forte usa pulsos alternados e o canal de volume de alarmes do Android.
        executor.execute {
            val sampleRate = 22050
            val duration = if (urgente) { if (forte) 3200 else 1900 } else 650
            val samples = ShortArray(duration * sampleRate / 1000)
            val pulses = if (urgente) { if (forte) arrayOf(intArrayOf(0,280,740),intArrayOf(430,280,1000),intArrayOf(850,310,780),intArrayOf(1270,410,1120),intArrayOf(1830,330,900),intArrayOf(2350,600,1260)) else arrayOf(intArrayOf(0,260,760),intArrayOf(420,280,1050),intArrayOf(870,510,1190)) } else arrayOf(intArrayOf(0,170,720),intArrayOf(280,210,930))
            for (pulse in pulses) {
                val begin = pulse[0] * sampleRate / 1000
                val end = min(samples.size, (pulse[0] + pulse[1]) * sampleRate / 1000)
                for (index in begin until end) {
                    val t = (index - begin).toDouble() / sampleRate
                    val fade = min(1.0, min((index-begin) / (sampleRate * .012), (end-index) / (sampleRate * .035)))
                    val value = (.68 * sin(2 * PI * pulse[2] * t) + .22 * sin(4 * PI * pulse[2] * t)) * fade
                    samples[index] = (value.coerceIn(-1.0,1.0) * 24000).toInt().toShort()
                }
            }
            runOnUiThread {
                if (generation != geracaoAlarme) return@runOnUiThread
                try {
                    val audio = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size*2).build()
                    if (audio.write(samples,0,samples.size) <= 0) { audio.release(); return@runOnUiThread }
                    alarmeAtual = audio
                    audio.play()
                    navegador.postDelayed({ if (generation == geracaoAlarme) { try { audio.stop(); audio.release() } catch (_: Exception) {}; if (alarmeAtual === audio) alarmeAtual = null } }, duration + 240L)
                } catch (error: Exception) { Log.e(TAG,"Falha ao tocar alarme",error) }
            }
        }
    }
    private fun tocarToqueSistema(): Boolean {
        val savedUri = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_SYSTEM_SOUND_URI, null)
        val uri = savedUri?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return false
        return try {
            val ringtone = RingtoneManager.getRingtone(this, uri) ?: return false
            ringtone.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            ringtone.play()
            toqueSistemaAtual = ringtone
            true
        } catch (error: Exception) {
            Log.w(TAG, "Não foi possível reproduzir o toque selecionado", error)
            false
        }
    }
    @Deprecated("Compatibilidade com a navegação do Android")
    override fun onBackPressed() {
        navegador.evaluateJavascript("(function(){var d=document.getElementById('activity-dialog');if(d&&d.open){d.close();return true}return false})()") { result ->
            if (result != "true") AlertDialog.Builder(this).setTitle("Sair do PULSO?").setMessage("Treinos em andamento podem parar se você sair do aplicativo.").setNegativeButton("Continuar treino") { _, _ -> }.setPositiveButton("Sair") { _, _ -> finish() }.show()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); navegador.saveState(outState) }
    override fun onDestroy() { geracaoAlarme++; try { alarmeAtual?.stop(); alarmeAtual?.release(); toqueSistemaAtual?.stop() } catch (_: Exception) {}; executor.shutdownNow(); navegador.destroy(); super.onDestroy() }
}
