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
 * What one kind of axis knows about its own values.
 *
 * <p>Package-private, and untyped on purpose. {@link Axis} carries the application's type on the
 * way in and hands this an {@code Object} that only this implementation has to understand. Keeping
 * the type out here is what lets the engine store a mixture on a {@code matching} axis whose
 * declared type is {@code String} -- a mixture is not a {@code String}, and pretending otherwise
 * would need a cast that could not be justified.
 *
 * <p>Note what is absent: there is no way to get a value back out as the application's type.
 * Nothing needs one, and not having it is why this package contains no unchecked casts.
 */
interface Order {

  /** Turns what the application wrote into what the engine keeps. */
  Object lift(Object written);

  /** What this order holds when nobody has said anything. The identity of {@link #join}. */
  Object bottom();

  /** The least upper bound: the weakest value at least as constrained as both. */
  Object join(Object left, Object right);

  /**
   * Whether a reader who constrained this axis not at all may see this value.
   *
   * <p>True for everything on an ordered axis, because there is nothing poisoned to exclude. False
   * for a mixture, which is the whole reason breadth can be offered safely at all.
   */
  boolean admitsAny(Object value);

  /** For a manifest line or an audit row. */
  String render(Object value);

  /** For storage. Round-trips through {@link #decode}. */
  String encode(Object value);

  Object decode(String encoded);
}
