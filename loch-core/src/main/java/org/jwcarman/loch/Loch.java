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

import org.jwcarman.codec.spi.TypeRef;

/**
 * A governed claim check.
 *
 * <p>A claim check stores the payload and hands back a token; the token travels instead of the
 * thing. What is usually missing from the pattern is that anyone holding the check can redeem it.
 * Here redemption is decided -- against the label the value carries, the ceiling of wherever it is
 * going, and an identity the holder of the check does not control.
 *
 * <p>Put a value in with {@link #hold} and you get a {@link Handle} handle. The handle goes
 * wherever you like -- an event stream, a prompt, a message to another service -- because
 * possession of a handle is not permission to read it. Getting the value back out is the one
 * checked operation, and it always names where the value is going.
 *
 * <p><b>There is no way to read a value without naming a destination.</b> No overload omits it. You
 * cannot obtain plaintext "in general", only plaintext for somewhere, and that somewhere is what
 * policy decides on and what the audit records.
 *
 * @param <A> the application's label type: one record holding whatever labels it cares about
 */
public interface Loch<A> {

  /**
   * Takes custody of a value, under the labels the caller asserts.
   *
   * <p><b>Say what the value is.</b> {@code List.of(a, b).getClass()} is {@code
   * ImmutableCollections$List12}, which nothing can deserialise into, so a loch cannot infer a type
   * from an object and be right. The caller declares it, and {@link TypeRef#listOf} and friends are
   * there for the generic cases.
   *
   * <p><b>Hold immutable values.</b> A loch stores what it is given. If the caller keeps a
   * reference to a mutable object and changes it afterwards, the stored value changes underneath a
   * label that was chosen for what it used to be -- and every check and derivation since was
   * answering about different content. Records and strings are safe; a mutable bean is not. A
   * durable loch serialises on the way in and is immune to this, which makes it a hazard of the
   * in-memory one specifically, and therefore of tests rather than production.
   *
   * <p>The caller is trusted application code at a boundary -- a mail listener, a tool that has
   * just queried a system of record -- so it is entitled to say what it is bringing in. This is the
   * only place labels are asserted rather than computed; everywhere else they are derived, and
   * derivation can only make them more constrained.
   */
  <T> Handle<T> hold(T value, TypeRef<T> type, A label);

  /** For a value whose class is its type, which is most of them. */
  default <T> Handle<T> hold(T value, Class<T> type, A label) {
    return hold(value, TypeRef.of(type), label);
  }

  /**
   * What a value is labelled, for rendering and for reporting.
   *
   * <p><b>Not gated, and worth knowing.</b> Anyone holding an id learns the label, the lineage and
   * whether the value exists. That is deliberate -- a renderer must read a label to decide whether
   * to show a value or a handle, and it cannot ask permission to ask -- but it does mean a label is
   * disclosed more freely than the value it describes. Ids are unguessable and only handed out on
   * purpose, which is what keeps that reasonable; a deployment that disagrees should not expose a
   * loch directly.
   *
   * <p>Reading a label is not reading a value. This is how a renderer decides what to say about a
   * handle it is not allowed to open.
   */
  A label(HandleId id);

  /** The label of a value you are holding a typed handle to. */
  default A label(Handle<?> handle) {
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
   * LochConfig#mayErase}.
   *
   * @return how many values were removed, the root included
   * @throws AccessDeniedException when the erasure policy refuses
   */
  int erase(Handle<?> root, AccessContext context);

  /** Using whatever the loch was told about who is asking. */
  default int erase(Handle<?> root) {
    return erase(root, AccessContext.empty());
  }

  /** Whether the loch is holding this at all. */
  boolean holds(HandleId id);

  /** Whether the loch is holding this, by typed handle. */
  default boolean holds(Handle<?> handle) {
    return holds(handle.id());
  }

  /**
   * Asks a registered question about a held value, without the value being handed over.
   *
   * <p>The way to avoid dereferencing. A check runs inside the store, sees the plaintext, and
   * returns a boolean, so the caller learns the one bit it needed and the value never enters code
   * that could keep it. Prefer this to {@link #dereference} wherever a question is what you
   * actually have.
   */
  <I, Q> Answer ask(Handle<I> held, QuestionId<I, Q> question, Q against, AccessContext context);

  /** Using whatever the loch was told about who is asking. */
  default <I, Q> Answer ask(Handle<I> held, QuestionId<I, Q> question, Q against) {
    return ask(held, question, against, AccessContext.empty());
  }

  /** Where a value came from: its parents, and what made it. Empty for anything held directly. */
  Lineage lineage(HandleId id);

  /** Where a value came from, by typed handle. */
  default Lineage lineage(Handle<?> handle) {
    return lineage(handle.id());
  }

  /**
   * What this loch is configured to allow, in a form a person can read.
   *
   * <p>Worth printing at startup and worth pasting into a review: the destinations values may
   * reach, the ways one value can be made from another, the questions that can be asked without
   * taking a value, and -- the part a reviewer is looking for -- every operation that can weaken a
   * label.
   */
  Manifest manifest();

  /**
   * The gate: the value, if this destination may receive it.
   *
   * <p>Four things are checked, and none of them is taken from the handle: that the value exists,
   * that the destination is one that was registered, that the stored value really is the type the
   * handle claims, and that the label is at or below the destination's ceiling.
   */
  <T> Dereferenced<T> dereference(Handle<T> held, DestinationId to, AccessContext context);

  /** Using whatever the loch was told about who is asking. */
  default <T> Dereferenced<T> dereference(Handle<T> held, DestinationId to) {
    return dereference(held, to, AccessContext.empty());
  }
}
