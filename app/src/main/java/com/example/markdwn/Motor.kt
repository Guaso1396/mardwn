package com.example.markdwn

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Un video del perfil de un creador. */
data class VideoPerfil(
    val id: String,
    val url: String,
    val titulo: String,
    val miniatura: String?,
    val vistas: Long?,
)

/** Perfil de un creador de TikTok con sus videos más recientes. */
data class Perfil(
    val nombre: String,
    val usuario: String,
    val videos: List<VideoPerfil>,
)

/**
 * Lógica de la app, independiente de la interfaz:
 *  - Descargar la versión original (sin logo de TikTok ni @usuario) de un enlace de TikTok.
 *  - Guardarla en la galería y en el historial.
 *  - Consultar el perfil y los videos de un creador.
 */
object Motor {

    /**
     * Selector de formato de yt-dlp. TikTok ofrece:
     *  - play_addr / play: el stream que se reproduce en la app, SIN marca de agua.
     *  - download_addr / download: el archivo del botón "Guardar video", CON marca.
     * yt-dlp marca estos últimos con format_note "watermarked"; aquí se excluyen.
     * El "?" después del operador conserva los formatos que no tienen format_note.
     */
    private const val FORMATO_SIN_MARCA =
        "best[format_note!*=?watermark][format_id!=download][format_id!=download_addr][vcodec!=none]"

    private const val ID_PROCESO = "descarga"
    private const val VIDEOS_POR_PERFIL = 30
    private val mutexInit = Mutex()
    @Volatile private var iniciado = false

    /** Descomprime Python, yt-dlp y ffmpeg la primera vez (tarda unos segundos). */
    suspend fun iniciar(ctx: Context) = withContext(Dispatchers.IO) {
        mutexInit.withLock {
            if (!iniciado) {
                YoutubeDL.getInstance().init(ctx.applicationContext)
                FFmpeg.getInstance().init(ctx.applicationContext)
                iniciado = true
            }
        }
    }

    /** Actualiza yt-dlp: TikTok cambia con frecuencia y las versiones viejas dejan de funcionar. */
    suspend fun actualizarYtDlp(ctx: Context): String = withContext(Dispatchers.IO) {
        val estado = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext)
        val version = YoutubeDL.getInstance().versionName(ctx.applicationContext) ?: "?"
        if (estado == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "yt-dlp ya está actualizado ($version)"
        else "yt-dlp actualizado a $version"
    }

    /** Acepta enlaces de tiktok.com y los cortos vm./vt.tiktok.com. */
    fun esEnlaceTikTok(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
        return host == "tiktok.com" || host.endsWith(".tiktok.com")
    }

    /**
     * Descarga el video sin marca, lo guarda en la galería y lo agrega al historial.
     * Devuelve la entrada del historial creada.
     */
    suspend fun descargar(
        ctx: Context,
        url: String,
        progreso: (Float) -> Unit,
    ): Descarga = withContext(Dispatchers.IO) {
        if (!esEnlaceTikTok(url)) throw Exception("Solo se aceptan enlaces de TikTok.")
        val dir = File(ctx.cacheDir, "descargas").apply { deleteRecursively(); mkdirs() }
        val req = YoutubeDLRequest(url).apply {
            addOption("-f", FORMATO_SIN_MARCA)
            addOption("-o", "${dir.absolutePath}/%(id)s.%(ext)s")
            addOption("--write-info-json")
            addOption("--write-thumbnail")
            addOption("--no-playlist")
            addOption("--no-mtime")
        }
        try {
            YoutubeDL.getInstance().execute(req, ID_PROCESO, false) { p, _, _ -> progreso(p) }
        } catch (e: YoutubeDLException) {
            throw Exception(traducirError(e.message.orEmpty()))
        }

        val archivos = dir.listFiles()?.toList().orEmpty()
        val infoArchivo = archivos.firstOrNull { it.name.endsWith(".info.json") }
            ?: throw Exception("La descarga terminó pero no se encontró la información del video.")
        val info = JSONObject(infoArchivo.readText())
        val id = info.optString("id").ifEmpty { System.currentTimeMillis().toString() }
        val video = archivos.firstOrNull { it.extension.lowercase() in setOf("mp4", "mov", "webm", "mkv") }
            ?: throw Exception("La descarga terminó pero no se encontró el video.")
        val miniaturaTemp = archivos.firstOrNull {
            it != video && !it.name.endsWith(".json") && it.extension.lowercase() in
                setOf("jpg", "jpeg", "png", "webp", "image", "heic")
        }

        // La miniatura se guarda en almacenamiento privado para el historial
        val miniatura = miniaturaTemp?.let { m ->
            val dest = File(File(ctx.filesDir, "miniaturas").apply { mkdirs() }, "$id.${m.extension}")
            m.copyTo(dest, overwrite = true)
            dest.absolutePath
        }

        val ubicacion = guardarEnGaleria(ctx, video, id)
        val usuario = info.optString("uploader")
        val entrada = Descarga(
            id = id,
            url = info.optString("webpage_url").ifEmpty { url },
            titulo = info.optString("title").ifEmpty { info.optString("description") },
            nombre = info.optString("channel").ifEmpty { usuario },
            usuario = usuario,
            secUid = info.optString("channel_id"),
            miniatura = miniatura,
            video = ubicacion,
            fecha = System.currentTimeMillis(),
        )
        Historial.agregar(ctx, entrada)
        dir.deleteRecursively()
        entrada
    }

