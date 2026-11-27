# MIDI Recording

Grabador MIDI multipista para Android con una interfaz al estilo de Audacity, pensada para la pantalla del móvil.

## Qué hace (versión 0.2)

- Se conecta a la vez a teclados y controladores MIDI por **USB (OTG)** y **Bluetooth LE MIDI**.
- **Graba** lo que tocas (notas, pedal, pitch bend) con un solo botón. Cada toma crea una pista nueva.
- **Reproduce** enviando las notas de vuelta **al mismo dispositivo**, que las toca con sus propios sonidos. También puedes elegir otro dispositivo MIDI conectado.
- Línea de tiempo horizontal con regla de compases, zoom (pellizco o botones), Mute y Solo por pista, y elección de canal e instrumento General MIDI.
- **Cuantizar** la selección o la pista entera (1/4, 1/8, 1/16, 1/32 y tresillos), con opción de ajustar también el final de las notas.
- **Agrupar** dos o más pistas en una sola fila para ahorrar pantalla, y desagruparlas después sin perder nada. Mute y Solo del grupo actúan sobre todas sus pistas.
- **Metrónomo** por el audio del teléfono, con el primer tiempo del compás más agudo.
- **Deshacer** cualquier cambio.
- El proyecto se guarda solo.

## Cómo se usa

| Gesto | Acción |
|---|---|
| Tocar la línea de tiempo | Mover el cursor (de ahí empieza a sonar y a grabar) |
| Mantener pulsado y arrastrar | Seleccionar un rango en una o varias pistas |
| Arrastrar con un dedo | Desplazarse |
| Pellizcar o botones +/− | Zoom |
| Tocar el nombre de la pista | Seleccionar la pista entera |
| Con algo seleccionado | Aparece la barra: Cuantizar, Agrupar, Borrar notas |

## Próximas versiones

1. Modo "aprender" para usar botones del controlador como Rec, Play/Pausa, Stop, metrónomo, BPM +/−…
2. Sintetizador interno con SoundFont (GeneralUser GS incluido y tus propios .sf2) por auriculares o altavoz.
3. Importar/exportar .mid, importar WAV/MP3 para escuchar y exportar la mezcla a WAV/MP3.

## Compilar

Requiere JDK 17 y el SDK de Android (API 35).

```
./gradlew assembleDebug
```

Cada push compila el APK en GitHub Actions; se descarga desde la pestaña **Actions**, en los artefactos del run (`midi-recording-debug-apk`).
