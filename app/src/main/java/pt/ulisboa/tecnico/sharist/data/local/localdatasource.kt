package pt.ulisboa.tecnico.sharist.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import pt.ulisboa.tecnico.sharist.data.model.PendingOperation
import pt.ulisboa.tecnico.sharist.data.model.RideRequestEntity

@Dao
interface RideRequestDao {
    @Query("SELECT * FROM requests_cache WHERE status = 'OPEN' ORDER BY requestedTimeMs ASC")
    fun observeOpenRequests(): Flow<List<RideRequestEntity>>

    @Query("SELECT * FROM requests_cache WHERE passengerId = :uid ORDER BY requestedTimeMs DESC")
    fun observePassengerRequests(uid: String): Flow<List<RideRequestEntity>>

    @Query("SELECT * FROM requests_cache WHERE driverId = :uid ORDER BY requestedTimeMs DESC")
    fun observeDriverRequests(uid: String): Flow<List<RideRequestEntity>>

    @Query("SELECT * FROM requests_cache WHERE id = :id")
    suspend fun getById(id: String): RideRequestEntity?

    @Upsert suspend fun upsert(requests: List<RideRequestEntity>)
    @Upsert suspend fun upsertOne(request: RideRequestEntity)

    @Query("DELETE FROM requests_cache WHERE cachedAtMs < :cutoffMs AND status != 'OPEN' AND status != 'ACCEPTED'")
    suspend fun evictStale(cutoffMs: Long)
}

@Dao
interface PendingOperationDao {
    @Query("SELECT * FROM pending_operations WHERE synced = 0 ORDER BY createdAtMs ASC")
    suspend fun getPending(): List<PendingOperation>
    @Insert suspend fun insert(op: PendingOperation): Long
    @Update suspend fun update(op: PendingOperation)
    @Query("DELETE FROM pending_operations WHERE synced = 1")
    suspend fun clearSynced()
}

@Database(entities = [RideRequestEntity::class, PendingOperation::class], version = 1, exportSchema = false)
abstract class SharISTDatabase : RoomDatabase() {
    abstract fun requestDao(): RideRequestDao
    abstract fun pendingDao(): PendingOperationDao

    companion object {
        @Volatile private var INSTANCE: SharISTDatabase? = null
        fun getInstance(ctx: Context): SharISTDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(ctx.applicationContext, SharISTDatabase::class.java, "sharist.db")
                .fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}

class LocalDataSource(private val db: SharISTDatabase) {
    val requestDao get() = db.requestDao()
    val pendingDao get() = db.pendingDao()
    suspend fun evictStale() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        requestDao.evictStale(cutoff)
    }
}
