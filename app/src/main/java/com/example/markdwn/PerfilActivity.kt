package com.example.markdwn

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.markdwn.databinding.ActivityPerfilBinding
import com.example.markdwn.databinding.ItemVideoPerfilBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/** Muestra el perfil del creador y sus videos; tocar un video lo descarga sin marca. */
class PerfilActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOMBRE = "nombre"
        const val EXTRA_USUARIO = "usuario"
        const val EXTRA_SECUID = "secUid"
    }

    private lateinit var b: ActivityPerfilBinding
    private val adaptador = AdaptadorVideos()
    private var descargando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPerfilBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = barras.top, bottom = barras.bottom)
            insets
        }

        val nombre = intent.getStringExtra(EXTRA_NOMBRE).orEmpty()
        val usuario = intent.getStringExtra(EXTRA_USUARIO).orEmpty()
        val secUid = intent.getStringExtra(EXTRA_SECUID)

        b.barra.setNavigationOnClickListener { finish() }
        b.barra.title = "@$usuario"
        b.avatar.text = Formato.inicial(nombre.ifBlank { usuario })
        b.nombre.text = nombre.ifBlank { usuario }
        b.usuario.text = "@$usuario"
        b.btnTikTok.setOnClickListener { Acciones.abrirEnTikTok(this, "https://www.tiktok.com/@$usuario") }

        b.lista.layoutManager = GridLayoutManager(this, 3)
        b.lista.adapter = adaptador

        cargar(secUid, usuario, nombre)
    }

    private fun cargar(secUid: String?, usuario: String, nombre: String) {
        b.progreso.visibility = View.VISIBLE
        b.estado.text = "Cargando videos…"
        lifecycleScope.launch {
            try {
                Motor.iniciar(this@PerfilActivity)
                val p = Motor.cargarPerfil(this@PerfilActivity, secUid, usuario, nombre)
                b.nombre.text = p.nombre
                b.avatar.text = Formato.inicial(p.nombre)
                adaptador.descargados = Historial.idsDescargados(this@PerfilActivity)
                adaptador.lista = p.videos
                b.estado.text = if (p.videos.isEmpty()) "No se encontraron videos públicos."
                else "${p.videos.size} videos recientes · toque uno para descargarlo sin marca"
            } catch (e: Exception) {
                b.estado.text = e.message ?: "No se pudo cargar el perfil."
            } finally {
                b.progreso.visibility = View.GONE
            }
        }
    }

    private fun descargar(v: VideoPerfil) {
        if (descargando) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            Snackbar.make(b.root, "Primero descargue un video desde la pantalla principal para conceder el permiso.", Snackbar.LENGTH_LONG).show()
            return
        }
        descargando = true
        b.progreso.visibility = View.VISIBLE
        b.estado.text = "Descargando sin marca de agua…"
        lifecycleScope.launch {
            try {
                val d = Motor.descargar(this@PerfilActivity, v.url) { }
                adaptador.descargados = adaptador.descargados + d.id + v.id
                b.estado.text = "Guardado en Películas/markdwn"
                Snackbar.make(b.root, "Video descargado", Snackbar.LENGTH_LONG)
                    .setAction("Ver") { Acciones.abrirVideo(this@PerfilActivity, d.video) }
                    .show()
            } catch (e: Exception) {
                b.estado.text = e.message ?: "Error"
            } finally {
                descargando = false
                b.progreso.visibility = View.GONE
            }
        }
    }

    private inner class AdaptadorVideos : RecyclerView.Adapter<AdaptadorVideos.VH>() {
        var lista: List<VideoPerfil> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }
        var descargados: Set<String> = emptySet()
            set(v) { field = v; notifyDataSetChanged() }

        inner class VH(val v: ItemVideoPerfilBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemVideoPerfilBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = lista.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val video = lista[pos]
            Imagenes.cargar(h.v.miniatura, video.miniatura, lifecycleScope)
            h.v.vistas.text = Formato.numero(video.vistas)
            h.v.vistas.visibility = if (video.vistas == null) View.GONE else View.VISIBLE
            h.v.descargado.visibility = if (video.id in descargados) View.VISIBLE else View.GONE
            h.v.root.contentDescription = video.titulo
            h.v.root.setOnClickListener { descargar(video) }
        }
    }
}
