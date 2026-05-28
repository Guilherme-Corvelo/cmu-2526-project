package pt.ulisboa.tecnico.sharist.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import pt.ulisboa.tecnico.sharist.data.model.BookingEntity
import pt.ulisboa.tecnico.sharist.data.model.PendingOperation
import pt.ulisboa.tecnico.sharist.data.model.RideEntity
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
interface RideDao {
    @Query("SELECT * FROM rides_cache WHERE status = 'OPEN' ORDER BY departureTimeMs ASC")
    fun observeOpenRides(): Flow<List<RideEntity>>

    @Query("SELECT * FROM rides_cache WHERE driverId = :uid ORDER BY departureTimeMs DESC")
    fun observeDriverRides(uid: String): Flow<List<RideEntity>>

    @Query("SELECT * FROM rides_cache WHERE id = :id")
    suspend fun getById(id: String): RideEntity?

    @Query("SELECT * FROM rides_cache WHERE status = 'OPEN' " +
           "AND (:origin = '' OR origin LIKE '%' || :origin || '%') " +
           "AND (:dest = '' OR destination LIKE '%' || :dest || '%') " +
           "ORDER BY departureTimeMs ASC")
    fun observeFilteredRides(origin: String, dest: String): Flow<List<RideEntity>>

    @Upsert suspend fun upsert(rides: List<RideEntity>)
    @Upsert suspend fun upsertOne(ride: RideEntity)

    @Query("DELETE FROM rides_cache WHERE cachedAtMs < :cutoffMs AND status != 'OPEN'")
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

@Dao
interface BookingDao {
    @Query("SELECT * FROM bookings_cache WHERE driverId = :uid ORDER BY createdAtMs DESC")
    fun observeDriverBookings(uid: String): Flow<List<BookingEntity>>

    @Query("SELECT * FROM bookings_cache WHERE passengerId = :uid ORDER BY createdAtMs DESC")
    fun observePassengerBookings(uid: String): Flow<List<BookingEntity>>

    @Query("SELECT * FROM bookings_cache WHERE rideId = :rideId")
    fun observeRideBookings(rideId: String): Flow<List<BookingEntity>>

    @Query("SELECT * FROM bookings_cache WHERE id = :id")
    suspend fun getById(id: String): BookingEntity?

    @Upsert suspend fun upsert(bookings: List<BookingEntity>)
    @Upsert suspend fun upsertOne(booking: BookingEntity)

    @Query("DELETE FROM bookings_cache WHERE cachedAtMs < :cutoffMs AND status != 'ACCEPTED'")
    suspend fun evictStale(cutoffMs: Long)
}

@Database(entities = [RideRequestEntity::class, RideEntity::class, BookingEntity::class, PendingOperation::class], version = 6, exportSchema = false)
abstract class SharISTDatabase : RoomDatabase() {
    abstract fun requestDao(): RideRequestDao
    abstract fun rideDao(): RideDao
    abstract fun bookingDao(): BookingDao
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
    val rideDao get() = db.rideDao()
    val bookingDao get() = db.bookingDao()
    val pendingDao get() = db.pendingDao()
    suspend fun evictStale() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        requestDao.evictStale(cutoff)
        rideDao.evictStale(cutoff)
        bookingDao.evictStale(cutoff)
    }
}
