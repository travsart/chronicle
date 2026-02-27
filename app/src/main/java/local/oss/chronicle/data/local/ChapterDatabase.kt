package local.oss.chronicle.data.local

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.*
import local.oss.chronicle.data.model.Chapter

private const val CHAPTER_DATABASE_NAME = "chapter_db"

private lateinit var INSTANCE: ChapterDatabase

fun getChapterDatabase(context: Context): ChapterDatabase {
    synchronized(ChapterDatabase::class.java) {
        if (!::INSTANCE.isInitialized) {
            INSTANCE =
                Room.databaseBuilder(
                    context.applicationContext,
                    ChapterDatabase::class.java,
                    CHAPTER_DATABASE_NAME,
                ).addMigrations(ChapterMigrations.MIGRATION_1_2).build()
        }
    }
    return INSTANCE
}

@Database(entities = [Chapter::class], version = 2, exportSchema = true)
abstract class ChapterDatabase : RoomDatabase() {
    abstract val chapterDao: ChapterDao
}

/**
 * Database migrations for the Chapter table
 */
object ChapterMigrations {
    /**
     * Migration from version 1 to 2:
     * - Changes primary key from `id` to auto-generated `uid`
     * - Ensures `bookId` is properly set (already existed in v1)
     *
     * Since chapter data is transient (re-fetched from Plex), we use a destructive migration
     * to simplify the migration process.
     */
    val MIGRATION_1_2 =
        object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Drop old table and recreate with new schema
                db.execSQL("DROP TABLE IF EXISTS Chapter")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS Chapter (
                        uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        id INTEGER NOT NULL,
                        `index` INTEGER NOT NULL,
                        discNumber INTEGER NOT NULL,
                        startTimeOffset INTEGER NOT NULL,
                        endTimeOffset INTEGER NOT NULL,
                        downloaded INTEGER NOT NULL,
                        trackId TEXT NOT NULL,
                        bookId TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
}

/**
 * Note: for the weird isCached <= :isOfflineModeActive queries, this ensures that cached items
 * are returned even when offline mode is inactive. A simple equality check would return only
 * cached items during offline mode, but only uncached items when offline mode is inactive. This
 * is an easy way to implement it at the DB level, avoiding messing code in the repository
 */
@Dao
interface ChapterDao {
    @Query("SELECT * FROM Chapter ORDER BY discNumber, `index`")
    fun getAllRows(): LiveData<List<Chapter>>

    @Query("SELECT * FROM Chapter")
    fun getChapters(): List<Chapter>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(rows: List<Chapter>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun update(chapter: Chapter)

    @Query("UPDATE Chapter SET downloaded = :cached WHERE uid = :chapterUid")
    fun updateCachedStatus(
        chapterUid: Long,
        cached: Boolean,
    )

    @Query("DELETE FROM Chapter WHERE uid IN (:chaptersToRemove)")
    fun removeAll(chaptersToRemove: List<Long>): Int

    @Query("DELETE FROM Chapter WHERE bookId = :bookId")
    fun deleteByBookId(bookId: String): Int
}
