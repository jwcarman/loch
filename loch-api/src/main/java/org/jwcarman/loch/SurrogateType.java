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

import java.util.Objects;
import org.jwcarman.codec.spi.TypeRef;

/**
 * A type this store keeps, and the name it is kept under.
 *
 * <p>Two things that must agree, carried together so they cannot drift. The Java type says how to
 * decode a value; the name is what gets written beside it and compared on the way out.
 *
 * <p><b>The name is not the class name</b>, and that is the point. A stored name is permanent: it
 * is on every row already written, so it has to survive the refactors a class does not. Rename the
 * record, move it to another package, split the module -- the name stays and everything already
 * stored is still readable. Change the name and you have orphaned every row carrying the old one,
 * which is a migration and not a preference.
 *
 * <p>Declare one wherever it reads best: {@code type(...)} on the configuration applies the naming
 * strategy for you, and this constructor is fine when you would rather say it plainly. Either way
 * the configuration sees every type that reaches a portal and refuses two of them wanting one name,
 * so the check does not depend on which you chose.
 *
 * <p>Holding one grants nothing -- it names a type, it does not open a door -- and a mismatched
 * pairing gains nothing either. A reader still only reaches names its destination declared and its
 * store recorded, so the worst a wrong pairing does is fail to decode something you could already
 * read.
 *
 * @param name what values of this type are written down as
 * @param type how to decode one
 * @param <T> the Java type
 */
public record SurrogateType<T>(String name, TypeRef<T> type) {

  public SurrogateType {
    Objects.requireNonNull(name, "a type needs a name");
    Objects.requireNonNull(type, "a type needs to say how to decode one");
    if (name.isBlank()) {
      throw new IllegalArgumentException("a type's name cannot be blank");
    }
  }

  /** A named type. */
  public static <T> SurrogateType<T> of(String name, Class<T> type) {
    return new SurrogateType<>(name, TypeRef.of(type));
  }

  /** A named type, for a generic container. */
  public static <T> SurrogateType<T> of(String name, TypeRef<T> type) {
    return new SurrogateType<>(name, type);
  }

  @Override
  public String toString() {
    return name;
  }
}
