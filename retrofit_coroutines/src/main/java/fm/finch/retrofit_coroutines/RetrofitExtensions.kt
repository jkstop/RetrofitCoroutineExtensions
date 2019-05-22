package fm.finch.retrofit_coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import retrofit2.Call
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend inline fun <T : Any> Call<T>.asBody(retryAttempts: Int = 1, delayPeriod: Long = 0): T {
    return suspendCancellableCoroutine { continuation ->
        CoroutineScope(continuation.context + Dispatchers.IO).launch {
            var currentAttempt = 1

            this@asBody.executeSync(object : ExecuteCallback<T> {

                override fun onSuccess(body: T) {
                    if (!continuation.isCancelled) {
                        continuation.resume(body)
                    }
                }

                override fun onFail(e: Exception) {
                    when {
                        currentAttempt < retryAttempts -> {
                            runBlocking { delay(delayPeriod) }
                            currentAttempt++
                            this@asBody.clone().executeSync(this)
                        }

                        isActive && !continuation.isCancelled -> continuation.resumeWithException(e)
                    }
                }
            })
        }

        continuation.invokeOnCancellation {
            runCatching { this@asBody.cancel() }
        }
    }
}

suspend inline fun <T : Any> Call<T>.asDeferred(retryAttempts: Int = 1, delayPeriod: Long = 0): Deferred<T> {
    val deferred = CompletableDeferred<T>(coroutineContext[Job.Key])

    CoroutineScope(Dispatchers.IO).launch {
        var currentAttempt = 1

        this@asDeferred.executeSync(object : ExecuteCallback<T> {

            override fun onSuccess(body: T) {
                if (!deferred.isCancelled) {
                    deferred.complete(body)
                }
            }

            override fun onFail(e: Exception) {
                when {
                    currentAttempt < retryAttempts -> {
                        runBlocking { delay(delayPeriod) }
                        currentAttempt++
                        this@asDeferred.clone().executeSync(this)
                    }

                    isActive && !deferred.isCancelled -> deferred.completeExceptionally(e)
                }
            }
        })
    }

    deferred.invokeOnCompletion {
        if (it is CancellationException) {
            this@asDeferred.cancel()
        }
    }

    return deferred
}


fun <T: Any> Call<T>.executeSync(executeCallback: ExecuteCallback<T>) {
    try {
        val response = this.execute()
        if (response.isSuccessful) {
            response.body()?.let { executeCallback.onSuccess(it) } ?: throw NullPointerException("Loss response body")
        } else {
            throw RuntimeException("Response is not success")
        }
    } catch (e: Exception) {
        executeCallback.onFail(e)
    }
}

interface ExecuteCallback<T> {
    fun onSuccess(body: T)
    fun onFail(e: Exception)
}
