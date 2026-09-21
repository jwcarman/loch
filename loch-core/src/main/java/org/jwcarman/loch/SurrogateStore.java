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
 * A governed claim check.
 *
 * <p>A claim check stores the payload and hands back a token; the token travels instead of the
 * thing. What is usually missing from the pattern is that anyone holding the check can redeem it.
 * Here redemption is decided -- against the label the value carries, the ceiling of wherever it is
 * going, and an identity the holder of the check does not control.
 *
 * <p>Values go in through a source, which hands back a {@link Surrogate}. The handle goes wherever
 * you like -- an event stream, a prompt, a message to another service -- because possession of a
 * handle is not permission to read it. Getting the value back out is the one checked operation, and
 * it always names where the value is going.
 *
 * <p><b>There is no way to read a value without naming a destination.</b> No overload omits it. You
 * cannot obtain plaintext "in general", only plaintext for somewhere, and that somewhere is what
 * policy decides on and what the audit records.
 *
 * @param <A> the application's label type: one record holding whatever labels it cares about
 */
public interface SurrogateStore<A> {

  /**
   * What a value is labelled, for rendering and for reporting.
   *
   * <p><b>Not gated, and worth knowing.</b> Anyone holding an id learns the label, the lineage and
   * whether the value exists. That is deliberate -- a renderer must read a label to decide whether
   * to show a value or a handle, and it cannot ask permission to ask -- but it does mean a label is
   * disclosed more freely than the value it describes. Ids are unguessable and only handed out on
   * purpose, which is what keeps that reasonable; a deployment that disagrees should not expose a
   * store directly.
   *
   * <p>Reading a label is not reading a value. This is how a renderer decides what to say about a
   * handle it is not allowed to open.
   */
  A label(String id);

  /** The label of a value you are holding a typed handle to. */
  default A label(Surrogate<?> handle) {
    return label(handle.id());
  }

  /**
   * Removes a value and everything ever derived from it.
   *
   * <p>"Erase this customer" is a reachability question rather than a cascade anyone designs, which
   * is what lineage buys. A derived value is made of its parents, so leaving descendants behind
   * would leave the data that was asked to be gone.
   *
   * <p><b>Refused unless the application said who may.</b> A label governs disclosure, not
   * destruction, so this is the one operation no ceiling can decide. See {@code
   * SurrogateStoreConfig#mayErase}.
   *
   * @return how many values were removed, the root included
   * @throws AccessDeniedException when the erasure policy refuses
   */
  int erase(Surrogate<?> root, AccessContext context);

  /** Using whatever the store was told about who is asking. */
  default int erase(Surrogate<?> root) {
    return erase(root, AccessContext.empty());
  }

  /** Whether the store is holding this at all. */
  boolean holds(String id);

  /** Whether the store is holding this, by typed handle. */
  default boolean holds(Surrogate<?> handle) {
    return holds(handle.id());
  }

  /** Where a value came from: its parents, and what made it. Empty for anything held directly. */
  Lineage lineage(String id);

  /** Where a value came from, by typed handle. */
  default Lineage lineage(Surrogate<?> handle) {
    return lineage(handle.id());
  }

  /**
   * What this store is configured to allow, in a form a person can read.
   *
   * <p>Worth printing at startup and worth pasting into a review: the destinations values may
   * reach, the ways one value can be made from another, the questions that can be asked without
   * taking a value, and -- the part a reviewer is looking for -- every operation that can weaken a
   * label.
   */
  Manifest manifest();
}
