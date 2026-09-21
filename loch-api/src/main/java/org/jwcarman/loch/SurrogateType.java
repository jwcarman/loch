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

import org.jwcarman.codec.spi.TypeRef;

/**
 * A type this store is willing to keep, and the name it is kept under.
 *
 * <p>Two things that must agree, carried together so they cannot drift. The Java type says how to
 * decode a value; the name is what gets written beside it and compared on the way out.
 *
 * <p><b>The name is not the class name</b>, and that is the point. A stored name is permanent: it
 * is on every row already written, so it has to survive the refactors a class does not. Rename the
 * record, move it to another package, split the module -- the name stays, and everything already
 * stored is still readable. Change the name and you have orphaned every row that carries the old
 * one, which is a migration and not a preference.
 *
 * <p><b>Only the configuration makes one.</b> The pairing has to be the one the store registered,
 * because a mismatched pairing defeats the check it exists for: a name that says invoice beside a
 * type that says card would pass the comparison and then decode invoice bytes as a card. So the
 * constructor is package-private and {@code type(...)} on the configuration is the only source.
 *
 * <p>Holding one grants nothing. It names a type; it does not open a door. That is what separates
 * it from the id types this design deleted, which could be constructed and presented.
 *
 * @param <T> the Java type
 */
public final class SurrogateType<T> {

  private final String name;
  private final TypeRef<T> type;

  SurrogateType(String name, TypeRef<T> type) {
    this.name = name;
    this.type = type;
  }

  /** What values of this type are written down as. */
  public String name() {
    return name;
  }

  /**
   * How to decode a value of this type.
   *
   * <p>Public because a storage implementation lives in its own package and needs it. Handing out
   * the type reference gives nothing away: what must not be forged is the <i>pairing</i> of a name
   * with a type, and that is still settled by the package-private constructor.
   */
  public TypeRef<T> type() {
    return type;
  }

  @Override
  public String toString() {
    return name;
  }
}
