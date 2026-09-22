/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.loch.lattice;

/**
 * What a reader is entitled to on one axis.
 *
 * <p>Two of them, and the second is the reason this type exists. A ceiling used to be a single
 * point in the whole lattice, and a point cannot express "any tenant, but only endorsed, only
 * ordinary" -- the set of labels that satisfies it is not of the form "everything below some
 * value", so no value denotes it. The library's answer was to let a reader turn the check off
 * entirely, which bought breadth on one axis by giving up all three.
 *
 * <p>Saying it per axis costs nothing and gives up nothing.
 *
 * @param <T> what the axis this constrains is written in
 */
// S2326 says T is unused, and at runtime it is: Any carries no T, and erasure removes the parameter
// everywhere. T is here so a constraint and the axis it constrains have to agree while the code is
// being written -- a constraint on a tenant axis cannot be attached to an axis written in anything
// else, and Constraint.<Tenant>any() is still a tenant constraint although it holds no tenant.
// Erasing T would make every constraint assignable to every axis and move that mismatch to a
// ClassCastException inside a ceiling check at request time, which is the one place a policy
// decision must not fail.
@SuppressWarnings("java:S2326")
public sealed interface Constraint<T> {

  /** At or below one value: the ordinary case, and what every ceiling used to be. */
  record AtMost<T>(T value) implements Constraint<T> {}

  /**
   * Anything this axis considers a usable value.
   *
   * <p>On a ladder that is every rung, because a ladder has nothing poisoned on it. On a matching
   * axis it is any one value and never a mixture -- a reporting job entitled to every tenant reads
   * each tenant's rows, and whatever it combines from them reaches nobody.
   */
  record Any<T>() implements Constraint<T> {}

  static <T> Constraint<T> atMost(T value) {
    return new AtMost<>(value);
  }

  static <T> Constraint<T> any() {
    return new Any<>();
  }
}
