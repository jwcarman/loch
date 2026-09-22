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

/**
 * What stands in for a value the store is keeping.
 *
 * <p>An identifier and nothing else. It does not say what kind of value it names, what tenant owns
 * it, or how sensitive it is -- and that is deliberate, because a surrogate is the one thing here
 * designed to travel. It goes into a prompt, a log line, a queue, a JSON payload, and comes back as
 * text. Anything written on it travels too, so knowing that a surrogate is a card token rather than
 * a display name would be a disclosure we inflicted on ourselves.
 *
 * <p><b>{@code T} is a phantom parameter.</b> It exists for the compiler, so that handing a
 * surrogate to the wrong portal is caught where you are writing the code, and it evaporates the
 * moment the surrogate becomes a string. The fact that survives is the type name the store
 * recorded, checked against the one the portal declares. So {@code new Surrogate<Invoice>(id)} on a
 * surrogate that really names a mail compiles, and is refused when it reaches a portal. The guess
 * is unchecked; the consequence is not.
 *
 * <p>Holding one is not nothing, which is worth saying plainly. Two invoices for the same tenant
 * carry identical labels, so a ceiling cannot tell them apart: within a label, possessing the
 * surrogate is what separates your record from somebody else's. The identifier is unguessable for
 * that reason, and it is why leaking one into a log matters.
 *
 * @param <T> the type of value this stands in for, as far as the compiler is concerned
 */
// S2326 says T is unused. It is used by the compiler and by nothing else, which is the point:
// handing a Surrogate<Card> to a door declared over Invoice is a compile error rather than a
// refusal at request time. Erasing T would delete the one guarantee this type exists to give.
@SuppressWarnings("java:S2326")
public record Surrogate<T>(String id) {

  public Surrogate {
    Objects.requireNonNull(id, "a surrogate needs an id");
    if (id.isBlank()) {
      throw new IllegalArgumentException("a surrogate's id cannot be blank");
    }
  }

  /** A typed view of a surrogate that arrived as text. */
  public static <T> Surrogate<T> of(String id) {
    return new Surrogate<>(id);
  }

  @Override
  public String toString() {
    return id;
  }
}
