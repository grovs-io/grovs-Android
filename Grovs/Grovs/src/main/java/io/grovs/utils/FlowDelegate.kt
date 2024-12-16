package io.grovs.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty0

/**
 * A little circuitous but the way this works is:
 * 1. [delegateRequested] set to true indicates that [delegate] should be filled.
 * 2. Upon [getValue], [delegate] is set.
 * 3. [KProperty0.delegate] returns the value previously set to [delegate]
 */
internal object DelegateAccess {
    internal val delegate = ThreadLocal<Any?>()
    internal val delegateRequested = ThreadLocal<Boolean>().apply { set(false) }
}

internal val <T> KProperty0<T>.delegate: Any?
    get() {
        try {
            DelegateAccess.delegateRequested.set(true)
            this.get()
            return DelegateAccess.delegate.get()
        } finally {
            DelegateAccess.delegate.set(null)
            DelegateAccess.delegateRequested.set(false)
        }
    }

/**
 * @return the flow associated with a [FlowObservable] property,
 * which can be collected upon to observe changes in the value.
 */
@Suppress("UNCHECKED_CAST")
val <T> KProperty0<T>.flow: StateFlow<T>
    get() = delegate as StateFlow<T>

/**
 * Indicates that the target property changes can be observed with [flow].
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
annotation class FlowObservable

@FlowObservable
internal class MutableStateFlowDelegate<T>
internal constructor(
    private val flow: MutableStateFlow<T>,
    private val onSetValue: ((newValue: T, oldValue: T) -> Unit)? = null,
) : MutableStateFlow<T> by flow {

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        if (DelegateAccess.delegateRequested.get() == true) {
            DelegateAccess.delegate.set(this)
        }
        return flow.value
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        val oldValue = flow.value
        flow.value = value
        onSetValue?.invoke(value, oldValue)
    }
}

@FlowObservable
internal class StateFlowDelegate<T>
internal constructor(
    private val flow: StateFlow<T>,
) : StateFlow<T> by flow {

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        if (DelegateAccess.delegateRequested.get() == true) {
            DelegateAccess.delegate.set(this)
        }
        return flow.value
    }
}

internal fun <T> flowDelegate(
    initialValue: T,
    onSetValue: ((newValue: T, oldValue: T) -> Unit)? = null,
): MutableStateFlowDelegate<T> {
    return MutableStateFlowDelegate(MutableStateFlow(initialValue), onSetValue)
}

internal fun <T> flowDelegate(
    stateFlow: StateFlow<T>,
): StateFlowDelegate<T> {
    return StateFlowDelegate(stateFlow)
}