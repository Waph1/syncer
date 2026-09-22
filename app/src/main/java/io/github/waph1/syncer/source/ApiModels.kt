package io.github.waph1.syncer.source

import io.github.waph1.syncer.format.KeepNote
import io.github.waph1.syncer.format.NoteAttachment
import io.github.waph1.syncer.format.NoteLink
import io.github.waph1.syncer.format.NoteListItem
import io.github.waph1.syncer.format.TodoTask
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

// ---- Google Tasks API v1 -------------------------------------------------------------------

@Serializable
internal data class TaskListsResponse(val items: List<ApiTaskList> = emptyList(), val nextPageToken: String? = null)

@Serializable
internal data class ApiTaskList(val id: String, val title: String = "", val updated: String? = null)

@Serializable
internal data class TasksResponse(val items: List<ApiTask> = emptyList(), val nextPageToken: String? = null)

@Serializable
internal data class ApiTask(
    val id: String,
    val title: String? = null,
    val notes: String? = null,
    val status: String? = null,
    val due: String? = null,
    val completed: String? = null,
    val parent: String? = null,
    val position: String? = null,
    val deleted: Boolean = false,
) {
    fun toTodo(zone: ZoneId): TodoTask = TodoTask(
        id = id,
        title = title.orEmpty(),
        notes = notes,
        completed = status == "completed",
        completedDate = completed?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() },
        due = due?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
        parentId = parent,
        position = position.orEmpty(),
    )
}

// ---- Google Keep API v1 (Google Workspace only) --------------------------------------------

@Serializable
internal data class KeepNotesResponse(val notes: List<ApiNote> = emptyList(), val nextPageToken: String? = null)

@Serializable
internal data class ApiNote(
    val name: String,
    val createTime: String? = null,
    val updateTime: String? = null,
    val trashed: Boolean = false,
    val title: String? = null,
    val body: ApiSection? = null,
) {
    fun toKeepNote(): KeepNote = KeepNote(
        id = name,
        title = title.orEmpty(),
        text = body?.text?.text,
        listItems = body?.list?.listItems?.map { it.toItem() },
        created = createTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
        updated = updateTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
    )
}

@Serializable
internal data class ApiSection(val text: ApiText? = null, val list: ApiList? = null)

@Serializable
internal data class ApiText(val text: String? = null)

@Serializable
internal data class ApiList(val listItems: List<ApiListItem> = emptyList())

@Serializable
internal data class ApiListItem(
    val childListItems: List<ApiListItem> = emptyList(),
    val text: ApiText? = null,
    val checked: Boolean = false,
) {
    fun toItem(): NoteListItem = NoteListItem(text?.text.orEmpty(), checked, childListItems.map { it.toItem() })
}

// ---- Google Takeout export of Keep ---------------------------------------------------------

@Serializable
internal data class TakeoutNote(
    val color: String? = null,
    val isTrashed: Boolean = false,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val textContent: String? = null,
    val title: String? = null,
    val userEditedTimestampUsec: Long? = null,
    val createdTimestampUsec: Long? = null,
    val labels: List<TakeoutLabel> = emptyList(),
    val listContent: List<TakeoutListItem>? = null,
    val annotations: List<TakeoutAnnotation> = emptyList(),
    val attachments: List<TakeoutAttachment> = emptyList(),
)

@Serializable
internal data class TakeoutLabel(val name: String = "")

@Serializable
internal data class TakeoutListItem(val text: String = "", val isChecked: Boolean = false)

@Serializable
internal data class TakeoutAnnotation(val url: String? = null, val title: String? = null, val source: String? = null)

@Serializable
internal data class TakeoutAttachment(val filePath: String = "", val mimetype: String? = null)

object KeepTakeoutParser {
    const val ATTACHMENTS_DIR = "attachments"

    /**
     * Parses one note JSON from a Google Takeout "Keep" folder. Returns null for trashed notes.
     * [fileBaseName] (JSON file name without extension) is used as id when timestamps are missing.
     */
    fun parse(json: String, fileBaseName: String): KeepNote? {
        val n = ApiJson.decodeFromString(TakeoutNote.serializer(), json)
        if (n.isTrashed) return null
        val id = n.createdTimestampUsec?.let { "takeout:$it" } ?: "takeout-file:$fileBaseName"
        return KeepNote(
            id = id,
            title = n.title.orEmpty(),
            text = n.textContent,
            listItems = n.listContent?.map { NoteListItem(it.text, it.isChecked) },
            created = n.createdTimestampUsec?.let(::usecToInstant),
            updated = n.userEditedTimestampUsec?.let(::usecToInstant),
            labels = n.labels.map { it.name }.filter { it.isNotBlank() },
            pinned = n.isPinned,
            archived = n.isArchived,
            color = n.color,
            attachments = n.attachments.filter { it.filePath.isNotBlank() }
                .map { NoteAttachment("$ATTACHMENTS_DIR/${it.filePath.substringAfterLast('/')}", it.mimetype) },
            links = n.annotations.mapNotNull { a -> a.url?.takeIf { it.isNotBlank() }?.let { NoteLink(it, a.title) } },
        )
    }

    private fun usecToInstant(usec: Long): Instant =
        Instant.ofEpochSecond(usec / 1_000_000, (usec % 1_000_000) * 1_000)
}
