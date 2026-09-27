package app.locationtimeline

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Uploads buffered points to Supabase in batches, deleting each batch once it is stored. */
class UploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val BATCH = 500
        private const val PERIODIC = "upload-periodic"
        private const val NOW = "upload-now"

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun uploadNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }

        private val uploadLock = Any()
    }

    override fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.isSignedIn) {
            prefs.recordError("Not signed in: points are kept on the phone until you sign in")
            return Result.success()
        }
        return try {
            synchronized(uploadLock) { uploadAll(prefs) }
            Result.success()
        } catch (e: Exception) {
            prefs.recordError(e.message ?: e.javaClass.simpleName)
            Result.retry()
        }
    }

    private fun uploadAll(prefs: Prefs) {
        val db = LocalDb.get(applicationContext)
        val client = SupabaseClient(applicationContext)
        var total = 0
        while (true) {
            val batch = db.oldest(BATCH)
            if (batch.isEmpty()) break
            val rows = JSONArray()
            for (p in batch) {
                rows.put(
                    JSONObject()
                        .put("recorded_at", Instant.ofEpochMilli(p.recordedAt).toString())
                        .put("lat", p.lat)
                        .put("lon", p.lon)
                        .put("accuracy", p.accuracy?.toDouble() ?: JSONObject.NULL)
                        .put("speed", p.speed?.toDouble() ?: JSONObject.NULL)
                        .put("activity", p.activity ?: JSONObject.NULL)
                )
            }
            client.insertLocations(rows)
            db.deleteUpTo(batch.last().id)
            total += batch.size
            if (batch.size < BATCH) break
        }
        prefs.lastUploadAt = System.currentTimeMillis()
        prefs.lastUploadCount = total
        prefs.lastError = null
    }
}
