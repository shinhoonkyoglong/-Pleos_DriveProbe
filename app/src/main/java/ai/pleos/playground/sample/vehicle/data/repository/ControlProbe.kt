package ai.pleos.playground.sample.vehicle.data.repository

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class ProbeRestoreException(
    name: String,
    expected: Any?,
    val operationFailure: Throwable?,
    restorationFailure: Throwable,
) : IllegalStateException("$name restore FAILED: expected=$expected (${restorationFailure.message}); operation=${operationFailure?.message}", restorationFailure)

/** Shared restoration contract for diagnostic writes, including failed/cancelled writes. */
internal suspend fun <T> controlProbe(
    name: String,
    read: suspend () -> T,
    target: (T) -> T,
    writeVerified: suspend (T) -> Result<T>,
    guard: () -> Unit = {},
): String {
    guard()
    val before = read()
    val requested = target(before)
    require(requested != before) { "$name cannot test a change: capability has no alternative value" }
    var originalFailure: Throwable? = null
    val after = try {
        guard()
        writeVerified(requested).getOrThrow()
    } catch (t: Throwable) {
        originalFailure = t
        throw t
    } finally {
        withContext(NonCancellable) {
            val restore = writeVerified(before)
            restore.exceptionOrNull()?.let { cause ->
                val error = ProbeRestoreException(name, before, originalFailure, cause)
                originalFailure?.let(error::addSuppressed)
                throw error
            }
        }
    }
    guard()
    return "$name before=$before target=$requested readback=$after restored=$before"
}
