package pt.ulisboa.tecnico.sharist.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import pt.ulisboa.tecnico.sharist.data.model.PendingOperation
import pt.ulisboa.tecnico.sharist.data.model.RideEntity

// ─── DAOs ─────────────────────────────────────────────────────────────────────

@Dao
interface RideDao {

    @Query("SELECT * FROM rides_cache WHERE status = 'OPEN' ORDER BY departureTimeMs ASC")
    fun observeAllRides(): Flow<List<RideEntity>>

    @Query("""
        SELECT * FROM rides_cache
        WHERE (:origin   = '' OR origin      LIKE '%' || :origin      || '%')
          AND (:dest     = '' OR destination LIKE '%' || :dest        || '%')
          AND (:minSeats = 0  OR seatsAvailable >= :minSeats)
          AND (:maxPrice < 0  OR pricePerSeat  <= :maxPrice)
          AND status = 'OPEN'
        ORDER BY departureTimeMs ASC
    """)
    fun searchRides(
        origin: String,
        dest: String,
        minSeats: Int,
        maxPrice: Double
    ): Flow<List<RideEntity>>

    @Query("SELECT * FROM rides_cache WHERE id = :id")
    suspend fun getRideById(id: String): RideEntity?

    @Query("SELECT * FROM rides_cache WHERE driverId = :driverId")
    fun getRidesByDriver(driverId: String): Flow<List<RideEntity>>

    @Upsert
    suspend fun upsertRides(rides: List<RideEntity>)

    @Upsert
    suspend fun upsertRide(ride: RideEntity)

    @Query("DELETE FROM rides_cache WHERE id = :id")
    suspend fun deleteRide(id: String)

    // Cache eviction: remove entries older than threshold not viewed recently.
    // Policy: keep the N most recently viewed + all future rides.
    @Query("""
        DELETE FROM rides_cache
        WHERE id NOT IN (
            SELECT id FROM rides_cache
            WHERE departureTimeMs > :nowMs OR cachedAtMs > :cutoffMs
            ORDER BY cachedAtMs DESC
            LIMIT :keepCount
        )
    """)
    suspend fun evictStale(nowMs: Long, cutoffMs: Long, keepCount: Int)

    @Query("SELECT COUNT(*) FROM rides_cache")
    suspend fun count(): Int
}

@Dao
interface PendingOperationDao {

    @Query("SELECT * FROM pending_operations WHERE synced = 0 ORDER BY createdAtMs ASC")
    suspend fun getPending(): List<PendingOperation>

    @Insert
    suspend fun insert(op: PendingOperation): Long

    @Update
    suspend fun update(op: PendingOperation)

    @Query("DELETE FROM pending_operations WHERE synced = 1")
    suspend fun clearSynced()
}

// ─── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities = [RideEntity::class, PendingOperation::class],
    version = 1,
    exportSchema = false
)
abstract class SharISTDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
    abstract fun pendingOperationDao(): PendingOperationDao

    companion object {
        @Volatile private var INSTANCE: SharISTDatabase? = null

        fun getInstance(context: Context): SharISTDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SharISTDatabase::class.java,
                    "sharist.db"
                ).fallbackToDestructiveMigration()
                 .build()
                 .also { INSTANCE = it }
            }
    }
}

// ─── Local data source ────────────────────────────────────────────────────────

class LocalDataSource(private val db: SharISTDatabase) {

    val rideDao get() = db.rideDao()
    val pendingDao get() = db.pendingOperationDao()

    // Cache policy constants
    companion object {
        const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L  // 24 h
        const val CACHE_KEEP_COUNT = 200                     // keep 200 most recent
    }

    suspend fun evictStaleCache() {
        val now = System.currentTimeMillis()
        rideDao.evictStale(
            nowMs = now,
            cutoffMs = now - CACHE_MAX_AGE_MS,
            keepCount = CACHE_KEEP_COUNT
        )
    }
}
