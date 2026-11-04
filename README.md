# markdwn (Android)

App Android (Kotlin) para descargar videos de TikTok sin la marca de agua (logo + @usuario).

## Abrir y ejecutar
1. Android Studio → **File › Open** → seleccione la carpeta `markdwn`.
2. Espere la sincronización de Gradle (descarga AGP 8.7.3, Gradle 8.9 y las librerías).
   Si Android Studio ofrece actualizar AGP, puede aceptar.
3. Conecte un teléfono (Depuración USB activada) o use un emulador y pulse **Run ▶**.

Requisitos: Android Studio Ladybug (2024.2) o posterior, JDK 17 (incluido en Android Studio), teléfono con Android 7.0+.

## Cómo funciona
- **Descargar:** pegue un enlace de TikTok (o use **Compartir → markdwn** desde TikTok). La app usa yt-dlp
  (vía `youtubedl-android` 0.18.1) y descarta los formatos que yt-dlp marca como `watermarked`
  (`download` / `download_addr`): descarga el stream original (`play_addr`), **sin el logo de TikTok ni el @usuario**.
- **Perfil del creador:** después de descargar, toque **Ver perfil del creador** para ver su nombre, su @usuario y
  sus 30 videos más recientes (miniatura y vistas). Tocar un video lo descarga sin marca; los ya descargados llevan ✓.
  yt-dlp no entrega la foto de perfil, por eso se muestra la inicial del nombre.
- **Mis descargas:** pestaña con el historial (miniatura, descripción, @usuario y fecha). Tocar abre el video;
  mantener presionado lo quita de la lista (el video sigue en la galería).
- Los videos se guardan en **Películas/markdwn**.
- **Actualizar yt-dlp:** úselo cuando las descargas fallen; TikTok cambia con frecuencia.

Solo funciona con enlaces de TikTok. No quita marcas de videos que ya estén en la galería.

## Archivos principales
- `app/src/main/java/com/example/markdwn/Motor.kt` – descarga, perfil del creador, guardado en galería.
- `app/src/main/java/com/example/markdwn/Historial.kt` – historial de descargas.
- `app/src/main/java/com/example/markdwn/MainActivity.kt` – pestañas Descargar y Mis descargas.
- `app/src/main/java/com/example/markdwn/PerfilActivity.kt` – perfil y videos del creador.
- `app/src/main/res/layout/activity_main.xml` – diseño de pantalla.

## Todo es gratuito
La app no usa servicios de pago, claves de API ni servidores propios: todo se ejecuta en el teléfono.

| Componente | Uso | Licencia | Costo |
|---|---|---|---|
| Android Studio, Android SDK, Gradle | Desarrollo y compilación | Gratuitos (Gradle: Apache 2.0) | $0 |
| Kotlin, kotlinx-coroutines | Lenguaje y tareas en segundo plano | Apache 2.0 | $0 |
| AndroidX, Material Components | Interfaz | Apache 2.0 | $0 |
| youtubedl-android 0.18.1 | Ejecuta yt-dlp y ffmpeg en Android | GPL-3.0 | $0 |
| yt-dlp | Descarga del video original | Unlicense (dominio público) | $0 |
| FFmpeg | Procesamiento de video que usa yt-dlp | LGPL/GPL | $0 |
| Python (incluido en la librería) | Ejecuta yt-dlp | PSF | $0 |
| GitHub (repositorio público) + GitHub Actions | Código y compilación del APK | — | $0 en repositorios públicos |

Como `youtubedl-android` es GPL-3.0, este proyecto también se distribuye bajo **GPL-3.0** (ver `LICENSE`):
cualquiera puede usar, modificar y compartir el código gratis, siempre que mantenga el código abierto bajo la misma licencia.

Único costo opcional: publicar en Google Play requiere una cuenta de desarrollador de pago único. No es necesario:
el APK se puede instalar directamente desde la pestaña Actions o desde GitHub Releases.

## Aviso
Use la app solo con videos propios o con permiso del autor.

## Descargar e instalar en el celular
1. En el celular, abra **https://github.com/Guaso1396/mardwn/releases/latest**.
2. Descargue **markdwn-arm64-v8a.apk** (si no instala, use `markdwn-armeabi-v7a.apk`).
3. Ábralo y permita "Instalar apps de origen desconocido" cuando Android lo pida.

Cada push a `main` vuelve a compilar y reemplaza los APK de esa página.

## Todo es gratuito
La app no usa servicios de pago, claves de API ni servidores propios: todo se ejecuta en el teléfono.

| Componente | Uso | Licencia | Costo |
|---|---|---|---|
| Android Studio, Android SDK, Gradle | Desarrollo y compilación | Gratuitos (Gradle: Apache 2.0) | $0 |
| Kotlin, kotlinx-coroutines | Lenguaje y tareas en segundo plano | Apache 2.0 | $0 |
| AndroidX, Material Components | Interfaz | Apache 2.0 | $0 |
| youtubedl-android 0.18.1 | Ejecuta yt-dlp y ffmpeg en Android | GPL-3.0 | $0 |
| yt-dlp | Descarga del video original | Unlicense (dominio público) | $0 |
| FFmpeg | Procesamiento de video que usa yt-dlp | LGPL/GPL | $0 |
| Python (incluido en la librería) | Ejecuta yt-dlp | PSF | $0 |
| GitHub (repositorio público) + GitHub Actions | Código y compilación del APK | — | $0 en repositorios públicos |

Como `youtubedl-android` es GPL-3.0, este proyecto también se distribuye bajo **GPL-3.0** (ver `LICENSE`):
cualquiera puede usar, modificar y compartir el código gratis, siempre que mantenga el código abierto bajo la misma licencia.

Único costo opcional: publicar en Google Play requiere una cuenta de desarrollador de pago único. No es necesario:
el APK se puede instalar directamente desde la pestaña Actions o desde GitHub Releases.

## Aviso
Use la app solo con videos propios o con permiso del autor.

## APK compilado automáticamente
Cada push a `main` compila el APK con GitHub Actions. Descárguelo en la pestaña **Actions** → última ejecución → **Artifacts** → `markdwn-debug-apk`.
