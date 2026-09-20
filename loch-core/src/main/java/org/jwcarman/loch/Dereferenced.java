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
package org.jwcarman.loch;

import java.util.Optional;

/**
 * What came of asking for a value.
 *
 * <p>A refusal is an outcome rather than a fault, because callers respond to one differently.
 * Assembling a prompt treats a refusal as "render the handle instead" and carries on; a tool treats
 * it as a call that cannot proceed. Throwing would force the first caller to catch in its normal
 * path.
 */
public sealed interface Dereferenced<T> {

  /** The value, because the gate allowed it. */
  record Allowed<T>(T value) implements Dereferenced<T> {

    /**
     * Says nothing about the value.
     *
     * <p>A record's generated {@code toString} would print it, and this object is exactly the sort
     * of thing that ends up in a log line or an exception message by accident. Getting the value
     * out should require asking for it.
     */
    @Override
    public String toString() {
      return "Allowed[value=<held>]";
    }
  }

  /** No value, and why. */
  record Denied<T>(Reason reason, String detail) implements Dereferenced<T> {}

  /** Why a value was not handed over. */
  enum Reason {
    /** No such value. Also what a manufactured id gets. */
    NO_SUCH_VALUE,
    /** No destination registered under that name. */
    NO_SUCH_DESTINATION,
    /** The handle claimed a type the stored value does not have. */
    WRONG_TYPE,
    /** The label is above what this destination accepts. The ordinary refusal. */
    ABOVE_CEILING
  }

  /** The value when the gate allowed it, empty when it did not. */
  default Optional<T> granted() {
    return this instanceof Allowed<T> allowed ? Optional.of(allowed.value()) : Optional.empty();
  }

  default boolean allowed() {
    return this instanceof Allowed<T>;
  }

  /**
   * The value, or an exception naming the refusal.
   *
   * <p>For code that genuinely cannot continue without it, and whose caller is not a prompt.
   */
  default T orThrow() {
    if (this instanceof Allowed<T> allowed) {
      return allowed.value();
    }
    Denied<T> denied = (Denied<T>) this;
    throw new AccessDeniedException(denied.reason(), denied.detail());
  }
}
