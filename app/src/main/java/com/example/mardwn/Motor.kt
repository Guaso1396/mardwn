package com.example.mardwn

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
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
import java.io.File

/**
 * Lógica de la app, independiente de la interfaz:
 *  1. Descargar la versión original (sin marca) de un enlace usando yt-dlp.
 *  2. Limpiar la marca incrustada en un video local con el filtro "delogo" de ffmpeg.
 *  3. Guardar el resultado en la galería.
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

    suspend fun descargarSinMarca(
        ctx: Context,
        url: String,
        progreso: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "descargas").apply { deleteRecursively(); mkdirs() }
        val req = YoutubeDLRequest(url).apply {
            addOption("-f", FORMATO_SIN_MARCA)
            addOption("-o", "${dir.absolutePath}/%(extractor)s_%(id)s.%(ext)s")
            addOption("--no-playlist")
            addOption("--no-mtime")
        }
        try {
            YoutubeDL.getInstance().execute(req, ID_PROCESO, false) { p, _, _ -> progreso(p) }
        } catch (e: YoutubeDLException) {
            val msg = e.message.orEmpty()
            throw Exception(
                when {
                    "Requested format is not available" in msg ->
                        "Este sitio no ofrece una versión sin marca para este video. " +
                            "Descárguelo y use la opción 2 (limpiar marca)."
                    "Unsupported URL" in msg -> "Enlace no soportado."
                    else -> "Error de yt-dlp. Pruebe 'Actualizar yt-dlp'.\n\n${msg.takeLast(400)}"
                }
            )
        }
        dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.maxByOrNull { it.length() }
            ?: throw Exception("La descarga terminó pero no se encontró el archivo.")
    }

    fun cancelar() {
        YoutubeDL.getInstance().destroyProcessById(ID_PROCESO)
    }

    /**
     * Zonas típicas de la marca de TikTok (logo + @usuario), en fracciones del ancho/alto
     * y de la duración. La marca suele estar arriba a la izquierda y luego salta abajo a la
     * derecha. SON APROXIMACIONES: ajústelas si en sus videos la marca queda fuera.
     */
    data class Zona(
        val x: Float, val y: Float, val ancho: Float, val alto: Float,
        val desde: Float, val hasta: Float,
    )

    val ZONAS_TIKTOK = listOf(
        Zona(x = 0.02f, y = 0.03f, ancho = 0.50f, alto = 0.12f, desde = 0.0f, hasta = 0.5f),
        Zona(x = 0.48f, y = 0.82f, ancho = 0.50f, alto = 0.13f, desde = 0.5f, hasta = 1.0f),
    )

    /**
     * Reconstruye las zonas con marca usando delogo (interpola desde los bordes).
     * No recupera la imagen original: la zona puede quedar algo borrosa.
     */
    suspend fun quitarMarcaLocal(
        ctx: Context,
        origen: Uri,
        zonas: List<Zona> = ZONAS_TIKTOK,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "limpieza").apply { deleteRecursively(); mkdirs() }
        val entrada = File(dir, "entrada.mp4")
        ctx.contentResolver.openInputStream(origen)?.use { inp ->
            entrada.outputStream().use { inp.copyTo(it) }
        } ?: throw Exception("No se pudo leer el video seleccionado.")

        val (w, h, dur) = medir(entrada)
        val filtros = zonas.joinToString(",") { z ->
            val px = maxOf(1, (z.x * w).toInt())
            val py = maxOf(1, (z.y * h).toInt())
            val pw = minOf((z.ancho * w).toInt(), w - px - 2)
            val ph = minOf((z.alto * h).toInt(), h - py - 2)
            "delogo=x=$px:y=$py:w=$pw:h=$ph" +
                ":enable='between(t,%.2f,%.2f)'".format(java.util.Locale.US, z.desde * dur, z.hasta * dur)
        }
        val salida = File(dir, "limpio.mp4")
        val base = listOf("-y", "-i", entrada.absolutePath, "-vf", filtros)
        val fin = listOf("-c:a", "copy", "-movflags", "+faststart", salida.absolutePath)
        try {
            ejecutarFfmpeg(ctx, base + listOf("-c:v", "libx264", "-crf", "18", "-preset", "veryfast") + fin)
        } catch (e: Exception) {
            // Si el ffmpeg incluido no trae libx264, se usa el codificador mpeg4 interno
            ejecutarFfmpeg(ctx, base + listOf("-c:v", "mpeg4", "-q:v", "2") + fin)
        }
        salida
    }

    private fun medir(f: File): Triple<Int, Int, Double> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t } // ffmpeg rota automáticamente
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (w <= 0 || h <= 0 || ms <= 0) throw Exception("No se pudo leer el tamaño/duración del video.")
            return Triple(w, h, ms / 1000.0)
        } finally {
            r.release()
        }
    }

    /**
     * Ejecuta el binario ffmpeg que trae youtubedl-android.
     * Las rutas replican las que usa la librería internamente (versión 0.18.1):
     * binario en nativeLibraryDir/libffmpeg.so y bibliotecas en noBackupFilesDir/youtubedl-android/packages.
     */
    private fun ejecutarFfmpeg(ctx: Context, args: List<String>) {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val paquetes = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        val pb = ProcessBuilder(listOf(bin.absolutePath, "-hide_banner", "-loglevel", "error") + args)
            .redirectErrorStream(true)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", "$paquetes/python/usr/lib:$paquetes/ffmpeg/usr/lib")
            put("TMPDIR", ctx.cacheDir.absolutePath)
        }
        val proc = pb.start()
        val log = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) throw Exception("ffmpeg falló:\n${log.takeLast(400)}")
    }

    /** Copia el video a Películas/mardwn para que aparezca en la galería. */
    fun guardarEnGaleria(ctx: Context, archivo: File): String {
        val nombre = "mardwn_${System.currentTimeMillis()}.mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = ctx.contentResolver
            val valores = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, nombre)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/mardwn")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val coleccion = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = cr.insert(coleccion, valores) ?: throw Exception("No se pudo crear el archivo en la galería.")
            cr.openOutputStream(uri)?.use { out -> archivo.inputStream().use { it.copyTo(out) } }
            valores.clear()
            valores.put(MediaStore.Video.Media.IS_PENDING, 0)
            cr.update(uri, valores, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "mardwn")
            dir.mkdirs()
            val destino = File(dir, nombre)
            archivo.copyTo(destino, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(destino.absolutePath), arrayOf("video/mp4"), null)
        }
        return "Películas/mardwn/$nombre"
    }
}
