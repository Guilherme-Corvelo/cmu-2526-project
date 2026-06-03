package pt.ulisboa.tecnico.sharist.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import pt.ulisboa.tecnico.sharist.data.remote.RemoteDataSource
import kotlin.math.abs

class MetaModerationWorker(
    context: Context,
    params: WorkerParameters,
    private val remote: RemoteDataSource
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // Note: In a real app, this would be a restricted admin call
            val allUsers = remote.getAllUsers() 
            
            for (user in allUsers) {
                val reviews = remote.getReviewsForUserSync(user.uid)
                if (reviews.isEmpty()) continue

                val avgRating = reviews.map { it.rating }.average()
                var outlierCount = 0

                reviews.forEach { review ->
                    // Flag outliers (deviating by more than 2.0 from average)
                    if (abs(review.rating - avgRating) > 2.0) {
                        outlierCount++
                        if (!review.isOutlier) {
                            remote.flagReviewAsOutlier(review.id)
                        }
                    }
                }

                // Update Trust Score: decrease by 0.1 for every 10% outlier ratio
                val outlierRatio = outlierCount.toDouble() / reviews.size
                val newTrustScore = (1.0 - outlierRatio).coerceIn(0.0, 1.0)

                if (abs(user.trustScore - newTrustScore) > 0.01) {
                    remote.updateUserTrustScore(user.uid, newTrustScore)
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("MetaModeration", "Worker failed", e)
            Result.retry()
        }
    }
}
