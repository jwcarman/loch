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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Label;

/**
 * Every policy decision a store makes, over whatever {@link Storage} it was given.
 *
 * <p>The gate, the axes, the registries and the audit live here and nowhere else, so an in-memory
 * store and a durable one cannot disagree about who may see what. Storage implementations keep
 * bytes; this decides.
 */
public final class DefaultSurrogateStore implements SurrogateStore {

  private final List<Axis<?>> axes;
  private final Map<String, DestinationSpec> destinations;
  private final List<DerivationSpec<?>> derivations;
  private final List<QuerySpec<?, ?>> queries;
  private final AccessContextProvider ambient;
  private final java.util.function.BiPredicate<Label, AccessContext> mayErase;
  private final Storage storage;

  public DefaultSurrogateStore(SurrogateStoreConfig config, Storage storage) {
    this.storage = storage;
    this.axes = config.declaredAxes();
    Map<String, DestinationSpec> byId = new LinkedHashMap<>();
    for (DestinationSpec destination : config.destinations()) {
      if (byId.put(destination.name(), destination) != null) {
        throw new IllegalStateException(
            "two destinations are registered as '" + destination.name() + "'");
      }
    }
    this.destinations = Collections.unmodifiableMap(byId);
    Map<String, DerivationSpec<?>> byName = new LinkedHashMap<>();
    for (DerivationSpec<?> derivation : config.derivations()) {
      if (byName.put(derivation.name(), derivation) != null) {
        throw new IllegalStateException(
            "two derivations are registered as '" + derivation.name() + "'");
      }
    }
    this.derivations = List.copyOf(config.derivations());
    this.queries = List.copyOf(config.queries());
    this.ambient = config.ambient();
    this.mayErase = config.mayErase();
    // Last, and only once everything above succeeded: capabilities minted during configuration
    // reach this store through the config, and one that half-built must not be reachable at all.
    config.bind(this);
  }

  /**
   * Holding through a door, which is holding without being told a label.
   *
   * <p>No {@code mayHold} check, because there is nothing left to check. That policy existed to
   * police a label the caller supplied; a door's label is a property of the door, decided during
   * configuration, and the caller contributes nothing to it.
   */
  <T> Surrogate<T> concealVia(
      String source,
      SurrogateType<T> type,
      java.util.function.BiFunction<T, AccessContext, Label> labelling,
      T value) {
    if (value == null) {
      throw new IllegalArgumentException("a store holds values, not nulls");
    }
    AccessContext asking = asking();
    Label label;
    try {
      label = labelling.apply(value, asking);
    } catch (RuntimeException e) {
      label = null;
    }
    if (label == null) {
      audit(
          AuditRecord.Operation.HOLD,
          freshId(),
          source,
          AuditRecord.Outcome.REFUSED,
          "the source could not say how to label this",
          null,
          asking);
      throw new AccessDeniedException(
          "SOURCE_CANNOT_LABEL", "'" + source + "' could not say what it labels values");
    }
    // The only door an incomplete label can come in through. A derived label is the join of its
    // parents and join only moves up, so nothing downstream can lose what was said here.
    if (leavesARequiredAxisUnsaid(label)) {
      audit(
          AuditRecord.Operation.HOLD,
          freshId(),
          source,
          AuditRecord.Outcome.REFUSED,
          "the label leaves a required axis unsaid",
          label,
          asking);
      throw new AccessDeniedException(
          "INCOMPLETE_LABEL",
          ("'%s' produced a label that leaves a required axis unsaid. Unsaid is the bottom of its"
                  + " order, which is below every ceiling, so the value would have been readable"
                  + " by everyone.")
              .formatted(source));
    }
    String id = freshId();
    // One act: the value and the record that it arrived. The source is named, so the record says
    // which door it came in through.
    AuditRecord entry =
        entry(
            AuditRecord.Operation.HOLD,
            id,
            source,
            AuditRecord.Outcome.ALLOWED,
            null,
            label,
            asking);
    storage.put(id, new StoredValue(value, type, label, Lineage.held()), entry);
    return new Surrogate<>(id);
  }

  /**
   * Whether a label leaves an axis unsaid that this store said it must not.
   *
   * <p>Checked at the one door a label is written through. Unsaid is the bottom of an axis's order,
   * which is below every ceiling, so a value that left a required axis unsaid would be readable by
   * everyone -- silently, and in the direction nobody would notice.
   */
  private boolean leavesARequiredAxisUnsaid(Label label) {
    return axes.stream().anyMatch(label::unsaid);
  }

