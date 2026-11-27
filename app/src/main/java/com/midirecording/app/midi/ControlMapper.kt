package com.midirecording.app.midi

/** Acciones de la app que se pueden asignar a botones del controlador MIDI. */
enum class ControlAction(val label: String) {
    RECORD("Grabar"),
    PLAY_PAUSE("Reproducir / Pausa"),
    STOP("Detener"),
    GO_TO_START("Ir al inicio"),
    METRONOME("Metrónomo encender / apagar"),
    BPM_UP("Subir BPM"),
    BPM_DOWN("Bajar BPM"),
    MUTE("Mute de la pista seleccionada"),
    SOLO("Solo de la pista seleccionada"),
    UNDO("Deshacer"),
}

/** Botón, tecla o pad del controlador: una nota, un control (CC) o un program change. */
data class MidiTrigger(val kind: Kind, val channel: Int, val number: Int) {
    enum class Kind { NOTE, CC, PROGRAM }

    fun describe(): String = when (kind) {
        Kind.NOTE -> "Nota ${com.midirecording.app.model.noteName(number)} (${number}) · canal ${channel + 1}"
        Kind.CC -> "Control CC $number · canal ${channel + 1}"
        Kind.PROGRAM -> "Program change $number · canal ${channel + 1}"
    }

    fun encode(): String = "${kind.name}:$channel:$number"

    companion object {
        fun decode(text: String): MidiTrigger? {
            val parts = text.split(":")
            if (parts.size != 3) return null
            val kind = runCatching { Kind.valueOf(parts[0]) }.getOrNull() ?: return null
            return MidiTrigger(kind, parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null)
        }
    }
}

/**
 * Modo "aprender" y asignación de botones del controlador a acciones.
 * Los eventos asignados no llegan a la grabación.
 */
class ControlMapper(initial: Map<ControlAction, MidiTrigger> = emptyMap()) {

    sealed interface Result {
        /** El evento no está asignado: se graba normalmente. */
        data object PassThrough : Result
        /** Pertenece a un botón asignado pero no dispara nada (soltar el botón). */
        data object Consumed : Result
        data class Triggered(val action: ControlAction) : Result
        data class Learned(val action: ControlAction, val trigger: MidiTrigger) : Result
    }

    private val lock = Any()
    private var bindings: Map<ControlAction, MidiTrigger> = initial
    private var learning: ControlAction? = null
    private val lastCcValue = HashMap<Int, Int>()

    fun bindings(): Map<ControlAction, MidiTrigger> = synchronized(lock) { bindings }

    fun learningAction(): ControlAction? = synchronized(lock) { learning }

    fun startLearning(action: ControlAction?) = synchronized(lock) { learning = action }

    fun clear(action: ControlAction) = synchronized(lock) { bindings = bindings - action }

    fun process(event: MidiEvent): Result = synchronized(lock) {
        val trigger = when (event) {
            is MidiEvent.NoteOn -> MidiTrigger(MidiTrigger.Kind.NOTE, event.channel, event.note)
            is MidiEvent.NoteOff -> MidiTrigger(MidiTrigger.Kind.NOTE, event.channel, event.note)
            is MidiEvent.ControlChange -> MidiTrigger(MidiTrigger.Kind.CC, event.channel, event.controller)
            is MidiEvent.ProgramChange -> MidiTrigger(MidiTrigger.Kind.PROGRAM, event.channel, event.program)
            else -> return@synchronized Result.PassThrough
        }
        val pressed = when (event) {
            is MidiEvent.NoteOn -> true
            is MidiEvent.ProgramChange -> true
            is MidiEvent.ControlChange -> {
                val key = event.channel * 128 + event.controller
                val previous = lastCcValue[key] ?: 0
                lastCcValue[key] = event.value
                event.value >= 64 && previous < 64
            }
            else -> false
        }

        val learn = learning
        if (learn != null) {
            if (!pressed) return@synchronized Result.Consumed
            bindings = bindings.filterValues { it != trigger } + (learn to trigger)
            learning = null
            return@synchronized Result.Learned(learn, trigger)
        }

        val action = bindings.entries.firstOrNull { it.value == trigger }?.key
            ?: return@synchronized Result.PassThrough
        if (pressed) Result.Triggered(action) else Result.Consumed
    }

    companion object {
        fun encode(bindings: Map<ControlAction, MidiTrigger>): String =
            bindings.entries.joinToString(";") { "${it.key.name}=${it.value.encode()}" }

        fun decode(text: String?): Map<ControlAction, MidiTrigger> {
            if (text.isNullOrBlank()) return emptyMap()
            return text.split(";").mapNotNull { entry ->
                val (name, value) = entry.split("=").takeIf { it.size == 2 } ?: return@mapNotNull null
                val action = runCatching { ControlAction.valueOf(name) }.getOrNull() ?: return@mapNotNull null
                val trigger = MidiTrigger.decode(value) ?: return@mapNotNull null
                action to trigger
            }.toMap()
        }
    }
}
