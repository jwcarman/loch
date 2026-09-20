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

import java.util.List;

/**
 * A store of values that are not simply text.
 *
 * <p>Put a value in with {@link #hold} and you get a {@link Held} handle. The handle goes wherever
 * you like -- an event stream, a prompt, a message to another service -- because possession of a
 * handle is not permission to read it. Getting the value back out is the one checked operation, and
 * it always names where the value is going.
 *
 * <p><b>There is no way to read a value without naming a destination.</b> No overload omits it. You
 * cannot obtain plaintext "in general", only plaintext for somewhere, and that somewhere is what
 * policy decides on and what the audit records.
 *
 * @param <A> the application's attribution type: one record holding whatever labels it cares about
 */
public interface Loch<A> {

  /**
   * Takes custody of a value, under the labels the caller asserts.
   *
   * <p>The caller is trusted application code at a boundary -- a mail listener, a tool that has
   * just queried a system of record -- so it is entitled to say what it is bringing in. This is the
   * only place labels are asserted rather than computed; everywhere else they are derived, and
   * derivation can only make them more constrained.
   */
  <T> Held<T> hold(T value, A attribution);

  /**
   * What a value is labelled, for rendering and for reporting.
   *
   * <p>Reading a label is not reading a value. This is how a renderer decides what to say about a
   * handle it is not allowed to open.
   */
  A attribution(Held<?> held);

  /** Whether the loch is holding this at all. */
  boolean holds(Held<?> held);

  /**
   * Makes a new value from one already held, through a derivation registered at wiring.
   *
   * <p>The new value's label is the join of its parents', so it can only be more constrained --
   * unless the derivation is a privileged one, which may label it lower and is recorded as having
   * done so. Either way the parentage is kept, which is what makes "erase everything derived from
   * this" a question with an answer.
   *
   * <p>A derivation reads plaintext in order to compute, so it is a destination like any other and
   * passes the same gate.
   */
  <I, O> Derived<O> derive(Held<I> parent, DerivationId<I, O> derivation, AccessContext context);

  /** For derivations that do not care who is asking, which is most of them. */
  default <I, O> Derived<O> derive(Held<I> parent, DerivationId<I, O> derivation) {
    return derive(parent, derivation, AccessContext.empty());
  }

  /** Where a value came from: its parents, and what made it. Empty for anything held directly. */
  Lineage lineage(Held<?> held);

  /**
   * Every registered operation that can weaken a label, and what it claims to check.
   *
   * <p>Worth printing at startup. No algebra can tell you whether a check is strong enough -- an
   * endorsement that merely confirms a record exists looks exactly like one that ties it to the
   * person who asked -- so the list being short and readable is the control.
   */
  List<String> manifest();

  /**
   * The gate: the value, if this destination may receive it.
   *
   * <p>Four things are checked, and none of them is taken from the handle: that the value exists,
   * that the destination is one that was registered, that the stored value really is the type the
   * handle claims, and that the label is at or below the destination's ceiling.
   */
  <T> Dereferenced<T> dereference(Held<T> held, DestinationId to, AccessContext context);

  /** For machine destinations, where nobody in particular is asking. */
  default <T> Dereferenced<T> dereference(Held<T> held, DestinationId to) {
    return dereference(held, to, AccessContext.empty());
  }
}
