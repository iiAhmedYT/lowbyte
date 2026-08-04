package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.RuntimeRewrite

/**
 * `Collectors.toUnmodifiableList`.
 *
 * Not `collectingAndThen(toList(), Collections::unmodifiableList)`, near as that
 * looks. `toList` accepts a null element and this refuses one, so the collector
 * has to put the check back, which is more than a call site can express.
 */
object CollectorsToUnmodifiableListRewrite : RuntimeRewrite("toUnmodifiableList") {
    override val name = "Collectors.toUnmodifiableList"
}

/**
 * `Collectors.toUnmodifiableSet`.
 *
 * Not `collectingAndThen(toSet(), Collections::unmodifiableSet)`, near as that
 * looks. `toSet` accepts a null element and this refuses one, so the collector
 * has to put the check back, which is more than a call site can express.
 */
object CollectorsToUnmodifiableSetRewrite : RuntimeRewrite("toUnmodifiableSet") {
    override val name = "Collectors.toUnmodifiableSet"
}

/**
 * `Collectors.toUnmodifiableMap`, both overloads.
 *
 * `toMap` already refuses a null value and already throws `IllegalStateException`
 * on a repeated key. A null key is the one thing it lets through, so that is all
 * the replacement adds.
 */
object CollectorsToUnmodifiableMapRewrite : RuntimeRewrite("toUnmodifiableMap") {
    override val name = "Collectors.toUnmodifiableMap"
}
