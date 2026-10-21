package io.github.markalexanderhub.spareeye

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the service is doing right now, for the screen to show. */
object GuardState {
    data class Status(
        val armed: Boolean = false,
        val armedAt: Long = 0L,
        val lastMotionAt: Long = 0L,
        val alertsSent: Int = 0,
        val liveUrl: String? = null,
        val lastError: String? = null,
        /** Fraction of the picture that changed in the last frame, 0..1. */
        val motionLevel: Float = 0f,
        val note: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    fun update(block: (Status) -> Status) {
        _status.value = block(_status.value)
    }

    fun reset() {
        _status.value = Status()
    }
}
