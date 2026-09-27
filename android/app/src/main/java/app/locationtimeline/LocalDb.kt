package app.locationtimeline

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class BufferedPoint(
    val id: Long,
    val recordedAt: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float?,
    val speed: Float?,
    val activity: String?,
)

/** On-device buffer of points waiting to be uploaded. */
class LocalDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "points.db", null, 1) {

    companion object {
        @Volatile private var instance: LocalDb? = null

        fun get(context: Context): LocalDb =
            instance ?: synchronized(this) { instance ?: LocalDb(context).also { instance = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE points (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                recorded_at INTEGER NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                accuracy REAL,
                speed REAL,
                activity TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(recordedAt: Long, lat: Double, lon: Double, accuracy: Float?, speed: Float?, activity: String?) {
        val v = ContentValues().apply {
            put("recorded_at", recordedAt)
            put("lat", lat)
            put("lon", lon)
            if (accuracy != null) put("accuracy", accuracy) else putNull("accuracy")
            if (speed != null) put("speed", speed) else putNull("speed")
            put("activity", activity)
        }
        writableDatabase.insert("points", null, v)
    }

    fun count(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "points")

    fun oldest(limit: Int): List<BufferedPoint> {
        val out = ArrayList<BufferedPoint>()
        readableDatabase.query(
            "points",
            arrayOf("id", "recorded_at", "lat", "lon", "accuracy", "speed", "activity"),
            null, null, null, null, "id ASC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out += BufferedPoint(
                    id = c.getLong(0),
                    recordedAt = c.getLong(1),
                    lat = c.getDouble(2),
                    lon = c.getDouble(3),
                    accuracy = if (c.isNull(4)) null else c.getFloat(4),
                    speed = if (c.isNull(5)) null else c.getFloat(5),
                    activity = if (c.isNull(6)) null else c.getString(6),
                )
            }
        }
        return out
    }

    fun deleteUpTo(maxId: Long) {
        writableDatabase.delete("points", "id <= ?", arrayOf(maxId.toString()))
    }
}
