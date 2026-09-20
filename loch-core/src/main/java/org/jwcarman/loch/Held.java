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
 * A handle to a value the loch is holding.
 *
 * <p><b>It does not contain the value.</b> That is the whole point: there is no way to read a held
 * value except by asking the store, and asking the store is the gate. You cannot forget to check,
 * because there is nothing here to read.
 *
 * <p><b>What travels is the {@link HeldId}.</b> A handle is a local, typed view of a value that is
 * already held: the {@link TypeRef} is a claim the gate checks against what the store actually
 * wrote, so it is worth having where code is using a value and worth nothing on a wire. An event, a
 * message or a row carries the id -- a string, which every serialiser can manage without being
 * taught anything -- and the receiving side says what it expects with {@link #of}.
 *
 * <p>Ids are safe to pass anywhere, because possession is not authority.
 *
 * @param type what the stored value is, which the store confirms rather than trusts. A handle is a
 *     claim about identity; the claim about type is checked against what was actually stored.
 *     <p>A {@link TypeRef} rather than a {@code Class}, because {@code List.of(a, b).getClass()} is
 *     {@code ImmutableCollections$List12} -- a JDK-internal type nothing can deserialise into. What
 *     a value <i>is</i> and what class happened to carry it are different questions, and only the
 *     first survives a round trip.
 */
public record Held<T>(HeldId id, TypeRef<T> type) {

  public Held {
    Objects.requireNonNull(id, "a handle needs an id");
    Objects.requireNonNull(type, "a handle needs a type");
  }

  /** A local typed view of a value that is already held. */
  public static <T> Held<T> of(HeldId id, Class<T> type) {
    return new Held<>(id, TypeRef.of(type));
  }

  /** A local typed view, for a generic container. */
  public static <T> Held<T> of(HeldId id, TypeRef<T> type) {
    return new Held<>(id, type);
  }

  /**
   * The id, and only the id.
   *
   * <p>Anything that prints a handle -- a log line, an error, a prompt -- gets a name and nothing
   * else. The Java type is a local matter, and whether a model may be told anything about a value
   * is a policy question for a renderer to ask the loch, not a decision a {@code toString} should
   * make on everyone's behalf.
   */
  @Override
  public String toString() {
    return id.value();
  }
}
