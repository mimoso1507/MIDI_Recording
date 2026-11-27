# MIDI Recording

Grabador MIDI multipista para Android con una interfaz al estilo de Audacity, pensada para la pantalla del móvil.

## Qué hace (versión 0.1)

- Se conecta a la vez a teclados y controladores MIDI por **USB (OTG)** y **Bluetooth LE MIDI**.
- **Graba** lo que tocas (notas, pedal, pitch bend) con un solo botón. Cada toma crea una pista nueva.
- **Reproduce** enviando las notas de vuelta **al mismo dispositivo**, que las toca con sus propios sonidos. También puedes elegir otro dispositivo MIDI conectado.
- Línea de tiempo horizontal con regla de compases, zoom (pellizco o botones), Mute y Solo por pista, y elección de canal e instrumento General MIDI.
- El proyecto se guarda solo.

## Cómo se usa

| Gesto | Acción |
|---|---|
| Tocar la línea de tiempo | Mover el cursor (de ahí empieza a sonar y a grabar) |
| Mantener pulsado y arrastrar | Seleccionar un rango en una o varias pistas |
| Arrastrar con un dedo | Desplazarse |
| Pellizcar o botones +/− | Zoom |
| Tocar el nombre de la pista | Seleccionar la pista entera |

## Próximas versiones

1. Cuantizar la selección o la pista entera, agrupar pistas temporalmente, metrónomo.
2. Modo "aprender" para usar botones del controlador como Rec, Play/Pausa, Stop, metrónomo, BPM +/−…
3. Sintetizador interno con SoundFont (GeneralUser GS incluido y tus propios .sf2) por auriculares o altavoz.
4. Importar/exportar .mid, importar WAV/MP3 para escuchar y exportar la mezcla a WAV/MP3.

## Compilar

Requiere JDK 17 y el SDK de Android (API 35).

```
./gradlew assembleDebug
```

Cada push compila el APK en GitHub Actions; se descarga desde la pestaña **Actions**, en los artefactos del run (`midi-recording-debug-apk`).
