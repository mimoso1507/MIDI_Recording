package com.midirecording.app.data

import com.midirecording.app.model.ControlEvent
import com.midirecording.app.model.Note
import com.midirecording.app.model.Project
import com.midirecording.app.model.Track
import com.midirecording.app.model.TrackGroup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Guarda el proyecto actual en JSON para no perder nada al cerrar la app. */
class ProjectStore(private val file: File) {

    fun load(): Project? = try {
        if (file.exists()) fromJson(file.readText()) else null
    } catch (e: Exception) {
        null
    }

    fun save(project: Project) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson(project))
        tmp.renameTo(file)
    }

    companion object {
        fun toJson(p: Project): String {
            val root = JSONObject()
            root.put("version", 1)
            root.put("bpm", p.bpm)
            root.put("beatsPerBar", p.beatsPerBar)
            root.put("nextTrackId", p.nextTrackId)
            val tracks = JSONArray()
            for (t in p.tracks) {
                val o = JSONObject()
                o.put("id", t.id)
                o.put("name", t.name)
                o.put("channel", t.channel)
                o.put("program", t.program ?: -1)
                o.put("muted", t.muted)
                o.put("solo", t.solo)
                o.put("color", t.colorIndex)
                o.put("group", t.groupId ?: -1)
                val notes = JSONArray()
                t.notes.forEach {
                    notes.put(it.pitch); notes.put(it.velocity); notes.put(it.startTick); notes.put(it.durationTicks)
                }
                o.put("notes", notes)
                val controls = JSONArray()
                t.controls.forEach {
                    controls.put(it.tick); controls.put(it.status); controls.put(it.data1); controls.put(it.data2)
                }
                o.put("controls", controls)
                tracks.put(o)
            }
            root.put("tracks", tracks)
            val groups = JSONArray()
            p.groups.forEach { g -> groups.put(JSONObject().put("id", g.id).put("name", g.name)) }
            root.put("groups", groups)
            root.put("nextGroupId", p.nextGroupId)
            return root.toString()
        }

        fun fromJson(text: String): Project {
            val root = JSONObject(text)
            val arr = root.getJSONArray("tracks")
            val tracks = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val n = o.getJSONArray("notes")
                val notes = (0 until n.length() / 4).map { k ->
                    Note(n.getInt(k * 4), n.getInt(k * 4 + 1), n.getLong(k * 4 + 2), n.getLong(k * 4 + 3))
                }
                val c = o.optJSONArray("controls") ?: JSONArray()
                val controls = (0 until c.length() / 4).map { k ->
                    ControlEvent(c.getLong(k * 4), c.getInt(k * 4 + 1), c.getInt(k * 4 + 2), c.getInt(k * 4 + 3))
                }
                Track(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    channel = o.optInt("channel", 0),
                    program = o.optInt("program", -1).takeIf { it >= 0 },
                    notes = notes,
                    controls = controls,
                    muted = o.optBoolean("muted"),
                    solo = o.optBoolean("solo"),
                    colorIndex = o.optInt("color", 0),
                    groupId = o.optLong("group", -1).takeIf { it >= 0 },
                )
            }
            val g = root.optJSONArray("groups") ?: JSONArray()
            val groups = (0 until g.length()).map { k ->
                val go = g.getJSONObject(k)
                TrackGroup(go.getLong("id"), go.getString("name"))
            }
            return Project(
                bpm = root.optDouble("bpm", 120.0),
                beatsPerBar = root.optInt("beatsPerBar", 4),
                tracks = tracks,
                nextTrackId = root.optLong("nextTrackId", (tracks.maxOfOrNull { it.id } ?: 0L) + 1),
                groups = groups,
                nextGroupId = root.optLong("nextGroupId", (groups.maxOfOrNull { it.id } ?: 0L) + 1),
            )
        }
    }
}
