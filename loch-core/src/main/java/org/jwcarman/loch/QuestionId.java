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
 * The name of a question that can be asked about a held value without the value being handed over.
 *
 * <p>Inert, like every reference here: the question's implementation lives in the registry.
 */
public record QuestionId<I, Q>(String value) {

  public QuestionId {
    Objects.requireNonNull(value, "a check needs a name");
    if (value.isBlank()) {
      throw new IllegalArgumentException("a check's name cannot be blank");
    }
  }

  public static <I, Q> QuestionId<I, Q> of(String value) {
    return new QuestionId<>(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
