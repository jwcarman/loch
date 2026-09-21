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
 * The authority to make one new held value from five existing ones, of different types.
 *
 * <p>Arity is part of the type, so a five-parent derivation cannot be handed the wrong number of
 * handles and a mismatch is a compile error rather than a refusal. That distinction matters: a
 * refusal is an ordinary outcome calling code is expected to handle, and burying a bug in one hides
 * it.
 *
 * <p>Plaintext is read to compute and does not leave. The result carries the join of every parent's
 * label, so combining two tenants' data yields something labelled for both -- a conflict no
 * destination admits. That is a consequence of join being monotone, not a check.
 *
 * <p>Minted during configuration, like every other capability here, and obtainable only by being
 * handed one.
 */
public interface Derivation5<I1, I2, I3, I4, I5, O> {

  /** Makes the new value, or refuses. */
  Derived<O> derive(
      Surrogate<I1> first,
      Surrogate<I2> second,
      Surrogate<I3> third,
      Surrogate<I4> fourth,
      Surrogate<I5> fifth);

  /** The same, with attributes the caller is contributing to the decision. */
  Derived<O> derive(
      Surrogate<I1> first,
      Surrogate<I2> second,
      Surrogate<I3> third,
      Surrogate<I4> fourth,
      Surrogate<I5> fifth,
      AccessContext context);
}
