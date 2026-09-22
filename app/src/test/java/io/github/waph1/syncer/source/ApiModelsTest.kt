package io.github.waph1.syncer.source

import io.github.waph1.syncer.format.NoteListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ApiModelsTest {

    @Test
    fun parsesTasksResponse() {
        val json = """
            {"kind":"tasks#tasks","items":[
              {"id":"t1","title":"Spesa","notes":"latte","status":"needsAction","due":"2024-01-05T00:00:00.000Z","position":"00000000000000000001","unknown":1},
              {"id":"t2","title":"Fatto","status":"completed","completed":"2024-01-02T23:30:00.000Z","parent":"t1"}
            ],"nextPageToken":"abc"}
        """.trimIndent()
        val response = ApiJson.decodeFromString(TasksResponse.serializer(), json)
        assertEquals("abc", response.nextPageToken)
        val zone = ZoneId.of("Europe/Rome")
        val open = response.items[0].toTodo(zone)
        assertEquals("Spesa", open.title)
        assertEquals(LocalDate.of(2024, 1, 5), open.due)
        assertEquals(false, open.completed)
        val done = response.items[1].toTodo(zone)
        assertTrue(done.completed)
        // 23:30 UTC is already the next day in Rome.
        assertEquals(LocalDate.of(2024, 1, 3), done.completedDate)
        assertEquals("t1", done.parentId)
    }

    @Test
    fun parsesKeepApiNote() {
        val json = """
            {"notes":[{"name":"notes/abc","createTime":"2024-01-01T10:00:00.123456Z","updateTime":"2024-01-02T10:00:00Z",
              "title":"Spesa","body":{"list":{"listItems":[{"text":{"text":"Frutta"},"checked":false,
              "childListItems":[{"text":{"text":"Mele"},"checked":true}]}]}}}]}
        """.trimIndent()
        val note = ApiJson.decodeFromString(KeepNotesResponse.serializer(), json).notes.single().toKeepNote()
        assertEquals("notes/abc", note.id)
        assertEquals(Instant.parse("2024-01-01T10:00:00.123456Z"), note.created)
        assertEquals(listOf(NoteListItem("Frutta", false, listOf(NoteListItem("Mele", true)))), note.listItems)
        assertNull(note.text)
    }

    @Test
    fun parsesTakeoutNote() {
        val json = """
            {"color":"YELLOW","isTrashed":false,"isPinned":true,"isArchived":false,
             "textContent":"Ciao","title":"Promemoria","userEditedTimestampUsec":1704103200000000,
             "createdTimestampUsec":1704099600123456,"labels":[{"name":"Casa"}],
             "annotations":[{"description":"","source":"WEBLINK","title":"Sito","url":"https://example.com"}],
             "attachments":[{"filePath":"1a2b.jpg","mimetype":"image/jpeg"}],
             "textContentHtml":"<p>Ciao</p>"}
        """.trimIndent()
        val note = KeepTakeoutParser.parse(json, "Promemoria")!!
        assertEquals("takeout:1704099600123456", note.id)
        assertEquals(Instant.parse("2024-01-01T09:00:00.123456Z"), note.created)
        assertEquals(Instant.parse("2024-01-01T10:00:00Z"), note.updated)
        assertEquals(listOf("Casa"), note.labels)
        assertEquals("attachments/1a2b.jpg", note.attachments.single().path)
        assertEquals("https://example.com", note.links.single().url)
        assertTrue(note.pinned)
    }

    @Test
    fun trashedTakeoutNoteIsSkipped() {
        assertNull(KeepTakeoutParser.parse("""{"isTrashed":true,"title":"x"}""", "x"))
    }

    @Test
    fun takeoutChecklistAndMissingTimestamps() {
        val note = KeepTakeoutParser.parse("""{"title":"L","listContent":[{"text":"a","isChecked":true},{"text":"b","isChecked":false}]}""", "L")!!
        assertEquals("takeout-file:L", note.id)
        assertEquals(listOf(NoteListItem("a", true), NoteListItem("b", false)), note.listItems)
    }
}
