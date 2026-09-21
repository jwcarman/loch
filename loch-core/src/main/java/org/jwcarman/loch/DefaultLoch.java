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
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.loch.lattice.Lattice;

/**
 * Every policy decision a loch makes, over whatever {@link Storage} it was given.
 *
 * <p>The gate, the lattice, the registries and the audit live here and nowhere else, so an
 * in-memory loch and a durable one cannot disagree about who may see what. Storage implementations
 * keep bytes; this decides.
 */
public final class DefaultLoch<A> implements Loch<A> {

  private final Lattice<A> lattice;
  private final Map<DestinationId, Destination<A>> destinations;
  private final Map<String, Derivation<A, ?, ?>> derivations;
  private final Map<String, Question<A, ?, ?>> questions;
  private final boolean explainRefusals;
  private final Auditor auditor;
  private final java.util.function.Supplier<AccessContext> ambient;
  private final java.util.Set<String> callerMayContribute;
  private final java.util.function.BiPredicate<A, AccessContext> mayErase;
  private final java.util.function.BiPredicate<A, AccessContext> mayHold;
  private final Storage<A> storage;

  public DefaultLoch(LochConfig<A> config, Storage<A> storage) {
    this.storage = storage;
    this.lattice = config.lattice();
    Map<DestinationId, Destination<A>> byId = new LinkedHashMap<>();
    for (Destination<A> destination : config.destinations()) {
      if (byId.put(destination.id(), destination) != null) {
        throw new IllegalStateException(
            "two destinations are registered as '" + destination.id() + "'");
      }
    }
    this.destinations = Collections.unmodifiableMap(byId);
    Map<String, Derivation<A, ?, ?>> byName = new LinkedHashMap<>();
    for (Derivation<A, ?, ?> derivation : config.derivations()) {
      if (byName.put(derivation.id().value(), derivation) != null) {
        throw new IllegalStateException(
            "two derivations are registered as '" + derivation.id() + "'");
      }
    }
    this.derivations = Collections.unmodifiableMap(byName);
    Map<String, Question<A, ?, ?>> byQuestion = new LinkedHashMap<>();
    for (Question<A, ?, ?> question : config.questions()) {
      if (byQuestion.put(question.id().value(), question) != null) {
        throw new IllegalStateException("two questions are registered as '" + question.id() + "'");
      }
    }
    this.questions = Collections.unmodifiableMap(byQuestion);
    this.explainRefusals = config.explainsRefusals();
    this.auditor = config.auditor();
    this.ambient = config.ambient();
    this.callerMayContribute = config.callerMayContribute();
    this.mayErase = config.mayErase();
    this.mayHold = config.mayHold();
    // Last, and only once everything above succeeded: capabilities minted during configuration
    // reach this loch through the config, and one that half-built must not be reachable at all.
    config.bind(this);
  }

  /**
   * Holding through an inlet, which is holding without being told a label.
   *
   * <p>No {@code mayHold} check, because there is nothing left to check. That policy existed to
   * police a label the caller supplied; an inlet's label is a property of the door, decided during
   * configuration, and the caller contributes nothing to it.
   */
  <T> Handle<T> holdVia(
      InletId inlet,
      TypeRef<T> type,
      java.util.function.Function<AccessContext, A> labelling,
      T value) {
    if (value == null) {
      throw new IllegalArgumentException("a loch holds values, not nulls");
    }
    AccessContext asking = asking(AccessContext.empty());
    A label;
    try {
      label = labelling.apply(asking);
    } catch (RuntimeException e) {
      label = null;
    }
    if (label == null) {
      audit(
          AuditRecord.Operation.HOLD,
          HandleId.fresh(),
          inlet.value(),
          AuditRecord.Outcome.REFUSED,
          "the inlet could not say how to label this",
          null,
          asking);
      throw new AccessDeniedException(
          "INLET_CANNOT_LABEL", "'" + inlet + "' could not say what it labels values");
    }
    HandleId id = HandleId.fresh();
    // Recorded before it is stored, for the reason given in hold(...): an auditor that throws must
    // leave nothing behind. The inlet is named, so the record says which door this came in through.
    audit(
        AuditRecord.Operation.HOLD,
        id,
        inlet.value(),
        AuditRecord.Outcome.ALLOWED,
        null,
        label,
        asking);
    storage.put(id, new StoredValue<>(value, type, label, Lineage.held()));
    return new Handle<>(id, type);
  }

