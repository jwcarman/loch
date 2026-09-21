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
 * The authority to conceal a value: to hand over the real thing and leave with a surrogate that
 * stands in for it.
 *
 * <p><b>There is no label argument.</b> This door carries its own, decided once when it was
 * declared, so code holding it writes at that label and no other. A service handed the door for
 * customer-submitted disputes cannot create cardholder data -- not "is refused at runtime", but
 * cannot express the operation, because the only door it has says something else. "Which code in
 * this application can create an endorsed value?" is answered by grepping for a constructor
 * parameter.
 *
 * <p><b>Declared during configuration, obtained only by being handed one.</b> There is deliberately
 * no method that trades a name for the door it names. A name is what the manifest and the audit
 * trail call this door; it is not a way through it. Miller's four ways to come by a capability are
 * initial conditions, parenthood, endowment and introduction -- lookup by name is not among them,
 * and adding it here would quietly return this library to policing labels rather than distributing
 * authority.
 *
 * <p>The label may still depend on who is acting. A door fixes the parts that are properties of the
 * door itself -- what arrives there, how far it is trusted, how sensitive it is -- and reads the
 * rest, typically a tenant, from ambient context. So it is not quite a constant, but nothing a
 * caller passes influences it.
 *
 * <p>What this does not establish is that a value deserves the label it gets. Concealing is where
 * data enters, and at that moment there is no earlier label to check against. Monotone join makes
 * it a theorem that derivation cannot weaken a label; concealing is the axiom that theorem rests
 * on. This makes the axioms enumerable, which is all anything can do.
 *
 * @param <T> the type of value this door accepts
 */
public interface Conceal<T> {

  /**
   * Takes the real value and gives back a surrogate that stands in for it.
   *
   * <p>The value stays; the caller leaves with something that names it and discloses nothing about
   * it. {@link Reveal} runs the other way.
   *
   * <p>Refuses by throwing rather than by returning, because the two ways this fails -- a label
   * that leaves a required axis unsaid, and a labelling function that could not decide -- are
   * configuration faults rather than ordinary outcomes. Being turned away while reading is routine
   * and deserves a value; being unable to say how a value is labelled is a bug.
   *
   * @throws IllegalArgumentException if the value is null
   * @throws AccessDeniedException if this door cannot say how to label what arrived
   * @throws IllegalStateException if this door was never brought into force
   */
  Surrogate<T> conceal(T value);
}
