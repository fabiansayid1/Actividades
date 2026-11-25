package com.example.basededatos.network

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.basededatos.model.BookItem
import com.example.basededatos.model.ImageLinks
import com.example.basededatos.model.StoredApiBook
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class UserProfile(
    val name: String,
    val university: String,
    val email: String,
    val subjects: List<String>,
    val authProvider: String = "password",
    val authUid: String? = null
)

data class AssignmentRecord(
    val id: Long,
    val subject: String,
    val title: String,
    val dueAt: Long,
    val isCompleted: Boolean,
    val reminderEnabled: Boolean
)

data class AssignmentReminderRecord(
    val email: String,
    val id: Long,
    val subject: String,
    val title: String,
    val dueAt: Long,
    val isCompleted: Boolean,
    val reminderEnabled: Boolean
)

data class StudyData(
    val subjects: List<String>,
    val assignments: List<AssignmentRecord>
)

class UserStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE_USERS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NAME TEXT NOT NULL,
                $COLUMN_UNIVERSITY TEXT NOT NULL,
                $COLUMN_EMAIL TEXT NOT NULL UNIQUE,
                $COLUMN_PASSWORD TEXT NOT NULL,
                $COLUMN_SUBJECTS TEXT NOT NULL DEFAULT '',
                $COLUMN_AUTH_PROVIDER TEXT NOT NULL DEFAULT '$AUTH_PROVIDER_PASSWORD',
                $COLUMN_AUTH_UID TEXT
            )""".trimIndent()
        )
        createAssignmentsTable(db)
        createApiBooksTable(db)
        createFavoriteBooksTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN $COLUMN_SUBJECTS TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) {
            createAssignmentsTable(db)
        }
        if (oldVersion < 4) {
            createFavoriteBooksTable(db)
        }
        if (oldVersion in 3..4) {
            db.execSQL(
                "ALTER TABLE $TABLE_ASSIGNMENTS ADD COLUMN $COLUMN_REMINDER_ENABLED INTEGER NOT NULL DEFAULT 1"
            )
        }
        if (oldVersion < 6) {
            db.execSQL(
                "ALTER TABLE $TABLE_USERS ADD COLUMN $COLUMN_AUTH_PROVIDER TEXT NOT NULL DEFAULT '$AUTH_PROVIDER_PASSWORD'"
            )
            db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN $COLUMN_AUTH_UID TEXT")
        }
        if (oldVersion < 7) {
            createApiBooksTable(db)
        }
    }

    suspend fun register(profile: UserProfile, password: String): Boolean =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(COLUMN_NAME, profile.name.trim())
                put(COLUMN_UNIVERSITY, profile.university.trim())
                put(COLUMN_EMAIL, profile.email.trim().lowercase())
                put(COLUMN_PASSWORD, hashPassword(password))
                put(COLUMN_SUBJECTS, profile.subjects.joinToString("\n"))
            }
            writableDatabase.insert(TABLE_USERS, null, values) != -1L
        }

    suspend fun login(email: String, password: String): UserProfile? =
        withContext(Dispatchers.IO) {
            readableDatabase.query(
                TABLE_USERS,
                arrayOf(
                    COLUMN_NAME,
                    COLUMN_UNIVERSITY,
                    COLUMN_EMAIL,
                    COLUMN_SUBJECTS,
                    COLUMN_AUTH_PROVIDER,
                    COLUMN_AUTH_UID
                ),
                "$COLUMN_EMAIL = ? AND $COLUMN_PASSWORD = ?",
                arrayOf(email.trim().lowercase(), hashPassword(password)),
                null,
                null,
                null
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null
                UserProfile(
                    name = cursor.getString(0),
                    university = cursor.getString(1),
                    email = cursor.getString(2),
                    subjects = cursor.getString(3).toSubjects(),
                    authProvider = cursor.getString(4),
                    authUid = cursor.getString(5)
                )
            }
        }

    suspend fun getProfile(email: String): UserProfile? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE_USERS,
            arrayOf(
                COLUMN_NAME,
                COLUMN_UNIVERSITY,
                COLUMN_EMAIL,
                COLUMN_SUBJECTS,
                COLUMN_AUTH_PROVIDER,
                COLUMN_AUTH_UID
            ),
            "$COLUMN_EMAIL = ?",
            arrayOf(email.trim().lowercase()),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            UserProfile(
                name = cursor.getString(0),
                university = cursor.getString(1),
                email = cursor.getString(2),
                subjects = cursor.getString(3).toSubjects(),
                authProvider = cursor.getString(4),
                authUid = cursor.getString(5)
            )
        }
    }

    suspend fun saveGoogleProfile(name: String, email: String, googleUid: String): UserProfile =
        withContext(Dispatchers.IO) {
            val normalizedEmail = email.trim().lowercase()
            require(normalizedEmail.isNotBlank() && googleUid.isNotBlank()) {
                "Google no devolvió un correo o identificador válido."
            }

            val db = writableDatabase
            db.beginTransaction()
            try {
                val existing = db.query(
                    TABLE_USERS,
                    arrayOf(COLUMN_NAME, COLUMN_UNIVERSITY, COLUMN_SUBJECTS),
                    "$COLUMN_EMAIL = ?",
                    arrayOf(normalizedEmail),
                    null,
                    null,
                    null
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
                    } else null
                }

                val savedName = existing?.first?.takeIf { it.isNotBlank() }
                    ?: name.trim().ifBlank { "Estudiante" }
                val savedUniversity = existing?.second.orEmpty()
                val savedSubjects = existing?.third.orEmpty()
                val values = ContentValues().apply {
                    put(COLUMN_NAME, savedName)
                    put(COLUMN_UNIVERSITY, savedUniversity)
                    put(COLUMN_EMAIL, normalizedEmail)
                    put(COLUMN_AUTH_PROVIDER, AUTH_PROVIDER_GOOGLE)
                    put(COLUMN_AUTH_UID, googleUid)
                    if (existing == null) {
                        put(COLUMN_PASSWORD, hashPassword(java.util.UUID.randomUUID().toString()))
                        put(COLUMN_SUBJECTS, "")
                    }
                }
                if (existing == null) {
                    db.insertOrThrow(TABLE_USERS, null, values)
                } else {
                    db.update(TABLE_USERS, values, "$COLUMN_EMAIL = ?", arrayOf(normalizedEmail))
                }
                db.setTransactionSuccessful()
                UserProfile(
                    name = savedName,
                    university = savedUniversity,
                    email = normalizedEmail,
                    subjects = savedSubjects.toSubjects(),
                    authProvider = AUTH_PROVIDER_GOOGLE,
                    authUid = googleUid
                )
            } finally {
                db.endTransaction()
            }
        }

    suspend fun getStudyData(email: String): StudyData = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val normalizedEmail = email.trim().lowercase()
        val subjects = readSubjects(db, normalizedEmail)
        val assignments = mutableListOf<AssignmentRecord>()
        db.query(
            TABLE_ASSIGNMENTS,
            arrayOf(
                COLUMN_ASSIGNMENT_ID,
                COLUMN_ASSIGNMENT_SUBJECT,
                COLUMN_TITLE,
                COLUMN_DUE_AT,
                COLUMN_COMPLETED,
                COLUMN_REMINDER_ENABLED
            ),
            "$COLUMN_EMAIL = ?",
            arrayOf(normalizedEmail),
            null,
            null,
            "$COLUMN_COMPLETED ASC, $COLUMN_DUE_AT ASC, $COLUMN_ASSIGNMENT_ID DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                assignments += AssignmentRecord(
                    id = cursor.getLong(0),
                    subject = cursor.getString(1),
                    title = cursor.getString(2),
                    dueAt = cursor.getLong(3),
                    isCompleted = cursor.getInt(4) == 1,
                    reminderEnabled = cursor.getInt(5) == 1
                )
            }
        }
        StudyData(subjects, assignments)
    }

    suspend fun addSubject(email: String, subject: String): Boolean = withContext(Dispatchers.IO) {
        val cleanSubject = subject.trim()
        if (cleanSubject.isBlank()) return@withContext false
        val db = writableDatabase
        val current = readSubjects(db, email.trim().lowercase())
        if (current.any { it.equals(cleanSubject, ignoreCase = true) }) {
            return@withContext false
        }
        val values = ContentValues().apply {
            put(COLUMN_SUBJECTS, (current + cleanSubject).joinToString("\n"))
        }
        db.update(
            TABLE_USERS,
            values,
            "$COLUMN_EMAIL = ?",
            arrayOf(email.trim().lowercase())
        ) > 0
    }

    suspend fun removeSubject(email: String, subject: String): Boolean = withContext(Dispatchers.IO) {
        val normalizedEmail = email.trim().lowercase()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = readSubjects(db, normalizedEmail)
            val remaining = current.filterNot { it.equals(subject, ignoreCase = true) }
            if (remaining.size == current.size) return@withContext false

            db.delete(
                TABLE_ASSIGNMENTS,
                "$COLUMN_EMAIL = ? AND $COLUMN_ASSIGNMENT_SUBJECT = ?",
                arrayOf(normalizedEmail, subject)
            )
            val values = ContentValues().apply {
                put(COLUMN_SUBJECTS, remaining.joinToString("\n"))
            }
            val updated = db.update(
                TABLE_USERS,
                values,
                "$COLUMN_EMAIL = ?",
                arrayOf(normalizedEmail)
            ) > 0
            if (updated) db.setTransactionSuccessful()
            updated
        } finally {
            db.endTransaction()
        }
    }

    suspend fun addAssignment(
        email: String,
        subject: String,
        title: String,
        dueAt: Long,
        reminderEnabled: Boolean
    ): Long = withContext(Dispatchers.IO) {
        val normalizedEmail = email.trim().lowercase()
        if (readSubjects(writableDatabase, normalizedEmail).none { it == subject }) {
            return@withContext -1L
        }
        val values = ContentValues().apply {
            put(COLUMN_EMAIL, normalizedEmail)
            put(COLUMN_ASSIGNMENT_SUBJECT, subject)
            put(COLUMN_TITLE, title.trim())
            put(COLUMN_DUE_AT, dueAt)
            put(COLUMN_COMPLETED, 0)
            put(COLUMN_REMINDER_ENABLED, if (reminderEnabled) 1 else 0)
        }
        writableDatabase.insert(TABLE_ASSIGNMENTS, null, values)
    }

    suspend fun getAssignmentForReminder(email: String, assignmentId: Long): AssignmentReminderRecord? =
        withContext(Dispatchers.IO) {
            readableDatabase.query(
                TABLE_ASSIGNMENTS,
                arrayOf(
                    COLUMN_EMAIL,
                    COLUMN_ASSIGNMENT_ID,
                    COLUMN_ASSIGNMENT_SUBJECT,
                    COLUMN_TITLE,
                    COLUMN_DUE_AT,
                    COLUMN_COMPLETED,
                    COLUMN_REMINDER_ENABLED
                ),
                "$COLUMN_EMAIL = ? AND $COLUMN_ASSIGNMENT_ID = ?",
                arrayOf(email.trim().lowercase(), assignmentId.toString()),
                null,
                null,
                null
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null
                AssignmentReminderRecord(
                    email = cursor.getString(0),
                    id = cursor.getLong(1),
                    subject = cursor.getString(2),
                    title = cursor.getString(3),
                    dueAt = cursor.getLong(4),
                    isCompleted = cursor.getInt(5) == 1,
                    reminderEnabled = cursor.getInt(6) == 1
                )
            }
        }

    suspend fun getAllReminderAssignments(): List<AssignmentReminderRecord> = withContext(Dispatchers.IO) {
        val reminders = mutableListOf<AssignmentReminderRecord>()
        readableDatabase.query(
            TABLE_ASSIGNMENTS,
            arrayOf(
                COLUMN_EMAIL,
                COLUMN_ASSIGNMENT_ID,
                COLUMN_ASSIGNMENT_SUBJECT,
                COLUMN_TITLE,
                COLUMN_DUE_AT,
                COLUMN_COMPLETED,
                COLUMN_REMINDER_ENABLED
            ),
            "$COLUMN_COMPLETED = 0 AND $COLUMN_REMINDER_ENABLED = 1",
            null,
            null,
            null,
            "$COLUMN_DUE_AT ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                reminders += AssignmentReminderRecord(
                    email = cursor.getString(0),
                    id = cursor.getLong(1),
                    subject = cursor.getString(2),
                    title = cursor.getString(3),
                    dueAt = cursor.getLong(4),
                    isCompleted = cursor.getInt(5) == 1,
                    reminderEnabled = cursor.getInt(6) == 1
                )
            }
        }
        reminders
    }

    suspend fun setAssignmentCompleted(email: String, assignmentId: Long, completed: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(COLUMN_COMPLETED, if (completed) 1 else 0)
            }
            writableDatabase.update(
                TABLE_ASSIGNMENTS,
                values,
                "$COLUMN_ASSIGNMENT_ID = ? AND $COLUMN_EMAIL = ?",
                arrayOf(assignmentId.toString(), email.trim().lowercase())
            ) > 0
        }

    suspend fun deleteAssignment(email: String, assignmentId: Long): Boolean =
        withContext(Dispatchers.IO) {
            writableDatabase.delete(
                TABLE_ASSIGNMENTS,
                "$COLUMN_ASSIGNMENT_ID = ? AND $COLUMN_EMAIL = ?",
                arrayOf(assignmentId.toString(), email.trim().lowercase())
            ) > 0
        }

    suspend fun cacheApiBooks(queryResults: List<Pair<String, List<BookItem>>>) =
        withContext(Dispatchers.IO) {
            if (queryResults.isEmpty()) return@withContext
            val db = writableDatabase
            db.beginTransaction()
            try {
                queryResults.forEach { (query, books) ->
                    val normalizedQuery = query.trim().lowercase()
                    books.forEach { book ->
                        val volumeId = book.id?.trim().orEmpty()
                        if (volumeId.isNotBlank()) {
                            val oldQueries = db.query(
                                TABLE_API_BOOKS,
                                arrayOf(COLUMN_API_QUERIES, COLUMN_API_BOOK_JSON),
                                "$COLUMN_API_VOLUME_ID = ?",
                                arrayOf(volumeId),
                                null,
                                null,
                                null
                            ).use { cursor ->
                                if (cursor.moveToFirst()) {
                                    cursor.getString(0).orEmpty().toQueryList() to
                                        runCatching { gson.fromJson(cursor.getString(1), BookItem::class.java) }.getOrNull()
                                } else emptyList<String>() to null
                            }
                            val queries = (oldQueries.first + normalizedQuery).filter { it.isNotBlank() }.distinct()
                            val mergedBook = mergeBookData(oldQueries.second, book)
                            val values = apiBookValues(mergedBook, queries)
                            db.insertWithOnConflict(
                                TABLE_API_BOOKS,
                                null,
                                values,
                                SQLiteDatabase.CONFLICT_REPLACE
                            )
                        }
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

    suspend fun getCachedApiBooks(queries: List<String>): List<BookItem> = withContext(Dispatchers.IO) {
        val requestedQueries = queries.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        if (requestedQueries.isEmpty()) return@withContext emptyList()
        val cachedBooks = mutableListOf<BookItem>()
        readableDatabase.query(
            TABLE_API_BOOKS,
            arrayOf(COLUMN_API_BOOK_JSON, COLUMN_API_QUERIES),
            null,
            null,
            null,
            null,
            "$COLUMN_API_CACHED_AT DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val matchingQueries = cursor.getString(1).orEmpty().toQueryList().toSet()
                if (matchingQueries.intersect(requestedQueries).isNotEmpty()) {
                    try {
                        gson.fromJson(cursor.getString(0), BookItem::class.java)?.let(cachedBooks::add)
                    } catch (_: Exception) {
                        // Skip only the damaged cache record; the remaining records still load.
                    }
                }
            }
        }
        cachedBooks.distinctBy { it.id }
    }

    /**
     * Consulta todos los elementos de Google Books guardados en SQLite.
     * Devuelve un modelo tipado para que la interfaz no dependa directamente del cursor.
     */
    suspend fun getStoredApiBooks(): List<StoredApiBook> = withContext(Dispatchers.IO) {
        val records = mutableListOf<StoredApiBook>()
        readableDatabase.query(
            TABLE_API_BOOKS,
            arrayOf(
                COLUMN_API_VOLUME_ID,
                COLUMN_API_TITLE,
                COLUMN_API_AUTHORS,
                COLUMN_API_DESCRIPTION,
                COLUMN_API_THUMBNAIL,
                COLUMN_API_PREVIEW_LINK,
                COLUMN_API_INFO_LINK,
                COLUMN_API_LANGUAGE,
                COLUMN_API_QUERIES,
                COLUMN_API_CACHED_AT,
                COLUMN_API_BOOK_JSON
            ),
            null, null, null, null,
            "$COLUMN_API_CACHED_AT DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val book = runCatching {
                    gson.fromJson(cursor.getString(10), BookItem::class.java)
                }.getOrNull() ?: continue
                records += StoredApiBook(
                    volumeId = cursor.getString(0),
                    title = cursor.getString(1).orEmpty(),
                    authors = cursor.getString(2).orEmpty(),
                    description = cursor.getString(3),
                    thumbnailUrl = cursor.getString(4),
                    previewLink = cursor.getString(5),
                    infoLink = cursor.getString(6),
                    language = cursor.getString(7),
                    searchQueries = cursor.getString(8).orEmpty().toQueryList(),
                    cachedAt = cursor.getLong(9),
                    book = book
                )
            }
        }
        records
    }

    suspend fun getCachedApiBook(volumeId: String): BookItem? = withContext(Dispatchers.IO) {
        val normalizedId = volumeId.trim()
        if (normalizedId.isBlank()) return@withContext null
        readableDatabase.query(
            TABLE_API_BOOKS,
            arrayOf(COLUMN_API_BOOK_JSON),
            "$COLUMN_API_VOLUME_ID = ?",
            arrayOf(normalizedId),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            try {
                gson.fromJson(cursor.getString(0), BookItem::class.java)
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun getFavoriteBooks(email: String): List<BookItem> = withContext(Dispatchers.IO) {
        val favorites = mutableListOf<BookItem>()
        val db = writableDatabase
        db.query(
            TABLE_FAVORITE_BOOKS,
            arrayOf(COLUMN_FAVORITE_BOOK_JSON),
            "$COLUMN_EMAIL = ?",
            arrayOf(email.trim().lowercase()),
            null,
            null,
            "$COLUMN_FAVORITE_ID DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    gson.fromJson(cursor.getString(0), BookItem::class.java)?.let(favorites::add)
                } catch (_: Exception) {
                    // Ignore a damaged favorite record while keeping the rest of the list usable.
                }
            }
        }
        favorites.forEach { cacheApiBook(db, it, listOf("favorite")) }
        favorites
    }

    suspend fun toggleFavoriteBook(email: String, book: BookItem): Boolean =
        withContext(Dispatchers.IO) {
            val normalizedEmail = email.trim().lowercase()
            val volumeId = book.id?.trim().orEmpty()
            if (normalizedEmail.isBlank() || volumeId.isBlank()) return@withContext false

            val db = writableDatabase
            db.beginTransaction()
            try {
                cacheApiBook(db, book, listOf("favorite"))
                val isAlreadyFavorite = db.query(
                    TABLE_FAVORITE_BOOKS,
                    arrayOf(COLUMN_FAVORITE_ID),
                    "$COLUMN_EMAIL = ? AND $COLUMN_VOLUME_ID = ?",
                    arrayOf(normalizedEmail, volumeId),
                    null,
                    null,
                    null
                ).use { it.moveToFirst() }

                val nowFavorite = if (isAlreadyFavorite) {
                    db.delete(
                        TABLE_FAVORITE_BOOKS,
                        "$COLUMN_EMAIL = ? AND $COLUMN_VOLUME_ID = ?",
                        arrayOf(normalizedEmail, volumeId)
                    )
                    false
                } else {
                    val values = ContentValues().apply {
                        put(COLUMN_EMAIL, normalizedEmail)
                        put(COLUMN_VOLUME_ID, volumeId)
                        put(COLUMN_FAVORITE_BOOK_JSON, gson.toJson(book))
                    }
                    db.insertWithOnConflict(
                        TABLE_FAVORITE_BOOKS,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE
                    ) != -1L
                }
                db.setTransactionSuccessful()
                nowFavorite
            } finally {
                db.endTransaction()
            }
        }

    suspend fun isFavoriteBook(email: String, volumeId: String): Boolean = withContext(Dispatchers.IO) {
        val normalizedEmail = email.trim().lowercase()
        val normalizedVolumeId = volumeId.trim()
        if (normalizedEmail.isBlank() || normalizedVolumeId.isBlank()) return@withContext false
        readableDatabase.query(
            TABLE_FAVORITE_BOOKS,
            arrayOf(COLUMN_FAVORITE_ID),
            "$COLUMN_EMAIL = ? AND $COLUMN_VOLUME_ID = ?",
            arrayOf(normalizedEmail, normalizedVolumeId),
            null,
            null,
            null
        ).use { it.moveToFirst() }
    }

    suspend fun removeFavoriteBook(email: String, volumeId: String): Boolean = withContext(Dispatchers.IO) {
        val normalizedEmail = email.trim().lowercase()
        val normalizedVolumeId = volumeId.trim()
        if (normalizedEmail.isBlank() || normalizedVolumeId.isBlank()) return@withContext false
        writableDatabase.delete(
            TABLE_FAVORITE_BOOKS,
            "$COLUMN_EMAIL = ? AND $COLUMN_VOLUME_ID = ?",
            arrayOf(normalizedEmail, normalizedVolumeId)
        ) > 0
    }

    suspend fun deleteAccount(email: String, password: String): Boolean =
        withContext(Dispatchers.IO) {
            val normalizedEmail = email.trim().lowercase()
            val db = writableDatabase
            val isValidAccount = db.query(
                TABLE_USERS,
                arrayOf(COLUMN_EMAIL),
                "$COLUMN_EMAIL = ? AND $COLUMN_PASSWORD = ?",
                arrayOf(normalizedEmail, hashPassword(password)),
                null,
                null,
                null
            ).use { it.moveToFirst() }
            if (!isValidAccount) return@withContext false

            db.beginTransaction()
            try {
                db.delete(TABLE_ASSIGNMENTS, "$COLUMN_EMAIL = ?", arrayOf(normalizedEmail))
                val deleted = db.delete(TABLE_USERS, "$COLUMN_EMAIL = ?", arrayOf(normalizedEmail)) > 0
                if (deleted) db.setTransactionSuccessful()
                deleted
            } finally {
                db.endTransaction()
            }
        }

    suspend fun deleteGoogleAccount(email: String, googleUid: String): Boolean =
        withContext(Dispatchers.IO) {
            val normalizedEmail = email.trim().lowercase()
            val db = writableDatabase
            db.beginTransaction()
            try {
                val deleted = db.delete(
                    TABLE_USERS,
                    "$COLUMN_EMAIL = ? AND $COLUMN_AUTH_PROVIDER = ? AND $COLUMN_AUTH_UID = ?",
                    arrayOf(normalizedEmail, AUTH_PROVIDER_GOOGLE, googleUid)
                ) > 0
                if (deleted) {
                    db.delete(TABLE_ASSIGNMENTS, "$COLUMN_EMAIL = ?", arrayOf(normalizedEmail))
                    db.setTransactionSuccessful()
                }
                deleted
            } finally {
                db.endTransaction()
            }
        }

    private fun readSubjects(db: SQLiteDatabase, email: String): List<String> {
        return db.query(
            TABLE_USERS,
            arrayOf(COLUMN_SUBJECTS),
            "$COLUMN_EMAIL = ?",
            arrayOf(email),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty().toSubjects() else emptyList()
        }
    }

    private fun createAssignmentsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $TABLE_ASSIGNMENTS (
                $COLUMN_ASSIGNMENT_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_EMAIL TEXT NOT NULL,
                $COLUMN_ASSIGNMENT_SUBJECT TEXT NOT NULL,
                $COLUMN_TITLE TEXT NOT NULL,
                $COLUMN_DUE_AT INTEGER NOT NULL,
                $COLUMN_COMPLETED INTEGER NOT NULL DEFAULT 0,
                $COLUMN_REMINDER_ENABLED INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY ($COLUMN_EMAIL) REFERENCES $TABLE_USERS($COLUMN_EMAIL) ON DELETE CASCADE
            )""".trimIndent()
        )
    }

    private fun createFavoriteBooksTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $TABLE_FAVORITE_BOOKS (
                $COLUMN_FAVORITE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_EMAIL TEXT NOT NULL,
                $COLUMN_VOLUME_ID TEXT NOT NULL,
                $COLUMN_FAVORITE_BOOK_JSON TEXT NOT NULL,
                UNIQUE ($COLUMN_EMAIL, $COLUMN_VOLUME_ID),
                FOREIGN KEY ($COLUMN_EMAIL) REFERENCES $TABLE_USERS($COLUMN_EMAIL) ON DELETE CASCADE
            )""".trimIndent()
        )
    }

    private fun createApiBooksTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $TABLE_API_BOOKS (
                $COLUMN_API_VOLUME_ID TEXT PRIMARY KEY,
                $COLUMN_API_TITLE TEXT NOT NULL DEFAULT '',
                $COLUMN_API_AUTHORS TEXT NOT NULL DEFAULT '',
                $COLUMN_API_DESCRIPTION TEXT,
                $COLUMN_API_THUMBNAIL TEXT,
                $COLUMN_API_PREVIEW_LINK TEXT,
                $COLUMN_API_INFO_LINK TEXT,
                $COLUMN_API_LANGUAGE TEXT,
                $COLUMN_API_QUERIES TEXT NOT NULL DEFAULT '',
                $COLUMN_API_BOOK_JSON TEXT NOT NULL,
                $COLUMN_API_CACHED_AT INTEGER NOT NULL
            )""".trimIndent()
        )
    }

    private fun apiBookValues(book: BookItem, queries: List<String>): ContentValues =
        ContentValues().apply {
            put(COLUMN_API_VOLUME_ID, book.id.orEmpty())
            put(COLUMN_API_TITLE, book.volumeInfo?.title.orEmpty())
            put(COLUMN_API_AUTHORS, book.volumeInfo?.authors.orEmpty().joinToString(", "))
            put(COLUMN_API_DESCRIPTION, book.volumeInfo?.description)
            put(COLUMN_API_THUMBNAIL, book.volumeInfo?.imageLinks?.thumbnail)
            put(COLUMN_API_PREVIEW_LINK, book.volumeInfo?.previewLink)
            put(COLUMN_API_INFO_LINK, book.volumeInfo?.infoLink)
            put(COLUMN_API_LANGUAGE, book.volumeInfo?.language)
            put(COLUMN_API_QUERIES, queries.joinToString("\n"))
            put(COLUMN_API_BOOK_JSON, gson.toJson(book))
            put(COLUMN_API_CACHED_AT, System.currentTimeMillis())
        }

    private fun cacheApiBook(db: SQLiteDatabase, book: BookItem, queries: List<String>) {
        val volumeId = book.id?.trim().orEmpty()
        if (volumeId.isBlank()) return
        val existing = db.query(
            TABLE_API_BOOKS,
            arrayOf(COLUMN_API_QUERIES, COLUMN_API_BOOK_JSON),
            "$COLUMN_API_VOLUME_ID = ?",
            arrayOf(volumeId),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0).orEmpty().toQueryList() to
                    runCatching { gson.fromJson(cursor.getString(1), BookItem::class.java) }.getOrNull()
            } else emptyList<String>() to null
        }
        val mergedQueries = (existing.first + queries.map { it.trim().lowercase() })
            .filter { it.isNotBlank() }
            .distinct()
        db.insertWithOnConflict(
            TABLE_API_BOOKS,
            null,
            apiBookValues(mergeBookData(existing.second, book), mergedQueries),
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun mergeBookData(existing: BookItem?, incoming: BookItem): BookItem {
        val oldInfo = existing?.volumeInfo
        val newInfo = incoming.volumeInfo
        val mergedInfo = when {
            newInfo == null -> oldInfo
            oldInfo == null -> newInfo
            else -> newInfo.copy(
                title = newInfo.title ?: oldInfo.title,
                subtitle = newInfo.subtitle ?: oldInfo.subtitle,
                authors = newInfo.authors ?: oldInfo.authors,
                publisher = newInfo.publisher ?: oldInfo.publisher,
                publishedDate = newInfo.publishedDate ?: oldInfo.publishedDate,
                description = newInfo.description ?: oldInfo.description,
                pageCount = newInfo.pageCount ?: oldInfo.pageCount,
                categories = newInfo.categories ?: oldInfo.categories,
                industryIdentifiers = newInfo.industryIdentifiers ?: oldInfo.industryIdentifiers,
                printType = newInfo.printType ?: oldInfo.printType,
                maturityRating = newInfo.maturityRating ?: oldInfo.maturityRating,
                language = newInfo.language ?: oldInfo.language,
                averageRating = newInfo.averageRating ?: oldInfo.averageRating,
                ratingsCount = newInfo.ratingsCount ?: oldInfo.ratingsCount,
                imageLinks = mergeImageLinks(newInfo.imageLinks, oldInfo.imageLinks),
                previewLink = newInfo.previewLink ?: oldInfo.previewLink,
                infoLink = newInfo.infoLink ?: oldInfo.infoLink,
                canonicalVolumeLink = newInfo.canonicalVolumeLink ?: oldInfo.canonicalVolumeLink
            )
        }
        val oldAccess = existing?.accessInfo
        val newAccess = incoming.accessInfo
        val mergedAccess = when {
            newAccess == null -> oldAccess
            oldAccess == null -> newAccess
            else -> newAccess.copy(
                viewability = newAccess.viewability ?: oldAccess.viewability,
                accessViewStatus = newAccess.accessViewStatus ?: oldAccess.accessViewStatus,
                webReaderLink = newAccess.webReaderLink ?: oldAccess.webReaderLink,
                publicDomain = newAccess.publicDomain ?: oldAccess.publicDomain,
                pdf = newAccess.pdf ?: oldAccess.pdf,
                epub = newAccess.epub ?: oldAccess.epub
            )
        }
        val oldSale = existing?.saleInfo
        val newSale = incoming.saleInfo
        val mergedSale = when {
            newSale == null -> oldSale
            oldSale == null -> newSale
            else -> newSale.copy(
                saleability = newSale.saleability ?: oldSale.saleability,
                isEbook = newSale.isEbook ?: oldSale.isEbook
            )
        }
        return incoming.copy(
            id = incoming.id ?: existing?.id,
            volumeInfo = mergedInfo,
            accessInfo = mergedAccess,
            saleInfo = mergedSale
        )
    }

    private fun mergeImageLinks(incoming: ImageLinks?, existing: ImageLinks?): ImageLinks? {
        if (incoming == null) return existing
        if (existing == null) return incoming
        return incoming.copy(
            thumbnail = incoming.thumbnail ?: existing.thumbnail,
            smallThumbnail = incoming.smallThumbnail ?: existing.smallThumbnail,
            small = incoming.small ?: existing.small,
            medium = incoming.medium ?: existing.medium,
            large = incoming.large ?: existing.large,
            extraLarge = incoming.extraLarge ?: existing.extraLarge
        )
    }

    private fun String?.toSubjects(): List<String> =
        this.orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    private fun String.toQueryList(): List<String> =
        split("\n").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val DATABASE_NAME = "UserDatabase.db"
        const val DATABASE_VERSION = 7
        const val TABLE_USERS = "users"
        const val TABLE_ASSIGNMENTS = "assignments"
        const val TABLE_FAVORITE_BOOKS = "favorite_books"
        const val TABLE_API_BOOKS = "api_books"

        const val COLUMN_ID = "id"
        const val COLUMN_NAME = "nombre"
        const val COLUMN_UNIVERSITY = "universidad"
        const val COLUMN_EMAIL = "correo"
        const val COLUMN_PASSWORD = "password"
        const val COLUMN_SUBJECTS = "materias"
        const val COLUMN_AUTH_PROVIDER = "auth_provider"
        const val COLUMN_AUTH_UID = "auth_uid"

        const val COLUMN_ASSIGNMENT_ID = "id"
        const val COLUMN_ASSIGNMENT_SUBJECT = "materia"
        const val COLUMN_TITLE = "titulo"
        const val COLUMN_DUE_AT = "fecha_limite"
        const val COLUMN_COMPLETED = "completada"
        const val COLUMN_REMINDER_ENABLED = "recordatorio_habilitado"
        const val COLUMN_FAVORITE_ID = "favorite_id"
        const val COLUMN_VOLUME_ID = "volume_id"
        const val COLUMN_FAVORITE_BOOK_JSON = "book_json"
        const val COLUMN_API_VOLUME_ID = "volume_id"
        const val COLUMN_API_TITLE = "title"
        const val COLUMN_API_AUTHORS = "authors"
        const val COLUMN_API_DESCRIPTION = "description"
        const val COLUMN_API_THUMBNAIL = "thumbnail_url"
        const val COLUMN_API_PREVIEW_LINK = "preview_link"
        const val COLUMN_API_INFO_LINK = "info_link"
        const val COLUMN_API_LANGUAGE = "language"
        const val COLUMN_API_QUERIES = "search_queries"
        const val COLUMN_API_BOOK_JSON = "book_json"
        const val COLUMN_API_CACHED_AT = "cached_at"

        const val AUTH_PROVIDER_PASSWORD = "password"
        const val AUTH_PROVIDER_GOOGLE = "google"
        val gson = Gson()
    }
}
