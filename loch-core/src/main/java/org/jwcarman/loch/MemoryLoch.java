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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jwcarman.loch.lattice.Lattice;

/**
 * A loch that keeps everything in memory.
 *
 * <p>For tests, for single-process tools, and for working out whether the shape is right before a
 * database is involved. It does not encrypt, it does not survive a restart, and it does not audit
 * -- so it is not the thing to put in front of real cardholder data.
 *
 * <p>What it does do is enforce the gate exactly as a durable implementation must, which is what
 * makes it useful for proving a policy before deploying it.
 *
 * <p><b>It stores references, not copies.</b> A durable implementation serialises on the way in and
 * hands back a fresh object every time; this one does not, so a caller that mutates a value after
 * holding it changes what was stored, and a caller that mutates what it dereferenced changes what
 * everyone else sees. Hold immutable values and the difference never shows.
 */
public final class MemoryLoch<A> implements Loch<A> {

  private record Entry<A>(Object value, Class<?> type, A attribution, Lineage lineage) {}

  private final Lattice<A> lattice;
  private final Map<DestinationId, Destination<A>> destinations;
  private final Map<String, Derivation<A, ?, ?>> derivations;
  private final Map<String, Check<A, ?, ?>> checks;
  private final boolean explainRefusals;
  private final Auditor auditor;
  private final Map<HeldId, Entry<A>> entries = new ConcurrentHashMap<>();

  private MemoryLoch(LochConfig<A> config) {
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
    this.explainRefusals = config.explainsRefusals();
    this.auditor = config.auditor();
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

  /** Builds one. The customizer is where the lattice and the destinations are declared. */
  public static <A> MemoryLoch<A> create(Consumer<LochConfig<A>> customizer) {
    LochConfig<A> config = new LochConfig<>();
    customizer.accept(config);
    return new MemoryLoch<>(config);
  }

  @Override
  public <T> Held<T> hold(T value, A attribution) {
    if (value == null) {
      throw new IllegalArgumentException("a loch holds values, not nulls");
    }
    if (attribution == null) {
      throw new IllegalArgumentException(
          "a held value needs an attribution; use the lattice's bottom to say 'nothing in"
              + " particular'");
    }
    @SuppressWarnings("unchecked")
    Class<T> type = (Class<T>) value.getClass();
    HeldId id = HeldId.fresh();
    entries.put(id, new Entry<>(value, type, attribution, Lineage.held()));
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
    Entry<A> entry = entries.get(held.id());
    if (entry == null) {
      throw new IllegalArgumentException("this loch is not holding " + held.id());
    }
    return entry.attribution();
  }

  @Override
  public boolean holds(Held<?> held) {
    return entries.containsKey(held.id());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <I, Q> Answer check(Held<I> held, CheckId<I, Q> id, Q question, AccessContext context) {
    Check<A, I, Q> check = (Check<A, I, Q>) checks.get(id.value());
    if (check == null) {
      return new Answer.Refused(
          Answer.Reason.NO_SUCH_CHECK, "no check is registered as '" + id + "'");
    }
    if (!check.availableTo(context)) {
      return new Answer.Refused(
          Answer.Reason.NOT_AVAILABLE_HERE, "'" + id + "' is not offered here");
    }
    Entry<A> entry = entries.get(held.id());
    if (entry == null) {
      return new Answer.Refused(
          Answer.Reason.NO_SUCH_VALUE, "this loch is not holding " + held.id());
    }
    if (!check.inputType().isAssignableFrom(entry.type())) {
      return new Answer.Refused(
          Answer.Reason.WRONG_TYPE,
          "'%s' asks about a %s, but %s is a %s"
              .formatted(
                  id, check.inputType().getSimpleName(), held.id(), entry.type().getSimpleName()));
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
    boolean answer = check.test(check.inputType().cast(entry.value()), question, context);
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
    Entry<A> entry = entries.get(held.id());
    if (entry == null) {
      throw new IllegalArgumentException("this loch is not holding " + held.id());
    }
    return entry.lineage();
  }

  @Override
  public List<String> manifest() {
    List<String> lines = new ArrayList<>();
    derivations.values().stream()
        .filter(Derivation::privileged)
        .forEach(
            derivation ->
                lines.add(
                    "%s: %s -> %s, may weaken labels"
                        .formatted(
                            derivation.id(),
                            derivation.inputType().getSimpleName(),
                            derivation.outputType().getSimpleName())));
    return List.copyOf(lines);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <I, O> Derived<O> derive(Held<I> parent, DerivationId<I, O> id, AccessContext context) {
    Derivation<A, I, O> derivation = (Derivation<A, I, O>) derivations.get(id.value());
    if (derivation == null) {
      return new Derived.Refused<>(
          Derived.Reason.NO_SUCH_DERIVATION, "no derivation is registered as '" + id + "'");
    }
    if (!derivation.availableTo(context)) {
      return new Derived.Refused<>(
          Derived.Reason.NOT_AVAILABLE_HERE, "'" + id + "' is not offered here");
    }
    Entry<A> entry = entries.get(parent.id());
    if (entry == null) {
      return new Derived.Refused<>(
          Derived.Reason.NO_SUCH_VALUE, "this loch is not holding " + parent.id());
    }
    if (!derivation.inputType().isAssignableFrom(entry.type())) {
      return new Derived.Refused<>(
          Derived.Reason.WRONG_TYPE,
          "'%s' reads a %s, but %s is a %s"
              .formatted(
                  id,
                  derivation.inputType().getSimpleName(),
                  parent.id(),
                  entry.type().getSimpleName()));
    }
    // A derivation is handed plaintext, so it is a destination and passes the same gate.
    Optional<A> ceiling = derivation.ceiling();
    if (ceiling.isPresent() && !lattice.permits(entry.attribution(), ceiling.get())) {
      return new Derived.Refused<>(
          Derived.Reason.ABOVE_CEILING,
          "%s is labelled %s; '%s' accepts %s"
              .formatted(parent.id(), entry.attribution(), id, ceiling.get()));
    }

    Optional<O> produced = derivation.apply(derivation.inputType().cast(entry.value()), context);
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
    entries.put(
        newId,
        new Entry<>(
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
    Entry<A> entry = entries.get(held.id());
    if (entry == null) {
      return denied(
          Dereferenced.Reason.NO_SUCH_VALUE,
          "this loch is not holding " + held.id(),
          held.id(),
          to.value(),
          null,
          context);
    }
    if (!held.type().isAssignableFrom(entry.type())) {
      return denied(
          Dereferenced.Reason.WRONG_TYPE,
          held.id()
              + " is a "
              + entry.type().getSimpleName()
              + ", not a "
              + held.type().getSimpleName(),
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
    return new Dereferenced.Allowed<>(held.type().cast(entry.value()));
  }
}
