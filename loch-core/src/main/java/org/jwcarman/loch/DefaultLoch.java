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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  private final Map<String, Check<A, ?, ?>> checks;
  private final Map<String, Fold<A, ?, ?>> folds;
  private final boolean explainRefusals;
  private final Auditor auditor;
  private final java.util.function.Supplier<AccessContext> ambient;
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
    this.destinations = Map.copyOf(new HashMap<>(byId));
    Map<String, Derivation<A, ?, ?>> byName = new LinkedHashMap<>();
    for (Derivation<A, ?, ?> derivation : config.derivations()) {
      if (byName.put(derivation.id().value(), derivation) != null) {
        throw new IllegalStateException(
            "two derivations are registered as '" + derivation.id() + "'");
      }
    }
    this.derivations = Map.copyOf(new HashMap<>(byName));
    Map<String, Check<A, ?, ?>> byCheck = new LinkedHashMap<>();
    for (Check<A, ?, ?> check : config.checks()) {
      if (byCheck.put(check.id().value(), check) != null) {
        throw new IllegalStateException("two checks are registered as '" + check.id() + "'");
      }
    }
    this.checks = Map.copyOf(new HashMap<>(byCheck));
    Map<String, Fold<A, ?, ?>> byFold = new LinkedHashMap<>();
    for (Fold<A, ?, ?> fold : config.folds()) {
      if (byFold.put(fold.id().value(), fold) != null) {
        throw new IllegalStateException("two folds are registered as '" + fold.id() + "'");
      }
    }
    this.folds = Map.copyOf(new HashMap<>(byFold));
    this.explainRefusals = config.explainsRefusals();
    this.auditor = config.auditor();
    this.ambient = config.ambient();
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
   * Who is asking: what the loch was told, with anything the caller added laid over it.
   *
   * <p>Resolved once per operation, because an ambient source may be doing real work to answer.
   */
  private AccessContext asking(AccessContext explicit) {
    return explicit.over(ambient.get());
  }

  /**
   * Writes the line, and refuses the access if it cannot be written.
   *
   * <p>A control whose log is silently dropping entries still produces the report, so an access
   * that cannot be audited does not happen.
   */
  private void audit(
      AuditRecord.Operation operation,
      HeldId value,
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
      HeldId value,
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

  /** What a refusal is allowed to say about labels, which by default is nothing. */
  private String explain(A label, Object ceiling) {
    return explainRefusals ? " (labelled " + label + "; accepts " + ceiling + ")" : "";
  }

  @Override
  public <T> Held<T> hold(T value, TypeRef<T> type, A attribution) {
    if (value == null) {
      throw new IllegalArgumentException("a loch holds values, not nulls");
    }
    if (attribution == null) {
      throw new IllegalArgumentException(
          "a held value needs an attribution; use the lattice's bottom to say 'nothing in"
              + " particular'");
    }
    HeldId id = HeldId.fresh();
    storage.put(id, new StoredValue<>(value, type, attribution, Lineage.held()));
    audit(
        AuditRecord.Operation.HOLD,
        id,
        null,
        AuditRecord.Outcome.ALLOWED,
        null,
        attribution,
        AccessContext.empty());
    return new Held<>(id, type);
  }

  @Override
  public A attribution(Held<?> held) {
    return metadataOf(held).attribution();
  }

  private StoredMetadata<A> metadataOf(Held<?> held) {
    return storage
        .metadata(held.id())
        .orElseThrow(() -> new IllegalArgumentException("this loch is not holding " + held.id()));
  }

  /** What a handle says it is, in the form the store wrote it. */
  private static String nameOf(TypeRef<?> type) {
    return type.getType().getTypeName();
  }

  @Override
  public boolean holds(Held<?> held) {
    return storage.contains(held.id());
  }

  @Override
  public int erase(Held<?> root) {
    int removed = storage.erase(root.id());
    audit(
        AuditRecord.Operation.ERASE,
        root.id(),
        null,
        AuditRecord.Outcome.ALLOWED,
        removed + " values removed",
        null,
        AccessContext.empty());
    return removed;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <I, Q> Answer check(Held<I> held, CheckId<I, Q> id, Q question, AccessContext context) {
    context = asking(context);
    Check<A, I, Q> check = (Check<A, I, Q>) checks.get(id.value());
    if (check == null) {
      return new Answer.Refused(
          Answer.Reason.NO_SUCH_CHECK, "no check is registered as '" + id + "'");
    }
    if (!check.availableTo(context)) {
      return new Answer.Refused(
          Answer.Reason.NOT_AVAILABLE_HERE, "'" + id + "' is not offered here");
    }
    StoredMetadata<A> entry = storage.metadata(held.id()).orElse(null);
    if (entry == null) {
      return new Answer.Refused(
          Answer.Reason.NO_SUCH_VALUE, "this loch is not holding " + held.id());
    }
    if (!entry.typeName().equals(nameOf(check.inputType()))) {
      return new Answer.Refused(
          Answer.Reason.WRONG_TYPE,
          "'%s' asks about a %s, but %s is a %s"
              .formatted(id, nameOf(check.inputType()), held.id(), entry.typeName()));
    }
    Optional<A> ceiling = check.ceiling();
    if (ceiling.isPresent() && !lattice.permits(entry.attribution(), ceiling.get())) {
      return new Answer.Refused(
          Answer.Reason.ABOVE_CEILING,
          held.id()
              + " may not be looked at by '"
              + id
              + "'"
              + explain(entry.attribution(), ceiling.get()));
    }
    I subject = storage.value(held.id(), check.inputType()).orElse(null);
    if (subject == null) {
      return new Answer.Refused(
          Answer.Reason.NO_SUCH_VALUE, "this loch is not holding " + held.id());
    }
    boolean answer = check.test(subject, question, context);
    // The answer, never the question: what was asked can itself be sensitive.
    audit(
        AuditRecord.Operation.CHECK,
        held.id(),
        id.value(),
        AuditRecord.Outcome.ALLOWED,
        "answered " + answer,
        entry.attribution(),
        context);
    return new Answer.Answered(answer);
  }

  @Override
  public Lineage lineage(Held<?> held) {
    return metadataOf(held).lineage();
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
                    "%s -> %s, %s"
                        .formatted(
                            derivation.inputType().rawClass().getSimpleName(),
                            derivation.outputType().rawClass().getSimpleName(),
                            derivation.deterministic()
                                ? "deterministic v" + derivation.version()
                                : "not replay-safe"),
                    derivation.privileged())));
    List<Manifest.Entry> theChecks = new ArrayList<>();
    checks.forEach(
        (name, check) ->
            theChecks.add(
                new Manifest.Entry(
                    name, "asks about a " + check.inputType().rawClass().getSimpleName(), false)));
    return new Manifest(
        String.valueOf(lattice.bottom()), theDestinations, theDerivations, theChecks);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <I, O> Derived<O> deriveAll(
      List<Held<I>> parents, FoldId<I, O> id, AccessContext context) {
    context = asking(context);
    Fold<A, I, O> fold = (Fold<A, I, O>) folds.get(id.value());
    if (fold == null) {
      return new Derived.Refused<>(
          Derived.Reason.NO_SUCH_FOLD, "no fold is registered as '" + id + "'");
    }
    if (parents.isEmpty()) {
      return new Derived.Refused<>(
          Derived.Reason.NO_PARENTS, "'" + id + "' needs at least one value to fold");
    }
    if (!fold.availableTo(context)) {
      return new Derived.Refused<>(
          Derived.Reason.NOT_AVAILABLE_HERE, "'" + id + "' is not offered here");
    }

    String expected = nameOf(fold.inputType());
    List<I> inputs = new ArrayList<>();
    List<HeldId> parentIds = new ArrayList<>();
    A joined = null;
    for (Held<I> parent : parents) {
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
      Optional<A> ceiling = fold.ceiling();
      if (ceiling.isPresent() && !lattice.permits(entry.attribution(), ceiling.get())) {
        return new Derived.Refused<>(
            Derived.Reason.ABOVE_CEILING,
            parent.id()
                + " may not reach '"
                + id
                + "'"
                + explain(entry.attribution(), ceiling.get()));
      }
      I input = storage.value(parent.id(), fold.inputType()).orElse(null);
      if (input == null) {
        return new Derived.Refused<>(
            Derived.Reason.NO_SUCH_VALUE, "this loch is not holding " + parent.id());
      }
      inputs.add(input);
      parentIds.add(parent.id());
      // Every parent contributes. This is the line that makes a mixed-tenant value unusable.
      joined = joined == null ? entry.attribution() : lattice.join(joined, entry.attribution());
    }

    Optional<O> produced = fold.apply(List.copyOf(inputs), context);
    if (produced.isEmpty()) {
      return new Derived.Refused<>(Derived.Reason.DECLINED, "'" + id + "' declined");
    }

    A label = joined;
    Optional<java.util.function.UnaryOperator<A>> relabel = fold.relabel();
    if (relabel.isPresent()) {
      label = relabel.get().apply(joined);
      if (!lattice.permits(label, joined)) {
        return new Derived.Refused<>(
            Derived.Reason.NOT_A_LOWERING,
            "'%s' relabelled %s as %s, which is not below it".formatted(id, joined, label));
      }
    }

    HeldId newId =
        fold.deterministic()
            ? ContentAddress.of(parentIds, id.value(), fold.version())
            : HeldId.fresh();
    storage.put(
        newId,
        new StoredValue<>(
            produced.get(), fold.outputType(), label, Lineage.derivedFrom(parentIds, id.value())));
    audit(
        AuditRecord.Operation.DERIVE,
        newId,
        id.value(),
        AuditRecord.Outcome.ALLOWED,
        fold.privileged() ? "weakened from " + joined : "folded " + parentIds.size() + " values",
        label,
        context);
    return new Derived.Made<>(new Held<>(newId, fold.outputType()));
  }

  @Override
  @SuppressWarnings("unchecked")
  public <I, O> Derived<O> derive(Held<I> parent, DerivationId<I, O> id, AccessContext context) {
    context = asking(context);
    Derivation<A, I, O> derivation = (Derivation<A, I, O>) derivations.get(id.value());
    if (derivation == null) {
      return new Derived.Refused<>(
          Derived.Reason.NO_SUCH_DERIVATION, "no derivation is registered as '" + id + "'");
    }
    if (!derivation.availableTo(context)) {
      return new Derived.Refused<>(
          Derived.Reason.NOT_AVAILABLE_HERE, "'" + id + "' is not offered here");
    }
    StoredMetadata<A> entry = storage.metadata(parent.id()).orElse(null);
    if (entry == null) {
      return new Derived.Refused<>(
          Derived.Reason.NO_SUCH_VALUE, "this loch is not holding " + parent.id());
    }
    if (!entry.typeName().equals(nameOf(derivation.inputType()))) {
      return new Derived.Refused<>(
          Derived.Reason.WRONG_TYPE,
          "'%s' reads a %s, but %s is a %s"
              .formatted(id, nameOf(derivation.inputType()), parent.id(), entry.typeName()));
    }
    // A derivation is handed plaintext, so it is a destination and passes the same gate.
    Optional<A> ceiling = derivation.ceiling();
    if (ceiling.isPresent() && !lattice.permits(entry.attribution(), ceiling.get())) {
      return new Derived.Refused<>(
          Derived.Reason.ABOVE_CEILING,
          "%s is labelled %s; '%s' accepts %s"
              .formatted(parent.id(), entry.attribution(), id, ceiling.get()));
    }

    I input = storage.value(parent.id(), derivation.inputType()).orElse(null);
    if (input == null) {
      return new Derived.Refused<>(
          Derived.Reason.NO_SUCH_VALUE, "this loch is not holding " + parent.id());
    }
    Optional<O> produced = derivation.apply(input, context);
    if (produced.isEmpty()) {
      return new Derived.Refused<>(Derived.Reason.DECLINED, "'" + id + "' declined");
    }

    // One parent for now, but the fold is what makes several parents need no special case.
    A joined = entry.attribution();
    A label = joined;
    Optional<java.util.function.UnaryOperator<A>> relabel = derivation.relabel();
    if (relabel.isPresent()) {
      label = relabel.get().apply(joined);
      if (!lattice.permits(label, joined)) {
        return new Derived.Refused<>(
            Derived.Reason.NOT_A_LOWERING,
            "'%s' relabelled %s as %s, which is not below it; ordinary derivation already raises"
                .formatted(id, joined, label));
      }
    }

    List<HeldId> parents = List.of(parent.id());
    HeldId newId =
        derivation.deterministic()
            ? ContentAddress.of(parents, id.value(), derivation.version())
            : HeldId.fresh();
    storage.put(
        newId,
        new StoredValue<>(
            produced.get(),
            derivation.outputType(),
            label,
            Lineage.derivedFrom(parents, id.value())));
    audit(
        AuditRecord.Operation.DERIVE,
        newId,
        id.value(),
        AuditRecord.Outcome.ALLOWED,
        derivation.privileged() ? "weakened from " + joined : null,
        label,
        context);
    return new Derived.Made<>(new Held<>(newId, derivation.outputType()));
  }

  @Override
  public <T> Dereferenced<T> dereference(Held<T> held, DestinationId to, AccessContext context) {
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
          entry.attribution(),
          context);
    }
    A ceiling = ceilingOf(destination, context);
    if (ceiling == null) {
      return denied(
          Dereferenced.Reason.ABOVE_CEILING,
          "'" + to + "' could not say what it accepts, so it does not accept this",
          held.id(),
          to.value(),
          entry.attribution(),
          context);
    }
    if (!lattice.permits(entry.attribution(), ceiling)) {
      return denied(
          Dereferenced.Reason.ABOVE_CEILING,
          held.id() + " may not reach '" + to + "'" + explain(entry.attribution(), ceiling),
          held.id(),
          to.value(),
          entry.attribution(),
          context);
    }
    audit(
        AuditRecord.Operation.DEREFERENCE,
        held.id(),
        to.value(),
        AuditRecord.Outcome.ALLOWED,
        null,
        entry.attribution(),
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
