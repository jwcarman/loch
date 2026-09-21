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
 * The authority to make one new held value from one existing one.
 *
 * <p>The ordinary case, and the overwhelming majority: pulling a field out of a record, formatting,
 * normalising, truncating. {@link Derivation2} through {@link Derivation5} cover parents of
 * different types, and {@link Fold} covers many parents of one type.
 *
 * <p>Plaintext is read in order to compute and does not leave. The result carries its parent's
 * label unless the derivation was minted with a lowering rule, and a lowering is checked against
 * the lattice: relabelling to something not below the parent is refused, because raising is what
 * ordinary derivation already does and declaring it here is a mistake worth naming.
 *
 * <p>Minted during configuration, and obtainable only by being handed one.
 */
public interface Derivation<I, O> {

  /** What this is called in the manifest and in the record. */
  DerivationId id();

  /** Makes the new value, or refuses. */
  Derived<O> derive(Handle<I> parent);

  /** The same, with attributes the caller is contributing to the decision. */
  Derived<O> derive(Handle<I> parent, AccessContext context);
}
