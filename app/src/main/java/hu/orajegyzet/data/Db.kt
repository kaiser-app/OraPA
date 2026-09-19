package hu.orajegyzet.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Egy órarendi bejegyzés. dayOfWeek: 1 = hétfő ... 7 = vasárnap. Idők percben éjféltől. */
@Entity(tableName = "lessons")
data class Lesson(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val teacher: String = "",
    val room: String = "",
    val dayOfWeek: Int,
    val startMin: Int,
    val endMin: Int
)

enum class NoteStatus { RECORDING, QUEUED, PROCESSING, DONE, ERROR }

/** Egy óra jegyzete. dateIso: ÉÉÉÉ-HH-NN. */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lessonId: Long?,
    val subject: String,
    val dateIso: String,
    val startMin: Int,
    val endMin: Int,
    val status: NoteStatus = NoteStatus.RECORDING,
    val audioPath: String? = null,
    val transcript: String = "",
    val summary: String = "",
    val structured: String = "",
    val keywords: String = "",
    val error: String = "",
    val processedOnline: Boolean? = null
)

@Dao
interface LessonDao {
    @Query("SELECT * FROM lessons ORDER BY dayOfWeek, startMin")
    fun all(): Flow<List<Lesson>>

    @Query("SELECT * FROM lessons")
    suspend fun allOnce(): List<Lesson>

    @Query("SELECT * FROM lessons WHERE dayOfWeek = :day ORDER BY startMin")
    fun byDay(day: Int): Flow<List<Lesson>>

    @Query("SELECT * FROM lessons WHERE id = :id")
    suspend fun byId(id: Long): Lesson?

    @Insert suspend fun insert(l: Lesson): Long
    @Delete suspend fun delete(l: Lesson)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY dateIso DESC, startMin DESC")
    fun all(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun byId(id: Long): Note?

    @Query("SELECT * FROM notes WHERE id = :id")
    fun byIdFlow(id: Long): Flow<Note?>

    @Query("SELECT * FROM notes WHERE dateIso = :date AND status = 'DONE' ORDER BY startMin")
    suspend fun doneOn(date: String): List<Note>

    @Query("SELECT * FROM notes WHERE dateIso >= :sinceDate ORDER BY dateIso DESC, startMin DESC")
    fun since(sinceDate: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE status = 'RECORDING' LIMIT 1")
    suspend fun activeRecording(): Note?

    @Query("UPDATE notes SET status = 'ERROR', error = 'Megszakadt feldolgozás (az app újraindult). Indíts új felvételt.' WHERE status = 'PROCESSING'")
    suspend fun failStuckProcessing()

    @Query("UPDATE notes SET audioPath = null WHERE audioPath = :path")
    suspend fun clearAudioPath(path: String)

    @Insert suspend fun insert(n: Note): Long
    @Update suspend fun update(n: Note)
    @Delete suspend fun delete(n: Note)
}

@Database(entities = [Lesson::class, Note::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class Db : RoomDatabase() {
    abstract fun lessonDao(): LessonDao
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, Db::class.java, "orajegyzet.db")
                .build().also { inst = it }
        }
    }
}

class Converters {
    @TypeConverter fun statusToString(s: NoteStatus) = s.name
    @TypeConverter fun stringToStatus(s: String) = NoteStatus.valueOf(s)
}
