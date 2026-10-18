# RipTV

Reproductor de listas IPTV (M3U) para Android TV y cajas Android, pensado para usarse con el mando a distancia. Interfaz en español, hecha con Jetpack Compose para TV y reproducción con Media3 (ExoPlayer).

- **Paquete:** `com.riptv.tv`
- **Versión:** 1.1 (`versionCode` 2)
- **Android:** minSdk 23, targetSdk 35
- **Requisitos del dispositivo:** Leanback (Android TV); no necesita pantalla táctil

## Características

### Listas y canales
- Varias listas M3U guardadas, con una activa; se cargan por URL o desde un archivo.
- Las URL de las listas llevan usuario y contraseña, así que se guardan cifradas con una clave AES del almacén de claves de Android (`SecretBox`).
- Añadir una lista desde el móvil o el PC: la app levanta un servidor HTTP temporal en la red local y muestra un código QR con un formulario. La dirección lleva un código aleatorio y se ignora cualquier petición que no lo tenga.
- Actualización de la lista: manual, al iniciar, diaria o semanal.
- Canales organizados por grupos, con orden de canales (el de la lista o alfabético) y orden de grupos (alfabético, el de la lista o personalizado).
- Ocultar grupos y canales concretos, y lista de canales recientes.
- Búsqueda por texto o por voz, en el grupo actual o en todos los canales.
- Opción de arrancar en la lista o en el último canal visto.

### Reproducción
- Cambio de canal con las flechas arriba/abajo o con las teclas de canal, y salto directo tecleando el número.
- Reconexión automática con indicador del intento.
- Perfiles de colchón de vídeo (estabilidad de la reproducción), para aguantar microcortes.
- Formato de imagen: ajustar, ampliar o estirar.
- Panel de pistas de audio y subtítulos.
- Cliente de red OkHttp y agente de usuario configurable.
- Decodificación de audio por software para dispositivos que no la traen: MPEG-1 Layer II (el audio de la TDT) con JLayer, y AC-3, E-AC-3, DTS… con la extensión FFmpeg de Jellyfin.

### Guía de programación (EPG) y logos
- Programa actual y siguiente, y programación de hoy y mañana, pedidos a la API del servidor. El servidor, usuario, contraseña e id del canal se deducen de la propia URL del canal; si el servidor no es de ese tipo, simplemente no hay guía.
- Desfase de la guía ajustable y formato de hora (12/24 h) del dispositivo.
- Logos de canal: primero el que da el proveedor y, si no, el repositorio público [tv-logo/tv-logos](https://github.com/tv-logo/tv-logos) casando por nombre; si no hay ninguno se muestra el número del canal. Se cargan solo para los canales filtrados que están en pantalla.

### Control parental y ajustes
- PIN de 4 dígitos para bloquear grupos. Se desbloquean al introducir el PIN y vuelven a bloquearse al cerrar la app.
- Ajustes: tamaño del texto, número de canal, borrar caché y recientes, restablecer ajustes.
- Informe del último cierre inesperado, que se muestra en el siguiente arranque (en una tele no hay forma cómoda de ver el log).

## Controles del mando

Durante la reproducción:

| Tecla | Acción |
|---|---|
| Arriba / Canal + | Canal anterior |
| Abajo / Canal − | Canal siguiente |
| Izquierda / Menú | Abre el panel de pistas, programación y opciones |
| OK / Enter | Pausa o reanuda (si hay un número tecleado, va a ese canal) |
| Dígitos 0–9 | Teclear el número de canal |

## Compilar

Necesita JDK 17 y el SDK de Android (compileSdk 35). Abre el proyecto en Android Studio o usa Gradle:

```bash
./gradlew assembleDebug      # APK de depuración
./gradlew assembleRelease    # APK firmado (requiere keystore.properties)
```

Para instalar en un emulador o dispositivo conectado:

```bash
./gradlew installDebug
```

> En el emulador de Android TV, arráncalo con `-dns-server 8.8.8.8` si el host de tu lista no se resuelve.

### Firma de la versión release

La firma se lee de `keystore.properties` en la raíz del proyecto, que no se versiona (tampoco `*.jks` ni `local.properties`). Formato:

```properties
storeFile=ruta/al/almacen.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Si el archivo no existe, el build release se genera sin firmar.

## Estructura

Todo el código está en `app/src/main/java/com/riptv/tv/`:

| Archivo | Contenido |
|---|---|
| `MainActivity.kt` | Pantalla principal: grupos, canales, búsqueda |
| `MainViewModel.kt` | Estado de la app y lógica de listas, canales y zapping |
| `PlayerScreen.kt` | Reproductor, banner, panel de pistas y teclas del mando |
| `SettingsScreens.kt` | Menús de ajustes, PIN, listas, grupos y canales ocultos |
| `PlaylistRepository.kt`, `M3uParser.kt`, `Channel.kt` | Descarga, caché y lectura de las listas M3U |
| `Epg.kt`, `Schedule.kt` | Guía de programación |
| `Logos.kt` | Búsqueda y caché de logos |
| `Mp2Audio.kt` | Decodificadores de audio por software |
| `UrlServer.kt` | Servidor local para añadir listas por QR |
| `SecretBox.kt` | Cifrado de URL con el almacén de claves |
| `AppPrefs.kt` | Ajustes persistentes y observables |
| `Dialogs.kt`, `SearchBar.kt` | Componentes de interfaz (diálogos, teclado de PIN, búsqueda) |
| `CrashReport.kt`, `RipTvApp.kt` | Informe de cierres y clase `Application` |

## Tecnologías

Kotlin, Jetpack Compose y `androidx.tv:tv-material`, Media3/ExoPlayer con OkHttp, Coil (imágenes), ZXing (códigos QR), JLayer y la extensión FFmpeg de Jellyfin (audio).

## Aviso

RipTV es solo un reproductor: no incluye canales ni contenido. El usuario es responsable de que las listas que cargue sean legales.
