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
 * A handle to a value the loch is holding.
 *
 * <p><b>It does not contain the value.</b> That is the whole point: there is no way to read a held
 * value except by asking the store, and asking the store is the gate. You cannot forget to check,
 * because there is nothing here to read.
 *
 * <p>Handles are safe to pass anywhere -- into an event stream, a log line, a prompt, a message to
 * another service. Possession is not authority.
 *
 * @param type what the stored value is, which the store confirms rather than trusts. A handle is a
 *     claim about identity; the claim about type is checked against what was actually stored.
 */
public record Held<T>(HeldId id, Class<T> type) {

  public Held {
    Objects.requireNonNull(id, "a handle needs an id");
    Objects.requireNonNull(type, "a handle needs a type");
  }

  /** How a handle appears wherever a value would otherwise have been rendered. */
  @Override
  public String toString() {
    return "<held " + id.value() + " type=" + type.getSimpleName() + ">";
  }
}
