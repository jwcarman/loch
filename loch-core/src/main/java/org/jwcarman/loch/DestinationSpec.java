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
 * Somewhere a value might go, and the most constrained thing it will accept.
 *
 * <p>A destination is anything on the far side of the gate: a model endpoint, a tool, a payment
 * processor, a log sink, a person looking at an approval. Its <b>ceiling</b> is the highest label
 * it tolerates, so a value may go there when the value's label is at or below it.
 *
 * <p>Ceilings are declared once, at wiring, and resolved by name. That is not tidiness: a ceiling
 * constructed at a call site would let any code grant itself permission in one line.
 *
 * @param <A> the application's label type
 */
public interface DestinationSpec<A> {

  /** The name this is registered and audited under. */
  String name();

  /**
   * The most constrained label this will accept, for this particular access.
   *
   * <p>Usually constant. It takes the context for the one case that is not: a destination that is a
   * person, where what may be shown depends on who is looking.
   */
  A ceiling(AccessContext context);
}
