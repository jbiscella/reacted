/*
 * Copyright (c) 2020 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.core.utils;

import java.util.function.Predicate;
import java.util.function.Supplier;

public final class ObjectUtils {
    private ObjectUtils() { /* No instances allowed */ }

    public static <ElementT extends Comparable<ElementT>, ExceptionT extends RuntimeException>
    ElementT requiredInRange(ElementT element, ElementT inclusiveRangeStart, ElementT inclusiveRangeEnd,
                             Supplier<ExceptionT> onError) {
        if (!(java.util.Objects.requireNonNull(inclusiveRangeEnd).compareTo(java.util.Objects.requireNonNull(inclusiveRangeStart)) < 0) &&
            java.util.Objects.requireNonNull(element).compareTo(inclusiveRangeStart) >= 0 &&
                element.compareTo(inclusiveRangeEnd) <= 0) {
            return element;
        }
        throw onError.get();
    }

    public static <ReturnT, OnErrorT extends RuntimeException>  ReturnT
    requiredCondition(ReturnT element, Predicate<ReturnT> controlPredicate,
                      Supplier<OnErrorT> onControlPredicateFailure) {
        if (controlPredicate.negate().test(element)) {
            throw onControlPredicateFailure.get();
        }
        return element;
    }
}