    fun cancelar() {
        YoutubeDL.getInstance().destroyProcessById(ID_PROCESO)
    }

    /**
     * Lee los videos recientes de un creador. Se usa el identificador secUid (channel_id)
     * cuando se conoce, porque así yt-dlp no necesita abrir la página del perfil.
     */
    suspend fun cargarPerfil(
        ctx: Context,
        secUid: String?,
        usuario: String,
        nombre: String?,
    ): Perfil = withContext(Dispatchers.IO) {
        val url = if (!secUid.isNullOrBlank()) "tiktokuser:$secUid" else "https://www.tiktok.com/@$usuario"
        val req = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("-J")
            addOption("--playlist-end", VIDEOS_POR_PERFIL)
            addOption("--extractor-retries", 3)
        }
        val salida = try {
            YoutubeDL.getInstance().execute(req, null, false, null).out
        } catch (e: YoutubeDLException) {
            throw Exception(traducirError(e.message.orEmpty()))
        }
        val json = JSONObject(salida)
        val entradas = json.optJSONArray("entries")
        val videos = mutableListOf<VideoPerfil>()
        var nombreLeido: String? = null
        if (entradas != null) {
            for (i in 0 until entradas.length()) {
                val e = entradas.optJSONObject(i) ?: continue
                if (nombreLeido == null) nombreLeido = e.optString("channel").ifEmpty { null }
                val id = e.optString("id")
                val enlace = e.optString("url").ifEmpty { "https://www.tiktok.com/@$usuario/video/$id" }
                videos += VideoPerfil(
                    id = id,
                    url = enlace,
                    titulo = e.optString("title"),
                    miniatura = elegirMiniatura(e),
                    vistas = if (e.has("view_count") && !e.isNull("view_count")) e.optLong("view_count") else null,
                )
            }
        }
        Perfil(
            nombre = nombre?.ifBlank { null } ?: nombreLeido ?: usuario,
            usuario = usuario,
            videos = videos,
        )
    }

    private fun elegirMiniatura(e: JSONObject): String? {
        val lista = e.optJSONArray("thumbnails") ?: return null
        var primera: String? = null
        for (preferida in listOf("cover", "originCover", "thumbnail")) {
            for (i in 0 until lista.length()) {
                val t = lista.optJSONObject(i) ?: continue
                val u = t.optString("url").ifEmpty { null } ?: continue
                if (primera == null) primera = u
                if (t.optString("id") == preferida) return u
            }
        }
        return primera
    }

    private fun traducirError(msg: String): String = when {
        "Requested format is not available" in msg ->
            "TikTok no ofreció una versión sin marca para este video."
        "Unsupported URL" in msg -> "Enlace no soportado. Use un enlace de un video de TikTok."
        "private" in msg.lowercase() -> "La cuenta o el video es privado."
        "Unable to extract secondary user ID" in msg -> "No se pudo abrir el perfil de este creador."
        "does not have any videos" in msg -> "Este creador no tiene videos publicados."
        else -> "Error de yt-dlp. Pruebe 'Actualizar yt-dlp'.\n\n${msg.takeLast(400)}"
    }

    /**
     * Copia el video a Películas/markdwn para que aparezca en la galería.
     * Devuelve un content:// (Android 10+) o la ruta del archivo (Android 9 o anterior).
     */
    private fun guardarEnGaleria(ctx: Context, archivo: File, id: String): String {
        val nombre = "markdwn_${id}_${System.currentTimeMillis()}.mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = ctx.contentResolver
            val valores = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, nombre)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/markdwn")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val coleccion = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = cr.insert(coleccion, valores) ?: throw Exception("No se pudo crear el archivo en la galería.")
            cr.openOutputStream(uri)?.use { out -> archivo.inputStream().use { it.copyTo(out) } }
            valores.clear()
            valores.put(MediaStore.Video.Media.IS_PENDING, 0)
            cr.update(uri, valores, null, null)
            return uri.toString()
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "markdwn")
            dir.mkdirs()
            val destino = File(dir, nombre)
            archivo.copyTo(destino, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(destino.absolutePath), arrayOf("video/mp4"), null)
            return destino.absolutePath
        }
    }
}