  /**
   * A ceiling is application code, and application code throws.
   *
   * <p>Treated as a refusal rather than allowed to propagate: a policy that cannot be evaluated has
   * not said yes, and a caller assembling a prompt should get a handle rather than a stack trace.
   */
  private Ceiling ceilingOf(DestinationSpec destination, AccessContext context) {
    try {
      return destination.ceiling(context);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * The same treatment for a question's or a derivation's ceiling, which is equally application
   * code.
   *
   * <p>Returns {@code null} for "could not be evaluated", which is not the same as an empty {@link
   * Optional}: empty is a ceiling that deliberately accepts anything, and conflating the two would
   * turn a crashing policy into a permissive one.
   */
  private Optional<Ceiling> ceilingOf(java.util.function.Supplier<Optional<Ceiling>> ceiling) {
    try {
      return ceiling.get();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** A gate that cannot say whether it is open has not said it is open. */
  private boolean offeredHere(java.util.function.BooleanSupplier availableTo) {
    try {
      return availableTo.getAsBoolean();
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * Who is asking: what the store was told, with anything the caller added laid over it.
   *
   * <p>Resolved once per operation, because an ambient source may be doing real work to answer.
   */
  private AccessContext asking() {
    return ambient.get();
  }

  /**
   * Writes the line, and refuses the access if it cannot be written.
   *
   * <p>A control whose log is silently dropping entries still produces the report, so an access
   * that cannot be audited does not happen.
   */
  /** Writes the record. There is no way to turn this off, which is the point of it. */
  private void audit(
      AuditRecord.Operation operation,
      String value,
      String target,
      AuditRecord.Outcome outcome,
      String reason,
      Label label,
      AccessContext context) {
    storage.record(entry(operation, value, target, outcome, reason, label, context));
  }

  private AuditRecord entry(
      AuditRecord.Operation operation,
      String value,
      String target,
      AuditRecord.Outcome outcome,
      String reason,
      Label label,
      AccessContext context) {
    return new AuditRecord(
        Instant.now(),
        operation,
        value,
        Optional.ofNullable(target),
        outcome,
        Optional.ofNullable(reason),
        Optional.ofNullable(label).map(Object::toString),
        context.attributes());
  }

  private <T> Revealed<T> denied(
      Revealed.Reason reason,
      String detail,
      String because,
      String value,
      String target,
      Label label,
      AccessContext context) {
    audit(
        AuditRecord.Operation.DEREFERENCE,
        value,
        target,
        AuditRecord.Outcome.REFUSED,
        recorded(reason.name(), because),
        label,
        context);
    return new Revealed.Denied<>(reason, detail);
  }

  /**
   * What a successful derivation's record says beyond the bare fact.
   *
   * <p>Weakening a label is the event an auditor is looking for, so it is always said. Combining
   * several values is worth noting because the result is more constrained than any one parent.
   * Deriving one value from one is the ordinary case and says nothing extra.
   */
  private String reasonFor(DerivationSpec<?> spec, Label joined, int parents) {
    if (spec.privileged()) {
      return "weakened from " + joined;
    }
    return parents > 1 ? "combined from " + parents + " values" : null;
  }

  /** How a derivation's parents read in the manifest: positionally, or as many of one type. */
  private static String reads(DerivationSpec<?> spec) {
    String types =
        spec.inputTypes().stream()
            .map(SurrogateType::name)
            .collect(java.util.stream.Collectors.joining(", "));
    return spec.fold() ? "many " + types : types;
  }

  /** What a refusal is allowed to say about labels, which by default is nothing. */
  /**
   * What the record says about a refusal, and what the caller never hears.
   *
   * <p>A refusal message that explains itself is an oracle: code that may not read a value could
   * still learn its classification by asking often enough and reading the answers. So the label and
   * the ceiling go to the audit, where they are protected like any other label, and the caller
   * learns which door said no and a coarse reason.
   */
  private static String because(Label label, Object ceiling) {
    return "labelled " + label + "; accepts " + ceiling;
  }

  /** A reason code for the record, with whatever detail the decision produced. */
  private static String recorded(String reason, String because) {
    return because == null || because.isEmpty() ? reason : reason + ": " + because;
  }

  @Override
  public Label label(String id) {
    return metadataOf(id).label();
  }

  private StoredMetadata metadataOf(String id) {
    return storage
        .metadata(id)
        .orElseThrow(() -> new IllegalArgumentException("this store is not holding " + id));
  }

  /**
   * A fresh identifier for a value nobody has seen yet.
   *
   * <p>Random rather than sequential, and that is load-bearing rather than incidental. Two values
   * at the same label are indistinguishable to a ceiling, so within a label the thing that
   * separates your record from somebody else's is that they cannot name it. 122 random bits is what
   * makes that true, and it is also why a surrogate in a log file matters.
   */
  private static String freshId() {
    return "sur_" + java.util.UUID.randomUUID();
  }

  @Override
  public boolean holds(String id) {
    return storage.contains(id);
  }

  @Override
  public int erase(Surrogate<?> root) {
    AccessContext asking = asking();
    StoredMetadata entry = storage.metadata(root.id()).orElse(null);
    if (entry == null) {
      return 0;
    }
    if (!mayErase.test(entry.label(), asking)) {
      audit(
          AuditRecord.Operation.ERASE,
          root.id(),
          null,
          AuditRecord.Outcome.REFUSED,
          "not permitted to erase",
          null,
          asking);
      throw new AccessDeniedException(
          Revealed.Reason.ABOVE_CEILING,
          "erasing is refused: this store was not told who may erase");
    }
    int removed = storage.erase(root.id());
    audit(
        AuditRecord.Operation.ERASE,
        root.id(),
        null,
        AuditRecord.Outcome.ALLOWED,
        removed + " values removed",
        null,
        asking);
    return removed;
  }

  <I, Q> Answer askVia(QuerySpec<I, Q> spec, Surrogate<I> about, Q against) {
    AccessContext asking = asking();
    AtomicReference<Label> label = new AtomicReference<>();
    AtomicReference<String> because = new AtomicReference<>();
    Answer answer = answering(spec, about, against, asking, label, because);
    if (answer instanceof Answer.Refused refused) {
      audit(
          AuditRecord.Operation.ASK,
          about.id(),
          spec.name(),
          AuditRecord.Outcome.REFUSED,
          recorded(refused.reason().name(), because.get()),
          label.get(),
          asking);
    }
    return answer;
  }

  private <I, Q> Answer answering(
      QuerySpec<I, Q> spec,
      Surrogate<I> held,
      Q against,
      AccessContext context,
      AtomicReference<Label> refused,
      AtomicReference<String> because) {
    String name = spec.name();
    if (!offeredHere(() -> spec.availableTo().test(context))) {
      return new Answer.Refused(
          Answer.Reason.NOT_AVAILABLE_HERE, "'" + name + "' is not offered here");
    }
    StoredMetadata entry = storage.metadata(held.id()).orElse(null);
    if (entry == null) {
      return new Answer.Refused(
          Answer.Reason.NO_SUCH_VALUE, "this store is not holding " + held.id());
    }
    refused.set(entry.label());
    if (!entry.typeName().equals(spec.inputType().name())) {
      return new Answer.Refused(
          Answer.Reason.WRONG_TYPE,
          "'%s' asks about a %s, but %s is a %s"
              .formatted(name, spec.inputType().name(), held.id(), entry.typeName()));
    }
    Optional<Ceiling> ceiling = ceilingOf(() -> spec.ceilingFor(context));
    if (ceiling == null) {
      return new Answer.Refused(
          Answer.Reason.ABOVE_CEILING,
          "'" + name + "' could not say what it accepts, so it does not accept this");
    }
    if (ceiling.isPresent() && !ceiling.get().permits(entry.label())) {
      because.set(because(entry.label(), ceiling.get()));
      return new Answer.Refused(
          Answer.Reason.ABOVE_CEILING, held.id() + " may not be looked at by '" + name + "'");
    }
    I subject = storage.value(held.id(), spec.inputType().type()).orElse(null);
    if (subject == null) {
      return new Answer.Refused(
          Answer.Reason.NO_SUCH_VALUE, "this store is not holding " + held.id());
    }
    boolean answer;
    try {
      answer = spec.asking().test(subject, against, context);
    } catch (RuntimeException e) {
      // It has already read the plaintext, so this refusal is recorded like any other.
      return new Answer.Refused(
          Answer.Reason.NOT_AVAILABLE_HERE, "'" + name + "' failed while reading the value");
    }
    // The answer, never what was asked: the argument can itself be sensitive.
    audit(
        AuditRecord.Operation.ASK,
        held.id(),
        name,
        AuditRecord.Outcome.ALLOWED,
        "answered " + answer,
        entry.label(),
        context);
    return new Answer.Answered(answer);
  }

  @Override
  public Lineage lineage(String id) {
    return metadataOf(id).lineage();
  }

  @Override
  public Manifest manifest() {
    List<Manifest.Entry> theDestinations = new ArrayList<>();
    destinations.forEach(
        (id, destination) -> {
          Ceiling ceiling = ceilingOf(destination, AccessContext.empty());
          theDestinations.add(
              new Manifest.Entry(
                  id,
                  "accepts up to "
                      + (ceiling == null ? "(its ceiling could not be evaluated)" : ceiling),
                  false));
        });
    List<Manifest.Entry> theDerivations = new ArrayList<>();
    derivations.forEach(
        derivation ->
            theDerivations.add(
                new Manifest.Entry(
                    derivation.name(),
                    "%s -> %s".formatted(reads(derivation), derivation.outputType().name()),
                    derivation.privileged())));
    List<Manifest.Entry> theQuestions = new ArrayList<>();
    return new Manifest(
        String.valueOf(Label.nothing()), theDestinations, theDerivations, theQuestions);
  }

  /**
   * Every derivation and every fold, run positionally.
   *
   * <p>The parents' types and their number were settled by the capability the caller held, so this
   * does not re-derive them -- it checks each parent against the type declared for its position,
   * which is the one thing the compiler could not know: a {@link String} that arrived as text can
   * be given any type by {@link Surrogate#of}, so what the store wrote remains the only ground
   * truth.
   */
  <O> Derived<O> deriveVia(DerivationSpec<O> spec, List<Surrogate<?>> parents) {
    AccessContext asking = asking();
    AtomicReference<Label> label = new AtomicReference<>();
    AtomicReference<String> because = new AtomicReference<>();
    Derived<O> result = deriving(spec, parents, asking, label, because);
    if (result instanceof Derived.Refused<O> refused) {
      audit(
          AuditRecord.Operation.DERIVE,
          parents.isEmpty() ? freshId() : parents.getFirst().id(),
          spec.name(),
          AuditRecord.Outcome.REFUSED,
          recorded(refused.reason().name(), because.get()),
          label.get(),
          asking);
    }
    return result;
  }

  private <O> Derived<O> deriving(
      DerivationSpec<O> spec,
      List<Surrogate<?>> parents,
      AccessContext context,
      AtomicReference<Label> refused,
      AtomicReference<String> because) {
    String id = spec.name();
    if (parents.isEmpty()) {
      return new Derived.Refused<>(
          Derived.Reason.NO_PARENTS, "'" + id + "' needs at least one value");
    }
    // A fixed-arity capability cannot be called with the wrong number of handles, so reaching this
    // means the spec and the capability that minted it disagree. That is a bug here, not there.
    if (!spec.fold() && parents.size() != spec.inputTypes().size()) {
      throw new IllegalStateException(
          "'%s' reads %d values and was given %d"
              .formatted(id, spec.inputTypes().size(), parents.size()));
    }
    if (!offeredHere(() -> spec.availableTo().test(context))) {
      return new Derived.Refused<>(
          Derived.Reason.NOT_AVAILABLE_HERE, "'" + id + "' is not offered here");
    }
    // Once, not once per parent: a ceiling that reads ambient context is doing real work.
    Optional<Ceiling> ceiling =
        ceilingOf(() -> Optional.ofNullable(spec.ceiling()).map(f -> f.apply(context)));
    if (ceiling == null) {
      return new Derived.Refused<>(
          Derived.Reason.ABOVE_CEILING,
          "'" + id + "' could not say what it accepts, so it does not accept this");
    }

    List<Object> inputs = new ArrayList<>();
    List<String> parentIds = new ArrayList<>();
    Label joined = null;
    for (int position = 0; position < parents.size(); position++) {
      Surrogate<?> parent = parents.get(position);
      SurrogateType<?> expected = spec.typeAt(position);
      StoredMetadata entry = storage.metadata(parent.id()).orElse(null);
      if (entry == null) {
        return new Derived.Refused<>(
            Derived.Reason.NO_SUCH_VALUE, "this store is not holding " + parent.id());
      }
      if (!entry.typeName().equals(expected.name())) {
        return new Derived.Refused<>(
            Derived.Reason.WRONG_TYPE,
            "'%s' reads a %s in position %d, but %s is a %s"
                .formatted(id, expected.name(), position + 1, parent.id(), entry.typeName()));
      }
      if (ceiling.isPresent() && !ceiling.get().permits(entry.label())) {
        because.set(because(entry.label(), ceiling.get()));
        return new Derived.Refused<>(
            Derived.Reason.ABOVE_CEILING, parent.id() + " may not reach '" + id + "'");
      }
      Object input = storage.value(parent.id(), expected.type()).orElse(null);
      if (input == null) {
        return new Derived.Refused<>(
            Derived.Reason.NO_SUCH_VALUE, "this store is not holding " + parent.id());
      }
      inputs.add(input);
      parentIds.add(parent.id());
      // Every parent contributes. This is the line that makes a mixed-tenant value unusable.
      joined = joined == null ? entry.label() : joined.join(entry.label());
      refused.set(joined);
    }

    Optional<O> produced;
    try {
      produced = spec.function().apply(List.copyOf(inputs), context);
    } catch (RuntimeException e) {
      // It has already seen the plaintext, so this refusal has to be recorded like any other.
      return new Derived.Refused<>(
          Derived.Reason.DECLINED, "'" + id + "' failed while reading the value");
    }
    if (produced.isEmpty()) {
      return new Derived.Refused<>(Derived.Reason.DECLINED, "'" + id + "' declined");
    }

    Label label = joined;
    if (spec.relabel() != null) {
      try {
        label = spec.relabel().apply(joined);
      } catch (RuntimeException e) {
        return new Derived.Refused<>(
            Derived.Reason.NOT_A_LOWERING, "'" + id + "' could not say what it was lowering to");
      }
      if (!label.atOrBelow(joined)) {
        because.set(because(label, joined));
        return new Derived.Refused<>(
            Derived.Reason.NOT_A_LOWERING,
            "'%s' relabelled a value as something not below it".formatted(id));
      }
    }

    String newId = freshId();
    AuditRecord entry =
        entry(
            AuditRecord.Operation.DERIVE,
            newId,
            id,
            AuditRecord.Outcome.ALLOWED,
            reasonFor(spec, joined, parentIds.size()),
            label,
            context);
    storage.put(
        newId,
        new StoredValue(
            produced.get(), spec.outputType(), label, Lineage.derivedFrom(parentIds, id)),
        entry);
    return new Derived.Made<>(new Surrogate<>(newId));
  }

  <T> Revealed<T> revealVia(Surrogate<T> held, SurrogateType<T> expected, String to) {
    AccessContext context = asking();
    DestinationSpec destination = destinations.get(to);
    if (destination == null) {
      return denied(
          Revealed.Reason.NO_SUCH_DESTINATION,
          "no destination is registered as '" + to + "'",
          null,
          held.id(),
          to,
          null,
          context);
    }
    StoredMetadata entry = storage.metadata(held.id()).orElse(null);
    if (entry == null) {
      return denied(
          Revealed.Reason.NO_SUCH_VALUE,
          "this store is not holding " + held.id(),
          null,
          held.id(),
          to,
          null,
          context);
    }
    if (!entry.typeName().equals(expected.name())) {
      return denied(
          Revealed.Reason.WRONG_TYPE,
          held.id() + " is a " + entry.typeName() + ", not a " + expected.name(),
          null,
          held.id(),
          to,
          entry.label(),
          context);
    }
    Ceiling ceiling = ceilingOf(destination, context);
    if (ceiling == null) {
      return denied(
          Revealed.Reason.ABOVE_CEILING,
          "'" + to + "' could not say what it accepts, so it does not accept this",
          null,
          held.id(),
          to,
          entry.label(),
          context);
    }
    if (!ceiling.permits(entry.label())) {
      return denied(
          Revealed.Reason.ABOVE_CEILING,
          held.id() + " may not reach '" + to + "'",
          because(entry.label(), ceiling),
          held.id(),
          to,
          entry.label(),
          context);
    }
    audit(
        AuditRecord.Operation.DEREFERENCE,
        held.id(),
        to,
        AuditRecord.Outcome.ALLOWED,
        null,
        entry.label(),
        context);
    // The type was confirmed against what the store wrote, so this decodes a verified fact.
    return storage
        .value(held.id(), expected.type())
        .<Revealed<T>>map(Revealed.Allowed::new)
        .orElseGet(
            () ->
                new Revealed.Denied<>(
                    Revealed.Reason.NO_SUCH_VALUE, "this store is not holding " + held.id()));
  }
}
