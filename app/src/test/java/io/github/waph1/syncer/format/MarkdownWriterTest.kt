package io.github.waph1.syncer.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class MarkdownWriterTest {

    @Test
    fun writesFrontMatterTextAndLinks() {
        val note = KeepNote(
            id = "n1",
            title = "Idee \"regalo\"",
            text = "Prima riga\nSeconda riga\n",
            created = Instant.parse("2024-01-01T10:00:00.123Z"),
            updated = Instant.parse("2024-01-02T11:00:00Z"),
            labels = listOf("Personale", "Casa"),
            pinned = true,
            archived = true,
            color = "RED",
            links = listOf(NoteLink("https://example.com/a b", "Esempio [1]")),
            attachments = listOf(NoteAttachment("attachments/foto 1.jpg", "image/jpeg"), NoteAttachment("attachments/audio.3gp", "audio/3gpp")),
        )
        assertEquals(
            """
            ---
            title: "Idee \"regalo\""
            created: 2024-01-01T10:00:00Z
            updated: 2024-01-02T11:00:00Z
            tags:
              - "Casa"
              - "Personale"
            pinned: true
            archived: true
            color: "red"
            source: google-keep
            ---

            Prima riga
            Seconda riga

            ## Link

            - [Esempio \[1\]](https://example.com/a%20b)

            ## Allegati

            ![foto 1.jpg](attachments/foto%201.jpg)
            - [audio.3gp](attachments/audio.3gp)

            """.trimIndent(),
            MarkdownWriter.write(note),
        )
    }

    @Test
    fun writesChecklistWithUncheckedFirstAndNestedItems() {
        val note = KeepNote(
            id = "n2",
            title = "",
            listItems = listOf(
                NoteListItem("Pane", checked = true),
                NoteListItem("Frutta", checked = false, children = listOf(NoteListItem("Mele", false), NoteListItem("Pere", true))),
                NoteListItem("Latte\nintero", checked = false),
            ),
            color = "DEFAULT",
        )
        assertEquals(
            """
            ---
            source: google-keep
            ---

            - [ ] Frutta
                - [ ] Mele
                - [x] Pere
            - [ ] Latte
                  intero
            - [x] Pane

            """.trimIndent(),
            MarkdownWriter.write(note),
        )
    }
}
