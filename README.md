# SinMarca (Android)

App Android (Kotlin) para descargar videos de TikTok sin la marca de agua (logo + @usuario).

## Abrir y ejecutar
1. Android Studio → **File › Open** → seleccione la carpeta `SinMarcaAndroid`.
2. Espere la sincronización de Gradle (descarga AGP 8.7.3, Gradle 8.9 y las librerías).
   Si Android Studio ofrece actualizar AGP, puede aceptar.
3. Conecte un teléfono (Depuración USB activada) o use un emulador y pulse **Run ▶**.

Requisitos: Android Studio Ladybug (2024.2) o posterior, JDK 17 (incluido en Android Studio), teléfono con Android 7.0+.

## Cómo funciona
- **Opción 1 – Enlace:** usa yt-dlp (vía `youtubedl-android` 0.18.1) y descarta los formatos que yt-dlp
  marca como `watermarked` (`download` / `download_addr`). Descarga el stream de reproducción original (`play_addr`), sin marca.
  También acepta enlaces enviados con **Compartir → SinMarca** desde TikTok.
- **Opción 2 – Video local:** aplica el filtro `delogo` de ffmpeg sobre las zonas típicas de la marca
  (`Motor.ZONAS_TIKTOK`). Interpola los píxeles; puede quedar una mancha borrosa. Ajuste las zonas si es necesario.
- Los resultados se guardan en **Películas/SinMarca**.
- **Actualizar yt-dlp:** úselo cuando las descargas fallen; TikTok cambia con frecuencia.

## Archivos principales
- `app/src/main/java/com/example/sinmarca/Motor.kt` – descarga, limpieza ffmpeg, guardado.
- `app/src/main/java/com/example/sinmarca/MainActivity.kt` – interfaz.
- `app/src/main/res/layout/activity_main.xml` – diseño de pantalla.

## Aviso
Use la app solo con videos propios o con permiso del autor.
