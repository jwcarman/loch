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

/**
 * What came of asking a question about a held value.
 *
 * <p>Three states rather than two, because "no" and "I would not look" are different facts and
 * collapsing them is how a refusal gets mistaken for a negative answer.
 */
public sealed interface Answer {

  /** The check ran and this is what it said. */
  record Answered(boolean value) implements Answer {}

  /** The check did not run. */
  record Refused(Reason reason, String detail) implements Answer {}

  enum Reason {
    NO_SUCH_QUESTION,
    NO_SUCH_VALUE,
    WRONG_TYPE,
    ABOVE_CEILING,
    NOT_AVAILABLE_HERE
  }

  /** True only when the check ran and said yes. A refusal is not a yes. */
  default boolean isTrue() {
    return this instanceof Answered answered && answered.value();
  }

  /** True only when the check ran and said no. A refusal is not a no either. */
  default boolean isFalse() {
    return this instanceof Answered answered && !answered.value();
  }

  default boolean ran() {
    return this instanceof Answered;
  }

  /**
   * The answer, or an exception naming the refusal.
   *
   * <p>For a caller that cannot proceed without one. Prefer {@link #isTrue()} where a refusal and a
   * "no" should be handled differently, which is usually.
   */
  default boolean orThrow() {
    if (this instanceof Answered answered) {
      return answered.value();
    }
    Refused refused = (Refused) this;
    throw new AccessDeniedException(refused.reason().name(), refused.detail());
  }
}
