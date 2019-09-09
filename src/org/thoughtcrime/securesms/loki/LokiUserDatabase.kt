package org.thoughtcrime.securesms.loki

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.thoughtcrime.securesms.database.Address
import org.thoughtcrime.securesms.database.Database
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.signalservice.loki.messaging.LokiUserDatabaseProtocol

class LokiUserDatabase(context: Context, helper: SQLCipherOpenHelper) : Database(context, helper), LokiUserDatabaseProtocol {

    companion object {
        private val displayNameTable = "loki_user_display_name_database"
        private val serverDisplayNameTable = "loki_user_server_display_name_database"
        private val hexEncodedPublicKey = "hex_encoded_public_key"
        private val displayName = "display_name"
        private val serverId = "server_id"
        @JvmStatic val createDisplayNameTableCommand = "CREATE TABLE $displayNameTable ($hexEncodedPublicKey TEXT PRIMARY KEY, $displayName TEXT);"
        @JvmStatic val createServerDisplayNameTableCommand = "CREATE TABLE $serverDisplayNameTable ($hexEncodedPublicKey TEXT, $serverId TEXT, $displayName TEXT, PRIMARY KEY ($hexEncodedPublicKey, $serverId));"
    }

    override fun getDisplayName(hexEncodedPublicKey: String): String? {
        if (hexEncodedPublicKey == TextSecurePreferences.getLocalNumber(context)) {
            return TextSecurePreferences.getProfileName(context)
        } else {
            val database = databaseHelper.readableDatabase
            return database.get(displayNameTable, "${Companion.hexEncodedPublicKey} = ?", arrayOf(hexEncodedPublicKey)) { cursor ->
                cursor.getString(cursor.getColumnIndexOrThrow(displayName))
            }
        }
    }

    fun setDisplayName(hexEncodedPublicKey: String, displayName: String) {
        val database = databaseHelper.writableDatabase
        val row = ContentValues(2)
        row.put(Companion.hexEncodedPublicKey, hexEncodedPublicKey)
        row.put(Companion.displayName, displayName)
        database.insertOrUpdate(displayNameTable, row, "${Companion.hexEncodedPublicKey} = ?", arrayOf( hexEncodedPublicKey ))
        Recipient.from(context, Address.fromSerialized(hexEncodedPublicKey), false).notifyListeners()
    }

    fun getServerDisplayName(serverId: String, hexEncodedPublicKey: String): String? {
        val database = databaseHelper.readableDatabase
        return database.get(serverDisplayNameTable, "${Companion.hexEncodedPublicKey} = ? AND ${Companion.serverId} = ?", arrayOf(hexEncodedPublicKey, serverId)) { cursor ->
            cursor.getString(cursor.getColumnIndexOrThrow(displayName))
        }
    }

    fun setServerDisplayName(serverId: String, hexEncodedPublicKey: String, displayName: String) {
        val database = databaseHelper.writableDatabase
        val values = ContentValues(3)
        values.put(Companion.serverId, serverId)
        values.put(Companion.hexEncodedPublicKey, hexEncodedPublicKey)
        values.put(Companion.displayName, displayName)
        try {
            database.insertWithOnConflict(serverDisplayNameTable, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            print(e)
        }
    }
}