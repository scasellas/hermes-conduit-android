package app.hermesconduit.android.ui.phone

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import app.hermesconduit.android.model.PhonePerson

/**
 * A contact the user picked in the system contact picker (no address-book
 * permission: the picker grants access to that one contact), as the host
 * takes it (iOS PhonePerson(contact:)).
 */
object PhoneContacts {
    fun person(context: Context, uri: Uri, relation: String? = null): PhonePerson? {
        val resolver = context.contentResolver
        var lookup: String? = null
        var name = ""
        runCatching {
            resolver.query(uri, arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    lookup = cursor.getString(0)
                    name = cursor.getString(1).orEmpty()
                }
            }
        }
        var nickname: String? = null
        var organization: String? = null
        var birthday: String? = null
        val phones = mutableListOf<PhonePerson.Phone>()
        val emails = mutableListOf<PhonePerson.Email>()
        runCatching {
            resolver.query(
                Uri.withAppendedPath(uri, ContactsContract.Contacts.Entity.CONTENT_DIRECTORY),
                arrayOf(
                    ContactsContract.Contacts.Entity.MIMETYPE, ContactsContract.Contacts.Entity.DATA1,
                    ContactsContract.Contacts.Entity.DATA2, ContactsContract.Contacts.Entity.DATA3,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(0) ?: continue
                    val value = cursor.getString(1)?.trim().orEmpty()
                    if (value.isEmpty()) continue
                    when (mime) {
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                            val number = PhonePerson.plainNumber(value)
                            val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, cursor.getInt(2), cursor.getString(3)).toString()
                            if (PhonePerson.isDialable(number) && phones.size < 10) phones += PhonePerson.Phone(number, label)
                        }
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                            val label = ContactsContract.CommonDataKinds.Email.getTypeLabel(context.resources, cursor.getInt(2), cursor.getString(3)).toString()
                            if ("@" in value && " " !in value && value.length <= 254 && emails.size < 10) emails += PhonePerson.Email(value, label)
                        }
                        ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> nickname = value.take(60)
                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> organization = value.take(120)
                        ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE ->
                            if (cursor.getInt(2) == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) birthday = parseBirthday(value)
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> if (name.isEmpty()) name = value
                    }
                }
            }
        }
        if (name.isBlank()) name = organization.orEmpty()
        name = name.trim()
        if (name.isEmpty()) return null
        return PhonePerson(
            id = PhonePerson.id(lookup ?: uri.toString()),
            name = name.take(120),
            nickname = nickname,
            relation = relation,
            organization = organization?.takeIf { it != name },
            phones = phones,
            emails = emails,
            birthday = birthday,
        )
    }

    /** Contacts store birthdays as `YYYY-MM-DD` or `--MM-DD`. */
    fun parseBirthday(value: String): String? {
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(value)?.let { m ->
            return PhonePerson.birthday(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        Regex("^--(\\d{2})-?(\\d{2})").find(value)?.let { m ->
            return PhonePerson.birthday(null, m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        return null
    }
}
