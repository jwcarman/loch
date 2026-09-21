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
 * The authority to learn one fact about a value without the value leaving.
 *
 * <p>Boolean on purpose. A question that could return the customer's email has not protected the
 * customer's email; one that can only answer whether a given address matches has. The literature
 * calls the general problem inference control, and the classic result is the tracker attack of
 * Denning, Denning and Schwartz (1979): a sequence of individually harmless aggregate answers
 * reconstructs the record they were meant to hide. Dinur and Nissim (2003) proved the general case.
 *
 * <p>So a single answer is bounded at one bit, and nothing here bounds a thousand of them. The
 * ceiling decides who may ask and the audit line records that they did; counting is the
 * application's job, and it is a real job.
 *
 * <p>Minted during configuration, and obtainable only by being handed one.
 *
 * @param <I> the type of value this can be asked about
 * @param <Q> what the question is asked against
 */
public interface Query<I, Q> {

  /** Answers, or refuses. The answer is a bit; what was asked never appears in the record. */
  Answer ask(Surrogate<I> about, Q against);

  /** What a query actually does: looks at the value, and returns one bit. */
  @FunctionalInterface
  interface Asking<I, Q> {
    boolean test(I value, Q against, AccessContext context);
  }
}
