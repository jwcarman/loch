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
 * The authority to put a value into a loch at one particular label.
 *
 * <p><b>There is no label argument.</b> An inlet carries its own, decided once when it was minted,
 * so code holding this can write at that label and no other. A service handed the inlet for
 * customer-submitted disputes cannot mint cardholder data -- not "is refused at runtime", but
 * cannot express the operation, because the only inlet it has says something else. The question
 * "which code in this application can create an endorsed value?" is answered by grepping for a
 * constructor parameter.
 *
 * <p><b>Minted during configuration, obtained only by being handed one.</b> There is deliberately
 * no method that trades an {@link InletId} for the inlet it names. An id is what the manifest and
 * the audit trail call this door; it is not a way through it. Miller's four ways to come by a
 * capability are initial conditions, parenthood, endowment and introduction -- lookup by name is
 * not among them, and adding it here would quietly return this library to policing labels rather
 * than distributing authority.
 *
 * <p>The label may still depend on who is acting: an inlet fixes the parts that are properties of
 * the door itself -- what it is, how much it is trusted, what kind of data arrives there -- and
 * reads the rest, typically a tenant, from ambient context. So an inlet is not quite a constant,
 * but nothing a caller passes influences it.
 *
 * <p>What this does not establish is that a value deserves the label it gets. Minting is where data
 * enters the system, and at that moment there is no earlier label to check against. Monotone join
 * makes it a theorem that derivation cannot weaken a label; holding is the axiom that theorem rests
 * on. An inlet makes the axioms enumerable, which is all anything can do.
 *
 * @param <T> the type of value this inlet accepts
 */
public interface Inlet<T> {

  /**
   * Puts a value into the loch, labelled the way this inlet labels things.
   *
   * @throws IllegalArgumentException if the value is null
   * @throws IllegalStateException if this inlet was never bound to a loch
   */
  Handle<T> hold(T value);
}
