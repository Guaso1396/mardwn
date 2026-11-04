package com.example.markdwn

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AttributeSet
import android.util.LruCache
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Cargador de imágenes mínimo (sin librerías externas) con caché en memoria. */
object Imagenes {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cargar(vista: ImageView, origen: String?, scope: CoroutineScope) {
        vista.tag = origen
        vista.setImageDrawable(null)
        if (origen.isNullOrBlank()) return
        cache.get(origen)?.let { vista.setImageBitmap(it); return }
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { decodificar(origen) }.getOrNull() }
            if (bmp != null) {
                cache.put(origen, bmp)
                if (vista.tag == origen) vista.setImageBitmap(bmp)
            }
        }
    }

    private fun decodificar(origen: String): Bitmap? {
        val bytes = if (origen.startsWith("http")) {
            val c = URL(origen).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            c.setRequestProperty("Referer", "https://www.tiktok.com/")
            try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
        } else {
            File(origen).takeIf { it.exists() }?.readBytes() ?: return null
        }
        val opciones = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opciones)
        var muestra = 1
        while (opciones.outWidth / (muestra * 2) >= 360) muestra *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = muestra })
    }
}

object Formato {
    fun numero(n: Long?): String = when {
        n == null -> ""
        n >= 1_000_000 -> String.format(Locale.US, "%.1f M", n / 1_000_000.0).replace(".0 ", " ")
        n >= 1_000 -> String.format(Locale.US, "%.1f K", n / 1_000.0).replace(".0 ", " ")
        else -> n.toString()
    }

    fun fecha(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    fun inicial(nombre: String): String =
        nombre.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
}

object Acciones {
    /** Abre un video guardado (content:// o ruta de archivo) con el reproductor del teléfono. */
    fun abrirVideo(ctx: Context, video: String) {
        val uri = if (video.startsWith("content://")) Uri.parse(video)
        else FileProvider.getUriForFile(ctx, "${ctx.packageName}.archivos", File(video))
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { ctx.startActivity(i) } catch (e: Exception) {
            Toast.makeText(ctx, "No se pudo abrir el video (¿lo borró de la galería?)", Toast.LENGTH_SHORT).show()
        }
    }

    fun abrirEnTikTok(ctx: Context, url: String) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
    }

    fun abrirPerfil(ctx: Context, nombre: String, usuario: String, secUid: String) {
        ctx.startActivity(Intent(ctx, PerfilActivity::class.java).apply {
            putExtra(PerfilActivity.EXTRA_NOMBRE, nombre)
            putExtra(PerfilActivity.EXTRA_USUARIO, usuario)
            putExtra(PerfilActivity.EXTRA_SECUID, secUid)
        })
    }
}

/** Contenedor que mantiene la proporción 3:4 de las portadas de TikTok. */
class Proporcion34 @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null,
) : FrameLayout(ctx, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(w * 4 / 3, MeasureSpec.EXACTLY),
        )
    }
}
