package com.midirecording.app.synth

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/**
 * Carpeta de SoundFonts de la app. Incluye GeneralUser GS y guarda una copia
 * de cada .sf2 que añade el usuario.
 */
class SoundFontLibrary(private val context: Context) {
    val dir = File(context.filesDir, "soundfonts").apply { mkdirs() }

    /** Copia el SoundFont incluido la primera vez. */
    fun ensureDefault(): File {
        val target = File(dir, DEFAULT_FILE)
        if (!target.exists() || target.length() == 0L) {
            val tmp = File(dir, "$DEFAULT_FILE.tmp")
            context.assets.open("soundfonts/$DEFAULT_FILE").use { input ->
                tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            tmp.renameTo(target)
        }
        return target
    }

    fun list(): List<String> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".sf2", ignoreCase = true) } ?: emptyArray())
            .map { it.name }
            .sortedWith(compareBy<String>({ it != DEFAULT_FILE }, { it.lowercase() }))

    fun file(name: String) = File(dir, name)

    /** Copia un .sf2 elegido por el usuario y comprueba que se pueda leer. */
    fun import(uri: Uri): String {
        val display = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: "SoundFont.sf2"
        var name = display.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        if (!name.endsWith(".sf2", ignoreCase = true)) name += ".sf2"
        val target = File(dir, name)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("No se pudo abrir el archivo")
        input.use { i -> target.outputStream().use { i.copyTo(it, 1 shl 16) } }
        try {
            SoundFont.load(target)
        } catch (e: Exception) {
            target.delete()
            throw IOException("El archivo no es un SoundFont válido", e)
        }
        return name
    }

    fun delete(name: String): Boolean = name != DEFAULT_FILE && File(dir, name).delete()

    companion object {
        const val DEFAULT_FILE = "GeneralUser-GS.sf2"
    }
}
