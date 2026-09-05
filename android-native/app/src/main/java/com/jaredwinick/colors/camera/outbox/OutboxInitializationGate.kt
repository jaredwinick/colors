package com.jaredwinick.colors.camera.outbox

/**
 * Main-thread gate for actions that require startup reconciliation to finish.
 * A requested action is run exactly once after initialization succeeds, or its
 * failure callback is run if initialization fails.
 */
internal class OutboxInitializationGate {
    private enum class State {
        INITIALIZING,
        READY,
        FAILED,
        CANCELLED,
    }

    private data class DeferredAction(
        val onReady: () -> Unit,
        val onFailure: () -> Unit,
    )

    private var state = State.INITIALIZING
    private val deferred = ArrayDeque<DeferredAction>()

    fun runWhenReady(onReady: () -> Unit, onFailure: () -> Unit) {
        when (state) {
            State.INITIALIZING -> deferred.addLast(DeferredAction(onReady, onFailure))
            State.READY -> onReady()
            State.FAILED -> onFailure()
            State.CANCELLED -> Unit
        }
    }

    fun completeSuccessfully() = complete(State.READY)

    fun completeWithFailure() = complete(State.FAILED)

    fun cancel() {
        state = State.CANCELLED
        deferred.clear()
    }

    private fun complete(completedState: State) {
        if (state == State.CANCELLED) return
        check(state == State.INITIALIZING) { "Outbox initialization was already completed" }
        state = completedState
        val waiting = deferred.toList()
        deferred.clear()
        waiting.forEach { action ->
            if (completedState == State.READY) action.onReady() else action.onFailure()
        }
    }
}
