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

import java.util.function.BiPredicate;
import java.util.function.Function;
import org.jwcarman.loch.lattice.Axes;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Label;

/**
 * What an application declares its authority with.
 *
 * <p>A charter constitutes the portals an application holds: the questions it asks about every
 * value, the doors values may reach, the transformations that are legal, and what each of them is
 * entitled to see. Declaring one hands back the portal, and holding that portal is the only way to
 * perform the operation -- there is no registry and no lookup by name.
 *
 * <p><b>Two things are deliberately absent from this interface.</b>
 *
 * <p>{@code seal}, which brings a charter into force, is not here. Whoever holds the thing that can
 * seal decides when an application's authority graph stops growing, and that is not a decision a
 * bean should be able to make by naming a type in its constructor. The implementation carries it,
 * and whatever constructs a charter keeps that reference; everything else is handed this.
 *
 * <p>{@code erase}, which forgets a value and everything derived from it, is not here either.
 * Nothing in an application has needed it, and leaving it off means the answer to "which code can
 * destroy customer data" is <i>none, structurally</i>. If that changes, erasure earns a portal like
 * every other operation, rather than a method on the object everybody is handed.
 *
 * <p>What is here is declaring and reporting. Reporting is safe to publish: it says what the system
 * permits and how a value is labelled, which is what a manifest, an operator and an audit need, and
 * none of it is a way to move a value anywhere.
 */
public interface Charter {

  /** The questions this charter asks about every value it holds. */
  Axes axes();

  /** Whether this charter has been brought into force. */
  boolean sealed();

  // ------------------------------------------------------------------ constituting authority

  /** A door values enter through, labelled the same way every time. */
  <T> Conceal<T> source(String name, SurrogateType<T> type, Label label);

  /** The same, for a label that depends on who is acting. */
  <T> Conceal<T> source(
      String name, SurrogateType<T> type, Function<AccessContext, Label> labelling);

  /** The same, for a label that also depends on what is arriving. */
  <T> Conceal<T> source(
      String name,
      SurrogateType<T> type,
      java.util.function.BiFunction<T, AccessContext, Label> labelling);

  /** Somewhere values may go, and the types it is allowed to read. */
  SurrogateDestination destination(
      String name, Function<AccessContext, Ceiling> ceiling, SurrogateType<?>... reads);

  /** The same, for a ceiling that does not depend on who is asking. */
  SurrogateDestination destination(String name, Ceiling ceiling, SurrogateType<?>... reads);

  /** A destination declared elsewhere. */
  Charter destination(DestinationSpec destination);

  /**
   * The authority to make one value from another.
   *
   * <p>The customizer says what the derivation may read, and whether it weakens a label. Both are
   * settled here and cannot change afterwards, which is what makes the manifest a complete answer.
   */
  <I, O> Derivation<I, O> derivation(
      String name,
      SurrogateType<I> input,
      SurrogateType<O> output,
      Function<I, O> function,
      java.util.function.Consumer<DerivationConfig> customizer);

  /**
   * The same, for a derivation that may decline: a lookup that finds nothing, a check that fails.
   */
  <I, O> Derivation<I, O> checking(
      String name,
      SurrogateType<I> input,
      SurrogateType<O> output,
      java.util.function.BiFunction<I, AccessContext, java.util.Optional<O>> function,
      java.util.function.Consumer<DerivationConfig> customizer);

  /** The authority to make one value from many of one type. */
  <I, O> Fold<I, O> fold(
      String name,
      SurrogateType<I> input,
      SurrogateType<O> output,
      Function<java.util.List<I>, O> function,
      java.util.function.Consumer<DerivationConfig> customizer);

  /** The authority to ask one question of a value without the value leaving. */
  <I, Q> Query<I, Q> query(
      String name,
      SurrogateType<I> input,
      Class<Q> against,
      Query.Asking<I, Q> asking,
      java.util.function.Consumer<QueryConfig> customizer);

  // ------------------------------------------------------------------ settling how it behaves

  /** Where the access happening right now comes from. */
  Charter currentAccess(AccessContextProvider currentAccess);

  /** Who may forget a value, which is the one decision a label cannot make on its own. */
  Charter mayErase(BiPredicate<Label, AccessContext> mayErase);

  // ------------------------------------------------------------------ what it reports

  /**
   * What this charter permits, rendered.
   *
   * <p>Answerable before it has been sealed to anything, because it is a statement about the
   * declarations rather than about any value: a build can render it, diff it against the last
   * release, and fail on a change nobody meant to make.
   */
  Manifest manifest();

  /** How a value is labelled. For a report or an operator, never for a decision. */
  Label label(Surrogate<?> surrogate);

  /** The same, for an identifier that arrived without its type. */
  Label label(String id);

  /** Where a value came from. */
  Lineage lineage(Surrogate<?> surrogate);

  /** The same, for an identifier that arrived without its type. */
  Lineage lineage(String id);

  /** Whether this charter is holding a value at all. */
  boolean holds(Surrogate<?> surrogate);

  /** The same, for an identifier that arrived without its type. */
  boolean holds(String id);
}