  /**
   * A ceiling is application code, and application code throws.
   *
   * <p>Treated as a refusal rather than allowed to propagate: a policy that cannot be evaluated has
   * not said yes, and a caller assembling a prompt should get a handle rather than a stack trace.
   */
  private A ceilingOf(Destination<A> destination, AccessContext context) {
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
  private Optional<A> ceilingOf(java.util.function.Supplier<Optional<A>> ceiling) {
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
   * Who is asking: what the loch was told, with anything the caller added laid over it.
   *
   * <p>Resolved once per operation, because an ambient source may be doing real work to answer.
   */
  private AccessContext asking(AccessContext explicit) {
    return explicit.contributedTo(ambient.get(), callerMayContribute);
  }

  /**
   * Writes the line, and refuses the access if it cannot be written.
   *
   * <p>A control whose log is silently dropping entries still produces the report, so an access
   * that cannot be audited does not happen.
   */
  private void audit(
      AuditRecord.Operation operation,
      HandleId value,
      String target,
      AuditRecord.Outcome outcome,
      String reason,
      A label,
      AccessContext context) {
    auditor.record(
        new AuditRecord(
            Instant.now(),
            operation,
            value,
            Optional.ofNullable(target),
            outcome,
            Optional.ofNullable(reason),
            Optional.ofNullable(label).map(Object::toString),
            context.attributes()));
  }

  private <T> Dereferenced<T> denied(
      Dereferenced.Reason reason,
      String detail,
      HandleId value,
      String target,
      A label,
      AccessContext context) {
    audit(
        AuditRecord.Operation.DEREFERENCE,
        value,
        target,
        AuditRecord.Outcome.REFUSED,
        reason.name(),
        label,
        context);
    return new Dereferenced.Denied<>(reason, detail);
  }

  /**
   * What a successful derivation's record says beyond the bare fact.
   *
   * <p>Weakening a label is the event an auditor is looking for, so it is always said. Combining
   * several values is worth noting because the result is more constrained than any one parent.
   * Deriving one value from one is the ordinary case and says nothing extra.
   */
  private String reasonFor(Derivation<A, ?, ?> derivation, A joined, int parents) {
    if (derivation.privileged()) {
      return "weakened from " + joined;
    }
    return parents > 1 ? "combined from " + parents + " values" : null;
  }

  /** What a refusal is allowed to say about labels, which by default is nothing. */
  private String explain(A label, Object ceiling) {
    return explainRefusals ? " (labelled " + label + "; accepts " + ceiling + ")" : "";
  }

  @Override
  public <T> Handle<T> hold(T value, TypeRef<T> type, A label) {
    if (value == null) {
      throw new IllegalArgumentException("a loch holds values, not nulls");
    }
    if (label == null) {
      throw new IllegalArgumentException(
          "a held value needs an label; use the lattice's bottom to say 'nothing in"
              + " particular'");
    }
    AccessContext asking = asking(AccessContext.empty());
    if (!mayHold.test(label, asking)) {
      audit(
          AuditRecord.Operation.HOLD,
          HandleId.fresh(),
          null,
          AuditRecord.Outcome.REFUSED,
          "not permitted to hold at that label",
          label,
          asking);
      throw new AccessDeniedException(
          "MAY_NOT_HOLD", "this access may not create a value labelled that way");
    }
    HandleId id = HandleId.fresh();
    // Recorded before it is stored, not after. An auditor that throws must leave nothing behind;
    // the other order commits a value durably under an id the caller never receives, which is a
    // secret nothing can reach, read or erase.
    audit(AuditRecord.Operation.HOLD, id, null, AuditRecord.Outcome.ALLOWED, null, label, asking);
    storage.put(id, new StoredValue<>(value, type, label, Lineage.held()));
    return new Handle<>(id, type);
  }

  @Override
  public A label(HandleId id) {
    return metadataOf(id).label();
  }

  private StoredMetadata<A> metadataOf(HandleId id) {
    return storage
        .metadata(id)
        .orElseThrow(() -> new IllegalArgumentException("this loch is not holding " + id));
  }

  /** What a handle says it is, in the form the store wrote it. */
  private static String nameOf(TypeRef<?> type) {
    return type.getType().getTypeName();
  }

  @Override
  public boolean holds(HandleId id) {
    return storage.contains(id);
  }

  @Override
  public int erase(Handle<?> root, AccessContext context) {
    AccessContext asking = asking(context);
    StoredMetadata<A> entry = storage.metadata(root.id()).orElse(null);
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
          Dereferenced.Reason.ABOVE_CEILING,
          "erasing is refused: this loch was not told who may erase");
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

  @Override
  public <I, Q> Answer ask(Handle<I> held, QuestionId<I, Q> id, Q against, AccessContext context) {
    AccessContext asking = asking(context);
    AtomicReference<A> label = new AtomicReference<>();
    Answer answer = answering(held, id, against, asking, label);
    if (answer instanceof Answer.Refused refused) {
      audit(
          AuditRecord.Operation.ASK,
          held.id(),
          id.value(),
          AuditRecord.Outcome.REFUSED,
          refused.reason().name(),
          label.get(),
          asking);
    }
    return answer;
  }

  @SuppressWarnings("unchecked")
  private <I, Q> Answer answering(
      Handle<I> held,
      QuestionId<I, Q> id,
      Q against,
      AccessContext context,
      AtomicReference<A> refused) {
    Question<A, I, Q> asked = (Question<A, I, Q>) questions.get(id.value());
    if (asked == null) {
      return new Answer.Refused(
          Answer.Reason.NO_SUCH_QUESTION, "no question is registered as '" + id + "'");
    }
    if (!offeredHere(() -> asked.availableTo(context))) {
      return new Answer.Refused(
          Answer.Reason.NOT_AVAILABLE_HERE, "'" + id + "' is not offered here");
    }
    StoredMetadata<A> entry = storage.metadata(held.id()).orElse(null);
    if (entry == null) {
      return new Answer.Refused(
          Answer.Reason.NO_SUCH_VALUE, "this loch is not holding " + held.id());
    }
    refused.set(entry.label());
    if (!entry.typeName().equals(nameOf(asked.inputType()))) {
      return new Answer.Refused(
          Answer.Reason.WRONG_TYPE,
          "'%s' asks about a %s, but %s is a %s"
              .formatted(id, nameOf(asked.inputType()), held.id(), entry.typeName()));
    }
    Optional<A> ceiling = ceilingOf(() -> asked.ceiling(context));
    if (ceiling == null) {
      return new Answer.Refused(
          Answer.Reason.ABOVE_CEILING,
          "'" + id + "' could not say what it accepts, so it does not accept this");
    }
    if (ceiling.isPresent() && !lattice.permits(entry.label(), ceiling.get())) {
      return new Answer.Refused(
          Answer.Reason.ABOVE_CEILING,
          held.id()
              + " may not be looked at by '"
              + id
              + "'"
              + explain(entry.label(), ceiling.get()));
    }
    I subject = storage.value(held.id(), asked.inputType()).orElse(null);
    if (subject == null) {
      return new Answer.Refused(
          Answer.Reason.NO_SUCH_VALUE, "this loch is not holding " + held.id());
    }
    boolean answer = asked.test(subject, against, context);
    // The answer, never what was asked: the argument can itself be sensitive.
    audit(
        AuditRecord.Operation.ASK,
        held.id(),
        id.value(),
        AuditRecord.Outcome.ALLOWED,
        "answered " + answer,
        entry.label(),
        context);
    return new Answer.Answered(answer);
  }

  @Override
  public Lineage lineage(HandleId id) {
    return metadataOf(id).lineage();
  }

  @Override
  public Manifest manifest() {
    List<Manifest.Entry> theDestinations = new ArrayList<>();
    destinations.forEach(
        (id, destination) -> {
          A ceiling = ceilingOf(destination, AccessContext.empty());
          theDestinations.add(
              new Manifest.Entry(
                  id.value(),
                  "accepts up to "
                      + (ceiling == null ? "(its ceiling could not be evaluated)" : ceiling),
                  false));
        });
    List<Manifest.Entry> theDerivations = new ArrayList<>();
    derivations.forEach(
        (name, derivation) ->
            theDerivations.add(
                new Manifest.Entry(
                    name,
                    "%s -> %s"
                        .formatted(
                            derivation.inputType().rawClass().getSimpleName(),
                            derivation.outputType().rawClass().getSimpleName()),
                    derivation.privileged())));
    List<Manifest.Entry> theQuestions = new ArrayList<>();
    questions.forEach(
        (name, question) ->
            theQuestions.add(
                new Manifest.Entry(
                    name,
                    "asks about a " + question.inputType().rawClass().getSimpleName(),
                    false)));
    return new Manifest(
        String.valueOf(lattice.bottom()), theDestinations, theDerivations, theQuestions);
  }

  @Override
  public <I, O> Derived<O> deriveAll(
      List<Handle<I>> parents, DerivationId<I, O> id, AccessContext context) {
    AccessContext asking = asking(context);
    AtomicReference<A> label = new AtomicReference<>();
    Derived<O> result = derivingAll(parents, id, asking, label);
    if (result instanceof Derived.Refused<O> refused) {
      audit(
          AuditRecord.Operation.DERIVE,
          parents.isEmpty() ? HandleId.fresh() : parents.getFirst().id(),
          id.value(),
          AuditRecord.Outcome.REFUSED,
          refused.reason().name(),
          label.get(),
          asking);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private <I, O> Derived<O> derivingAll(
      List<Handle<I>> parents,
      DerivationId<I, O> id,
      AccessContext context,
      AtomicReference<A> refused) {
    Derivation<A, I, O> derivation = (Derivation<A, I, O>) derivations.get(id.value());
    if (derivation == null) {
      return new Derived.Refused<>(
          Derived.Reason.NO_SUCH_DERIVATION, "no derivation is registered as '" + id + "'");
    }
    // Before anything is decoded, and thrown rather than refused. Asking a one-at-a-time
    // derivation to read three values is a mistake in the calling code, not a decision about
    // whether this access is allowed, and the two must not arrive looking alike.
    if (!derivation.readsMany() && parents.size() > 1) {
      throw new IllegalArgumentException(
          "'%s' reads one value at a time, and was given %d".formatted(id, parents.size()));
    }
    if (parents.isEmpty()) {
      return new Derived.Refused<>(
          Derived.Reason.NO_PARENTS, "'" + id + "' needs at least one value");
    }
    if (!offeredHere(() -> derivation.availableTo(context))) {
      return new Derived.Refused<>(
          Derived.Reason.NOT_AVAILABLE_HERE, "'" + id + "' is not offered here");
    }

    String expected = nameOf(derivation.inputType());
    List<I> inputs = new ArrayList<>();
    List<HandleId> parentIds = new ArrayList<>();
    A joined = null;
    for (Handle<I> parent : parents) {
      StoredMetadata<A> entry = storage.metadata(parent.id()).orElse(null);
      if (entry == null) {
        return new Derived.Refused<>(
            Derived.Reason.NO_SUCH_VALUE, "this loch is not holding " + parent.id());
      }
      if (!entry.typeName().equals(expected)) {
        return new Derived.Refused<>(
            Derived.Reason.WRONG_TYPE,
            "'%s' reads a %s, but %s is a %s"
                .formatted(id, expected, parent.id(), entry.typeName()));
      }
      Optional<A> ceiling = ceilingOf(() -> derivation.ceiling(context));
      if (ceiling == null) {
        return new Derived.Refused<>(
            Derived.Reason.ABOVE_CEILING,
            "'" + id + "' could not say what it accepts, so it does not accept this");
      }
      if (ceiling.isPresent() && !lattice.permits(entry.label(), ceiling.get())) {
        return new Derived.Refused<>(
            Derived.Reason.ABOVE_CEILING,
            parent.id() + " may not reach '" + id + "'" + explain(entry.label(), ceiling.get()));
      }
      I input = storage.value(parent.id(), derivation.inputType()).orElse(null);
      if (input == null) {
        return new Derived.Refused<>(
            Derived.Reason.NO_SUCH_VALUE, "this loch is not holding " + parent.id());
      }
      inputs.add(input);
      parentIds.add(parent.id());
      // Every parent contributes. This is the line that makes a mixed-tenant value unusable.
      joined = joined == null ? entry.label() : lattice.join(joined, entry.label());
      refused.set(joined);
    }

    Optional<O> produced;
    try {
      produced = derivation.apply(List.copyOf(inputs), context);
    } catch (RuntimeException e) {
      // It has already seen the plaintext, so this refusal has to be recorded like any other.
      return new Derived.Refused<>(
          Derived.Reason.DECLINED, "'" + id + "' failed while reading the value");
    }
    if (produced.isEmpty()) {
      return new Derived.Refused<>(Derived.Reason.DECLINED, "'" + id + "' declined");
    }

    A label = joined;
    Optional<java.util.function.UnaryOperator<A>> relabel = derivation.relabel();
    if (relabel.isPresent()) {
      try {
        label = relabel.get().apply(joined);
      } catch (RuntimeException e) {
        return new Derived.Refused<>(
            Derived.Reason.NOT_A_LOWERING, "'" + id + "' could not say what it was lowering to");
      }
      if (!lattice.permits(label, joined)) {
        return new Derived.Refused<>(
            Derived.Reason.NOT_A_LOWERING,
            "'%s' relabelled a value as something not below it".formatted(id)
                + explain(label, joined));
      }
    }

    HandleId newId = HandleId.fresh();
    audit(
        AuditRecord.Operation.DERIVE,
        newId,
        id.value(),
        AuditRecord.Outcome.ALLOWED,
        reasonFor(derivation, joined, parentIds.size()),
        label,
        context);
    storage.put(
        newId,
        new StoredValue<>(
            produced.get(),
            derivation.outputType(),
            label,
            Lineage.derivedFrom(parentIds, id.value())));
    return new Derived.Made<>(new Handle<>(newId, derivation.outputType()));
  }

  @Override
  public <I, O> Derived<O> derive(Handle<I> parent, DerivationId<I, O> id, AccessContext context) {
    return deriveAll(List.of(parent), id, context);
  }

  @Override
  public <T> Dereferenced<T> dereference(Handle<T> held, DestinationId to, AccessContext context) {
    context = asking(context);
    Destination<A> destination = destinations.get(to);
    if (destination == null) {
      return denied(
          Dereferenced.Reason.NO_SUCH_DESTINATION,
          "no destination is registered as '" + to + "'",
          held.id(),
          to.value(),
          null,
          context);
    }
    StoredMetadata<A> entry = storage.metadata(held.id()).orElse(null);
    if (entry == null) {
      return denied(
          Dereferenced.Reason.NO_SUCH_VALUE,
          "this loch is not holding " + held.id(),
          held.id(),
          to.value(),
          null,
          context);
    }
    if (!entry.typeName().equals(nameOf(held.type()))) {
      return denied(
          Dereferenced.Reason.WRONG_TYPE,
          held.id() + " is a " + entry.typeName() + ", not a " + nameOf(held.type()),
          held.id(),
          to.value(),
          entry.label(),
          context);
    }
    A ceiling = ceilingOf(destination, context);
    if (ceiling == null) {
      return denied(
          Dereferenced.Reason.ABOVE_CEILING,
          "'" + to + "' could not say what it accepts, so it does not accept this",
          held.id(),
          to.value(),
          entry.label(),
          context);
    }
    if (!lattice.permits(entry.label(), ceiling)) {
      return denied(
          Dereferenced.Reason.ABOVE_CEILING,
          held.id() + " may not reach '" + to + "'" + explain(entry.label(), ceiling),
          held.id(),
          to.value(),
          entry.label(),
          context);
    }
    audit(
        AuditRecord.Operation.DEREFERENCE,
        held.id(),
        to.value(),
        AuditRecord.Outcome.ALLOWED,
        null,
        entry.label(),
        context);
    // The type was confirmed against what the store wrote, so this decodes a verified fact.
    return storage
        .value(held.id(), held.type())
        .<Dereferenced<T>>map(Dereferenced.Allowed::new)
        .orElseGet(
            () ->
                new Dereferenced.Denied<>(
                    Dereferenced.Reason.NO_SUCH_VALUE, "this loch is not holding " + held.id()));
  }
}
