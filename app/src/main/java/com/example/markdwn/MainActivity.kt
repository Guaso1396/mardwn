package com.example.markdwn

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.markdwn.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var ultimoArchivo: File? = null
    private var tarea: Job? = null
    private var accionPendiente: (() -> Unit)? = null

    private val elegirVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) limpiarVideo(uri)
    }

    private val permisoAlmacenamiento =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            if (concedido) accionPendiente?.invoke()
            else estado("Sin permiso de almacenamiento no se puede guardar en la galería.")
            accionPendiente = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Android 15 dibuja bajo las barras del sistema: se agrega margen interno
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(top = barras.top, bottom = barras.bottom)
            insets
        }

        b.btnPegar.setOnClickListener { pegarDelPortapapeles() }
        b.btnDescargar.setOnClickListener { descargar() }
        b.btnElegir.setOnClickListener { elegirVideo.launch("video/*") }
        b.btnVer.setOnClickListener { verUltimo() }
        b.btnCancelar.setOnClickListener { Motor.cancelar(); tarea?.cancel() }
        b.btnActualizar.setOnClickListener { actualizar() }

        manejarCompartido(intent)
        iniciarMotor()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        manejarCompartido(intent)
    }

    /** Recibe el enlace cuando el usuario usa "Compartir" en TikTok y elige esta app. */
    private fun manejarCompartido(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            extraerUrl(intent.getStringExtra(Intent.EXTRA_TEXT))?.let { b.etUrl.setText(it) }
        }
    }

    private fun extraerUrl(texto: String?): String? =
        texto?.let { Regex("""https?://\S+""").find(it)?.value }

    private fun iniciarMotor() {
        ocupado(true, "Preparando el motor (solo tarda la primera vez)…")
        lifecycleScope.launch {
            try {
                Motor.iniciar(this@MainActivity)
                ocupado(false, "Listo. Pegue un enlace o elija un video.")
            } catch (e: Exception) {
                ocupado(false, "No se pudo iniciar el motor: ${e.message}")
                b.btnDescargar.isEnabled = false
                b.btnElegir.isEnabled = false
            }
        }
    }

    private fun pegarDelPortapapeles() {
        val cm = getSystemService(ClipboardManager::class.java)
        val texto = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        val url = extraerUrl(texto)
        if (url != null) b.etUrl.setText(url) else toast("No hay un enlace en el portapapeles")
    }

    private fun descargar() {
        val url = extraerUrl(b.etUrl.text?.toString())
        if (url == null) { b.tilUrl.error = "Escriba un enlace válido"; return }
        b.tilUrl.error = null
        conPermisoDeGuardado {
            ocupado(true, "Descargando versión sin marca…")
            tarea = lifecycleScope.launch {
                try {
                    val f = Motor.descargarSinMarca(this@MainActivity, url) { p ->
                        runOnUiThread {
                            b.progreso.isIndeterminate = p <= 0f
                            b.progreso.setProgressCompat(p.toInt().coerceIn(0, 100), true)
                        }
                    }
                    terminar(f)
                } catch (e: Exception) {
                    ocupado(false, if (tarea?.isCancelled == true) "Cancelado." else e.message ?: "Error")
                }
            }
        }
    }

    private fun limpiarVideo(uri: Uri) {
        conPermisoDeGuardado {
            ocupado(true, "Quitando la marca (puede tardar según la duración)…")
            tarea = lifecycleScope.launch {
                try {
                    terminar(Motor.quitarMarcaLocal(this@MainActivity, uri))
                } catch (e: Exception) {
                    ocupado(false, e.message ?: "Error")
                }
            }
        }
    }

    private suspend fun terminar(archivo: File) {
        ultimoArchivo = archivo
        estado("Guardando en la galería…")
        val ruta = withContext(Dispatchers.IO) { Motor.guardarEnGaleria(this@MainActivity, archivo) }
        ocupado(false, "Guardado en $ruta")
        b.btnVer.visibility = View.VISIBLE
    }

    private fun actualizar() {
        ocupado(true, "Actualizando yt-dlp…")
        lifecycleScope.launch {
            val msg = try { Motor.actualizarYtDlp(this@MainActivity) } catch (e: Exception) {
                "No se pudo actualizar: ${e.message}"
            }
            ocupado(false, msg)
        }
    }

    private fun verUltimo() {
        val f = ultimoArchivo ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.archivos", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) } catch (e: Exception) { toast("No hay un reproductor disponible") }
    }

    /** En Android 9 o anterior se necesita permiso para escribir en la galería. */
    private fun conPermisoDeGuardado(accion: () -> Unit) {
        val permiso = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, permiso) == PackageManager.PERMISSION_GRANTED
        ) {
            accion()
        } else {
            accionPendiente = accion
            permisoAlmacenamiento.launch(permiso)
        }
    }

    private fun ocupado(si: Boolean, mensaje: String) {
        estado(mensaje)
        b.progreso.visibility = if (si) View.VISIBLE else View.GONE
        b.progreso.isIndeterminate = true
        b.btnCancelar.visibility = if (si) View.VISIBLE else View.GONE
        listOf(b.btnDescargar, b.btnElegir, b.btnActualizar, b.btnPegar).forEach { it.isEnabled = !si }
        if (si) b.btnVer.visibility = View.GONE
    }

    private fun estado(m: String) { b.tvEstado.text = m }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
