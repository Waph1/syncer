package io.github.waph1.syncer.source

import android.content.ContentResolver
import android.database.Cursor
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.SipAddress
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import io.github.waph1.syncer.format.VAddress
import io.github.waph1.syncer.format.VContact
import io.github.waph1.syncer.format.VDate
import io.github.waph1.syncer.format.VEventKind
import io.github.waph1.syncer.format.VLabeled
import io.github.waph1.syncer.format.VName
import io.github.waph1.syncer.format.VOrganization
import java.util.Locale

/** Reads the contacts stored in a Google account from Android's ContactsContract provider. */
class ContactsSource(private val resolver: ContentResolver) {

    fun read(account: String, includePhotos: Boolean): List<VContact> {
        val raw = linkedMapOf<Long, Builder>()
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.STARRED, RawContacts.DISPLAY_NAME_PRIMARY),
            "${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.DELETED}=0",
            arrayOf(CalendarSource.GOOGLE_ACCOUNT_TYPE, account), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                raw[id] = Builder(uid = c.str(1) ?: "rawcontact-$id", starred = c.intOrNull(2) == 1, displayName = c.str(3))
            }
        }
        if (raw.isEmpty()) return emptyList()
        val groups = groups(account)

        val projection = arrayOf(Data.RAW_CONTACT_ID, Data.MIMETYPE, Data.IS_PRIMARY) + DATA_COLUMNS
        for (chunk in raw.keys.toList().chunked(500)) {
            resolver.query(Data.CONTENT_URI, projection, inClause(Data.RAW_CONTACT_ID, chunk), null, null)?.use { c ->
                while (c.moveToNext()) {
                    val builder = raw[c.getLong(0)] ?: continue
                    val mime = c.str(1) ?: continue
                    val primary = c.intOrNull(2) == 1
                    readRow(builder, mime, primary, DataRow(c, 3), groups, includePhotos)
                }
            }
        }
        return raw.values.map { it.build() }
    }

    private fun groups(account: String): Map<Long, String> {
        val result = mutableMapOf<Long, String>()
        resolver.query(
            Groups.CONTENT_URI, arrayOf(Groups._ID, Groups.TITLE, Groups.SYSTEM_ID),
            "${Groups.ACCOUNT_TYPE}=? AND ${Groups.ACCOUNT_NAME}=? AND ${Groups.DELETED}=0",
            arrayOf(CalendarSource.GOOGLE_ACCOUNT_TYPE, account), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val title = c.str(1) ?: continue
                // "My Contacts" and "Starred in Android" are implicit, not real labels.
                if (c.str(2) in setOf("Contacts", "Starred")) continue
                result[c.getLong(0)] = title
            }
        }
        return result
    }

    /** Accessor for the DATA1..DATA15 columns starting at [offset] in the cursor. */
    private class DataRow(private val c: Cursor, private val offset: Int) {
        fun str(column: String): String? = c.str(offset + DATA_COLUMNS.indexOf(column))?.takeIf { it.isNotBlank() }
        fun int(column: String): Int? = c.intOrNull(offset + DATA_COLUMNS.indexOf(column))
        fun long(column: String): Long? = c.longOrNull(offset + DATA_COLUMNS.indexOf(column))
        fun blob(column: String): ByteArray? = offset.plus(DATA_COLUMNS.indexOf(column)).let { if (c.isNull(it)) null else c.getBlob(it) }
    }

    @Suppress("DEPRECATION") // Im and SipAddress are deprecated but still stored by existing contacts.
    private fun readRow(b: Builder, mime: String, primary: Boolean, d: DataRow, groups: Map<Long, String>, includePhotos: Boolean) {
        when (mime) {
            StructuredName.CONTENT_ITEM_TYPE -> {
                b.name = VName(
                    family = d.str(StructuredName.FAMILY_NAME),
                    given = d.str(StructuredName.GIVEN_NAME),
                    middle = d.str(StructuredName.MIDDLE_NAME),
                    prefix = d.str(StructuredName.PREFIX),
                    suffix = d.str(StructuredName.SUFFIX),
                    phoneticFamily = d.str(StructuredName.PHONETIC_FAMILY_NAME),
                    phoneticGiven = d.str(StructuredName.PHONETIC_GIVEN_NAME),
                    phoneticMiddle = d.str(StructuredName.PHONETIC_MIDDLE_NAME),
                )
                d.str(StructuredName.DISPLAY_NAME)?.let { if (b.displayName.isNullOrBlank()) b.displayName = it }
            }
            Nickname.CONTENT_ITEM_TYPE -> d.str(Nickname.NAME)?.let { b.nicknames += it }
            Phone.CONTENT_ITEM_TYPE -> d.str(Phone.NUMBER)?.let { number ->
                val (types, label) = phoneType(d.int(Phone.TYPE), d.str(Phone.LABEL))
                b.phones += VLabeled(number, types, label, primary)
            }
            Email.CONTENT_ITEM_TYPE -> d.str(Email.ADDRESS)?.let { address ->
                val (types, label) = when (d.int(Email.TYPE)) {
                    Email.TYPE_HOME -> listOf("HOME") to null
                    Email.TYPE_WORK -> listOf("WORK") to null
                    Email.TYPE_MOBILE -> emptyList<String>() to "Mobile"
                    Email.TYPE_CUSTOM -> emptyList<String>() to d.str(Email.LABEL)
                    else -> emptyList<String>() to null
                }
                b.emails += VLabeled(address, types, label, primary)
            }
            StructuredPostal.CONTENT_ITEM_TYPE -> {
                val (types, label) = when (d.int(StructuredPostal.TYPE)) {
                    StructuredPostal.TYPE_HOME -> listOf("HOME") to null
                    StructuredPostal.TYPE_WORK -> listOf("WORK") to null
                    StructuredPostal.TYPE_CUSTOM -> emptyList<String>() to d.str(StructuredPostal.LABEL)
                    else -> emptyList<String>() to null
                }
                b.addresses += VAddress(
                    street = d.str(StructuredPostal.STREET),
                    poBox = d.str(StructuredPostal.POBOX),
                    extended = d.str(StructuredPostal.NEIGHBORHOOD),
                    city = d.str(StructuredPostal.CITY),
                    region = d.str(StructuredPostal.REGION),
                    postalCode = d.str(StructuredPostal.POSTCODE),
                    country = d.str(StructuredPostal.COUNTRY),
                    formatted = d.str(StructuredPostal.FORMATTED_ADDRESS),
                    types = types,
                    label = label,
                )
            }
            Organization.CONTENT_ITEM_TYPE -> b.organizations += VOrganization(
                company = d.str(Organization.COMPANY),
                department = d.str(Organization.DEPARTMENT),
                title = d.str(Organization.TITLE),
            )
            Website.CONTENT_ITEM_TYPE -> d.str(Website.URL)?.let { b.websites += it }
            Note.CONTENT_ITEM_TYPE -> d.str(Note.NOTE)?.let { b.notes += it }
            Event.CONTENT_ITEM_TYPE -> d.str(Event.START_DATE)?.let { date ->
                b.dates += when (d.int(Event.TYPE)) {
                    Event.TYPE_BIRTHDAY -> VDate(date, VEventKind.BIRTHDAY)
                    Event.TYPE_ANNIVERSARY -> VDate(date, VEventKind.ANNIVERSARY)
                    Event.TYPE_CUSTOM -> VDate(date, VEventKind.OTHER, d.str(Event.LABEL))
                    else -> VDate(date, VEventKind.OTHER)
                }
            }
            Relation.CONTENT_ITEM_TYPE -> d.str(Relation.NAME)?.let { name ->
                val type = d.int(Relation.TYPE)
                val label = if (type == Relation.TYPE_CUSTOM) d.str(Relation.LABEL) else RELATION_LABELS[type]
                b.relations += VLabeled(name, label = label)
            }
            Im.CONTENT_ITEM_TYPE -> d.str(Im.DATA)?.let { handle ->
                val protocol = d.int(Im.PROTOCOL)
                val custom = d.str(Im.CUSTOM_PROTOCOL)
                val scheme = IM_SCHEMES[protocol]
                    ?: custom?.lowercase(Locale.ROOT)?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }?.let { "x-$it" }
                    ?: "x-im"
                b.impps += VLabeled("$scheme:$handle", label = if (scheme.startsWith("x-")) custom else null)
            }
            SipAddress.CONTENT_ITEM_TYPE -> d.str(SipAddress.SIP_ADDRESS)?.let { b.impps += VLabeled("sip:$it") }
            GroupMembership.CONTENT_ITEM_TYPE -> d.long(GroupMembership.GROUP_ROW_ID)?.let { groups[it] }?.let { b.groups += it }
            Photo.CONTENT_ITEM_TYPE -> if (includePhotos) d.blob(Photo.PHOTO)?.let { b.photo = it }
        }
    }

    private fun phoneType(type: Int?, customLabel: String?): Pair<List<String>, String?> = when (type) {
        Phone.TYPE_HOME -> listOf("HOME", "VOICE") to null
        Phone.TYPE_MOBILE -> listOf("CELL") to null
        Phone.TYPE_WORK -> listOf("WORK", "VOICE") to null
        Phone.TYPE_FAX_WORK -> listOf("WORK", "FAX") to null
        Phone.TYPE_FAX_HOME -> listOf("HOME", "FAX") to null
        Phone.TYPE_PAGER -> listOf("PAGER") to null
        Phone.TYPE_OTHER -> listOf("VOICE") to null
        Phone.TYPE_CAR -> listOf("CAR") to null
        Phone.TYPE_ISDN -> listOf("ISDN") to null
        Phone.TYPE_OTHER_FAX -> listOf("FAX") to null
        Phone.TYPE_WORK_MOBILE -> listOf("WORK", "CELL") to null
        Phone.TYPE_WORK_PAGER -> listOf("WORK", "PAGER") to null
        Phone.TYPE_MMS -> listOf("MSG") to null
        Phone.TYPE_MAIN -> listOf("VOICE") to "Main"
        Phone.TYPE_COMPANY_MAIN -> listOf("WORK", "VOICE") to "Company main"
        Phone.TYPE_CALLBACK -> listOf("VOICE") to "Callback"
        Phone.TYPE_RADIO -> listOf("VOICE") to "Radio"
        Phone.TYPE_TELEX -> emptyList<String>() to "Telex"
        Phone.TYPE_TTY_TDD -> emptyList<String>() to "TTY/TDD"
        Phone.TYPE_ASSISTANT -> listOf("VOICE") to "Assistant"
        Phone.TYPE_CUSTOM -> listOf("VOICE") to customLabel
        else -> listOf("VOICE") to null
    }

    private class Builder(val uid: String, val starred: Boolean, var displayName: String?) {
        var name: VName? = null
        val nicknames = mutableListOf<String>()
        val phones = mutableListOf<VLabeled>()
        val emails = mutableListOf<VLabeled>()
        val addresses = mutableListOf<VAddress>()
        val organizations = mutableListOf<VOrganization>()
        val websites = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val dates = mutableListOf<VDate>()
        val relations = mutableListOf<VLabeled>()
        val impps = mutableListOf<VLabeled>()
        val groups = mutableListOf<String>()
        var photo: ByteArray? = null

        fun build() = VContact(
            uid = uid, displayName = displayName, name = name, nicknames = nicknames, phones = phones,
            emails = emails, addresses = addresses, organizations = organizations, websites = websites,
            notes = notes, dates = dates, relations = relations, impps = impps, groups = groups,
            photo = photo, starred = starred,
        )
    }

    companion object {
        private val DATA_COLUMNS = arrayOf(
            Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5, Data.DATA6, Data.DATA7, Data.DATA8,
            Data.DATA9, Data.DATA10, Data.DATA11, Data.DATA12, Data.DATA13, Data.DATA14, Data.DATA15,
        )

        private val RELATION_LABELS = mapOf(
            Relation.TYPE_ASSISTANT to "Assistant", Relation.TYPE_BROTHER to "Brother", Relation.TYPE_CHILD to "Child",
            Relation.TYPE_DOMESTIC_PARTNER to "Domestic partner", Relation.TYPE_FATHER to "Father",
            Relation.TYPE_FRIEND to "Friend", Relation.TYPE_MANAGER to "Manager", Relation.TYPE_MOTHER to "Mother",
            Relation.TYPE_PARENT to "Parent", Relation.TYPE_PARTNER to "Partner", Relation.TYPE_REFERRED_BY to "Referred by",
            Relation.TYPE_RELATIVE to "Relative", Relation.TYPE_SISTER to "Sister", Relation.TYPE_SPOUSE to "Spouse",
        )

        @Suppress("DEPRECATION") // IM protocols are deprecated but still stored by old contacts.
        private val IM_SCHEMES = mapOf(
            Im.PROTOCOL_AIM to "aim", Im.PROTOCOL_MSN to "msnim", Im.PROTOCOL_YAHOO to "ymsgr",
            Im.PROTOCOL_SKYPE to "skype", Im.PROTOCOL_QQ to "x-qq", Im.PROTOCOL_GOOGLE_TALK to "xmpp",
            Im.PROTOCOL_ICQ to "icq", Im.PROTOCOL_JABBER to "xmpp", Im.PROTOCOL_NETMEETING to "x-netmeeting",
        )
    }
}
