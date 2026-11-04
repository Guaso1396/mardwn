package com.example.markdwn

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.markdwn.databinding.ActivityMainBinding
import com.example.markdwn.databinding.ItemHistorialBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var tarea: Job? = null
    private var accionPendiente: (() -> Unit)? = null
    private val adaptador = AdaptadorHistorial()

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
        b.btnCancelar.setOnClickListener { Motor.cancelar(); tarea?.cancel() }
        b.btnActualizar.setOnClickListener { actualizar() }

        b.listaHistorial.layoutManager = LinearLayoutManager(this)
        b.listaHistorial.adapter = adaptador

        b.navegacion.setOnItemSelectedListener { item ->
            mostrarPestana(item.itemId == R.id.nav_historial)
            true
        }

        manejarCompartido(intent)
        iniciarMotor()
    }

    override fun onResume() {
        super.onResume()
        refrescarHistorial()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        manejarCompartido(intent)
    }

    private fun mostrarPestana(historial: Boolean) {
        b.vistaDescargar.visibility = if (historial) View.GONE else View.VISIBLE
        b.vistaHistorial.visibility = if (historial) View.VISIBLE else View.GONE
        b.barra.title = if (historial) getString(R.string.mis_descargas) else getString(R.string.app_name)
        if (historial) refrescarHistorial()
    }

    private fun refrescarHistorial() {
        val lista = Historial.todas(this)
        adaptador.lista = lista
        b.tvHistorialVacio.visibility = if (lista.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Recibe el enlace cuando el usuario usa "Compartir" en TikTok y elige esta app. */
    private fun manejarCompartido(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            extraerUrl(intent.getStringExtra(Intent.EXTRA_TEXT))?.let {
                b.etUrl.setText(it)
                b.navegacion.selectedItemId = R.id.nav_descargar
            }
        }
    }

    private fun extraerUrl(texto: String?): String? =
        texto?.let { Regex("""https?://\S+""").find(it)?.value }

    private fun iniciarMotor() {
        ocupado(true, "Preparando el motor (solo tarda la primera vez)…")
        lifecycleScope.launch {
            try {
                Motor.iniciar(this@MainActivity)
                ocupado(false, "Listo. Pegue un enlace de TikTok.")
            } catch (e: Exception) {
                ocupado(false, "No se pudo iniciar el motor: ${e.message}")
                b.btnDescargar.isEnabled = false
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
        if (url == null || !Motor.esEnlaceTikTok(url)) {
            b.tilUrl.error = "Pegue un enlace de TikTok"; return
        }
        b.tilUrl.error = null
        conPermisoDeGuardado {
            ocupado(true, "Descargando sin marca de agua…", cancelable = true)
            b.tarjetaResultado.visibility = View.GONE
            tarea = lifecycleScope.launch {
                try {
                    val d = Motor.descargar(this@MainActivity, url) { p ->
                        runOnUiThread {
                            b.progreso.isIndeterminate = p <= 0f
                            b.progreso.setProgressCompat(p.toInt().coerceIn(0, 100), true)
                        }
                    }
                    ocupado(false, "Guardado en Películas/markdwn")
                    mostrarResultado(d)
                    refrescarHistorial()
                } catch (e: Exception) {
                    ocupado(false, if (tarea?.isCancelled == true) "Cancelado." else e.message ?: "Error")
                }
            }
        }
    }

    private fun mostrarResultado(d: Descarga) {
        b.tarjetaResultado.visibility = View.VISIBLE
        Imagenes.cargar(b.resMiniatura, d.miniatura, lifecycleScope)
        b.resTitulo.text = d.titulo.ifBlank { "(sin descripción)" }
        b.resAutor.text = "${d.nombre} · @${d.usuario}"
        b.resAvatar.text = Formato.inicial(d.nombre)
        b.btnResVer.setOnClickListener { Acciones.abrirVideo(this, d.video) }
        b.btnResPerfil.setOnClickListener { Acciones.abrirPerfil(this, d.nombre, d.usuario, d.secUid) }
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

    private fun ocupado(si: Boolean, mensaje: String, cancelable: Boolean = false) {
        estado(mensaje)
        b.progreso.visibility = if (si) View.VISIBLE else View.GONE
        b.progreso.isIndeterminate = true
        b.btnCancelar.visibility = if (si && cancelable) View.VISIBLE else View.GONE
        listOf(b.btnDescargar, b.btnActualizar, b.btnPegar).forEach { it.isEnabled = !si }
    }

    private fun estado(m: String) { b.tvEstado.text = m }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun confirmarQuitar(d: Descarga) {
        AlertDialog.Builder(this)
            .setTitle("Quitar del historial")
            .setMessage("El video seguirá en la galería; solo se quita de esta lista.")
            .setPositiveButton("Quitar") { _, _ -> Historial.quitar(this, d); refrescarHistorial() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private inner class AdaptadorHistorial : RecyclerView.Adapter<AdaptadorHistorial.VH>() {
        var lista: List<Descarga> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }

        inner class VH(val v: ItemHistorialBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemHistorialBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = lista.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val d = lista[pos]
            Imagenes.cargar(h.v.miniatura, d.miniatura, lifecycleScope)
            h.v.titulo.text = d.titulo.ifBlank { "(sin descripción)" }
            h.v.autor.text = "@${d.usuario}"
            h.v.fecha.text = Formato.fecha(d.fecha)
            h.v.root.setOnClickListener { Acciones.abrirVideo(this@MainActivity, d.video) }
            h.v.root.setOnLongClickListener { confirmarQuitar(d); true }
            h.v.btnPerfil.setOnClickListener {
                Acciones.abrirPerfil(this@MainActivity, d.nombre, d.usuario, d.secUid)
            }
        }
    }
}
