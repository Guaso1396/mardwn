package com.example.markdwn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Un video descargado, tal como se guarda en el historial. */
data class Descarga(
    val id: String,
    val url: String,
    val titulo: String,
    val nombre: String,
    val usuario: String,
    val secUid: String,
    val miniatura: String?,
    /** content:// (Android 10+) o ruta del archivo (Android 9 o anterior). */
    val video: String,
    val fecha: Long,
) {
    fun aJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("titulo", titulo); put("nombre", nombre)
        put("usuario", usuario); put("secUid", secUid); put("miniatura", miniatura ?: "")
        put("video", video); put("fecha", fecha)
    }

    companion object {
        fun deJson(o: JSONObject) = Descarga(
            id = o.optString("id"),
            url = o.optString("url"),
            titulo = o.optString("titulo"),
            nombre = o.optString("nombre"),
            usuario = o.optString("usuario"),
            secUid = o.optString("secUid"),
            miniatura = o.optString("miniatura").ifEmpty { null },
            video = o.optString("video"),
            fecha = o.optLong("fecha"),
        )
    }
}

/** Historial de descargas guardado como JSON en el almacenamiento privado de la app. */
object Historial {
    private fun archivo(ctx: Context) = File(ctx.filesDir, "historial.json")

    @Synchronized
    fun todas(ctx: Context): List<Descarga> {
        val f = archivo(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Descarga::deJson) }
        } catch (e: Exception) {
            emptyList()
        }.sortedByDescending { it.fecha }
    }

    @Synchronized
    fun agregar(ctx: Context, d: Descarga) {
        guardar(ctx, listOf(d) + todas(ctx))
    }

    @Synchronized
    fun quitar(ctx: Context, d: Descarga) {
        guardar(ctx, todas(ctx).filterNot { it.fecha == d.fecha && it.id == d.id })
        d.miniatura?.let { m -> if (todas(ctx).none { it.miniatura == m }) File(m).delete() }
    }

    /** IDs ya descargados, para marcarlos en el perfil del creador. */
    fun idsDescargados(ctx: Context): Set<String> = todas(ctx).map { it.id }.toSet()

    private fun guardar(ctx: Context, lista: List<Descarga>) {
        val arr = JSONArray()
        lista.forEach { arr.put(it.aJson()) }
        val tmp = File(ctx.filesDir, "historial.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(archivo(ctx))
    }
}
