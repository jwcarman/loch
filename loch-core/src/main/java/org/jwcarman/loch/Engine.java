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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.loch.lattice.Axes;
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
final class Engine {

  private static final String NOT_HOLDING = "this store is not holding ";
  private static final String COULD_NOT_SAY_WHAT_IT_ACCEPTS =
      "' could not say what it accepts, so it does not accept this";

  private final Axes axes;
  private final Map<String, DestinationSpec> destinations;
  private final AccessContextProvider ambient;
  private final java.util.function.BiPredicate<Label, AccessContext> mayErase;
  private final Storage storage;

  Engine(Axes axes, DefaultCharter.Configuration config, Storage storage) {
    this.storage = storage;
    this.axes = axes;
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
    this.ambient = config.currentAccess();
    this.mayErase = config.mayErase();
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
    } catch (RuntimeException _) {
      label = null;
    }
    if (label == null) {
      audit(
          AuditRecord.Operation.CONCEAL,
          storage.freshId(),
          source,
          AuditRecord.Outcome.REFUSED,
          Why.of("the source could not say how to label this"),
          null,
          asking);
      throw new AccessDeniedException(
          "SOURCE_CANNOT_LABEL", "'" + source + "' could not say what it labels values");
    }
    // One of two places a label can be incomplete. Join only moves up, so an ordinary derivation
    // cannot lose what was said here -- but a privileged one may relabel, so deriving checks too.
    if (leavesARequiredAxisUnsaid(label)) {
      audit(
          AuditRecord.Operation.CONCEAL,
          storage.freshId(),
          source,
          AuditRecord.Outcome.REFUSED,
          Why.of("the label leaves a required axis unsaid"),
          label,
          asking);
      throw new AccessDeniedException(
          "INCOMPLETE_LABEL",
          ("'%s' produced a label that leaves a required axis unsaid. Unsaid is the bottom of its"
                  + " order, which is below every ceiling, so the value would have been readable"
                  + " by everyone.")
              .formatted(source));
    }
    String id = storage.freshId();
    // One act: the value and the record that it arrived. The source is named, so the record says
    // which door it came in through.
    AuditRecord entry =
        entry(
            AuditRecord.Operation.CONCEAL,
            id,
            source,
            AuditRecord.Outcome.ALLOWED,
            Why.nothing(),
            label,
            asking);
    storage.put(id, new StoredValue(value, type, label, Lineage.concealed()), entry);
    return new Surrogate<>(id);
  }

  /**
   * Whether a reader holding this ceiling may see a value labelled so.
   *
   * <p>Not simply {@link Ceiling#permits}, because a ceiling can only judge the axes a label speaks
   * to. An axis a label never mentions sits at the bottom of its order, and bottom is below every
   * ceiling -- so an incomplete label is not refused by everyone, it is admitted by everyone, which
   * is the exact opposite of what marking an axis required means.
   *
   * <p>Concealing refuses an incomplete label, and so does a lowering, but neither covers a row
   * that was already there: a store that adds {@code required()} to an axis it has been writing
   * without, or an SPI caller that put a value directly. Checking it here, where a stored label is
   * read back, is what makes the guarantee about values rather than about writes.
   */
  private boolean admits(Ceiling ceiling, Label label) {
    return !leavesARequiredAxisUnsaid(label) && ceiling.permits(label);
  }

  /**
   * Whether a label leaves an axis unsaid that this store said it must not.
   *
   * <p>Checked at the one door a label is written through. Unsaid is the bottom of an axis's order,
   * which is below every ceiling, so a value that left a required axis unsaid would be readable by
   * everyone -- silently, and in the direction nobody would notice.
   */
  private boolean leavesARequiredAxisUnsaid(Label label) {
    for (Axis<?> axis : axes) {
      if (label.unsaid(axis)) {
        return true;
      }
    }
    return false;
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
    } catch (RuntimeException _) {
      return null;
    }
  }

  /**
   * The same treatment for a question's or a derivation's ceiling, which is equally application
   * code.
   *
   * <p>{@code null} means the ceiling did not decide, and every caller treats that as a refusal.
   * There is deliberately no second shape for "accepts anything": a derivation or a question
   * without a ceiling is refused at configuration, so a ceiling is always present and always has to
   * be consulted. This used to return an {@code Optional}, where empty was documented as accepting
   * anything -- nothing could produce that empty on purpose, but application code ending in {@code
   * .orElse(null)} produced it by accident, and the check was skipped.
   */
  private Ceiling ceilingOf(java.util.function.Supplier<Ceiling> ceiling) {
    try {
      return ceiling.get();
    } catch (RuntimeException _) {
      return null;
    }
  }

  /** A gate that cannot say whether it is open has not said it is open. */
  private boolean offeredHere(java.util.function.BooleanSupplier availableTo) {
    try {
      return availableTo.getAsBoolean();
    } catch (RuntimeException _) {
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
   * Writes the record. There is no way to turn this off, which is the point of it.
   *
   * <p>A control whose log is silently dropping entries still produces the report, so an access
   * that cannot be audited does not happen.
   */
  private void audit(
      AuditRecord.Operation operation,
      String value,
      String target,
      AuditRecord.Outcome outcome,
      Why why,
      Label label,
      AccessContext context) {
    storage.append(entry(operation, value, target, outcome, why, label, context));
  }

  private AuditRecord entry(
      AuditRecord.Operation operation,
      String value,
      String target,
      AuditRecord.Outcome outcome,
      Why why,
      Label label,
      AccessContext context) {
    return new AuditRecord(
        operation,
        value,
        Optional.ofNullable(target),
        outcome,
        Optional.ofNullable(why.code()),
        Optional.ofNullable(why.detail()),
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
        AuditRecord.Operation.REVEAL,
        value,
        target,
        AuditRecord.Outcome.REFUSED,
        Why.of(reason.name(), because),
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

  public Label label(String id) {
    return metadataOf(id).label();
  }

  private StoredMetadata metadataOf(String id) {
    return storage.metadata(id).orElseThrow(() -> new IllegalArgumentException(NOT_HOLDING + id));
  }

  public boolean holds(String id) {
    return storage.contains(id);
  }

  public int erase(Surrogate<?> root) {
    AccessContext asking = asking();
    StoredMetadata entry = storage.metadata(root.id()).orElse(null);
    if (entry == null) {
      // Recorded like every other operation. Asking to destroy something that is not here is an
      // event worth seeing -- a probe looks exactly like this, repeatedly -- and a trail that
      // records only the attempts that found something cannot show it.
      audit(
          AuditRecord.Operation.ERASE,
          root.id(),
          null,
          AuditRecord.Outcome.REFUSED,
          Why.of("no such value"),
          null,
          asking);
      return 0;
    }
    // Application code, so it throws, and a gate that could not decide has not said yes. Left to
    // propagate, an erasure nobody was allowed to attempt left no line saying it was attempted.
    boolean permitted;
    try {
      permitted = mayErase.test(entry.label(), asking);
    } catch (RuntimeException _) {
      permitted = false;
    }
    if (!permitted) {
      audit(
          AuditRecord.Operation.ERASE,
          root.id(),
          null,
          AuditRecord.Outcome.REFUSED,
          Why.of("not permitted to erase"),
          null,
          asking);
      throw new AccessDeniedException(
          Revealed.Reason.ABOVE_CEILING,
          "erasing is refused: this store was not told who may erase");
    }
    // One line per value, not one per call, and written by the storage inside the same
    // transaction as the deletes. Every other operation writes a line naming the value it acted
    // on, and erasure is where that matters most: it is the only operation that makes a value
    // stop existing, so the trail becomes the only thing that can say the value ever did. A
    // single line saying "41 values removed" cannot tell a lawful erasure from a quiet deletion,
    // because nothing afterwards knows which 41.
    //
    // Handing the line to storage rather than writing them here is what makes it repairable. Done
    // afterwards, a crash between the deletes and the lines leaves values destroyed that the
    // trail never says were destroyed, the chain still verifies, and erasing again finds nothing
    // to erase -- a permanent tamper alarm for something nobody did.
    List<String> removed =
        storage.erase(
            root.id(),
            id ->
                entry(
                    AuditRecord.Operation.ERASE,
                    id,
                    root.id(),
                    AuditRecord.Outcome.ALLOWED,
                    Why.of("erased"),
                    null,
                    asking));
    return removed.size();
  }

  <I, Q> Answer askVia(QuerySpec<I, Q> spec, Surrogate<I> about, Q against) {
    AccessContext asking = asking();
    AtomicReference<Label> label = new AtomicReference<>();
    AtomicReference<String> because = new AtomicReference<>();
    Answer answer = answering(spec, about, against, asking, label, because);
    if (answer instanceof Answer.Refused refused) {
      audit(
          AuditRecord.Operation.QUERY,
          about.id(),
          spec.name(),
          AuditRecord.Outcome.REFUSED,
          Why.of(refused.reason().name(), because.get()),
          label.get(),
          asking);
    }
    return answer;
  }

  /**
   * What a refusal is allowed to say about labels, which by default is nothing.
   *
   * <p>{@code refused} and {@code because} are filled in only as far as the check got before saying
   * no, so the caller's audit line says exactly what was known at the point of refusal -- never
   * more, and never a label this call never actually looked at.
   */
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
      return new Answer.Refused(Answer.Reason.NO_SUCH_VALUE, NOT_HOLDING + held.id());
    }
    refused.set(entry.label());
    Ceiling ceiling = ceilingOf(() -> spec.ceilingFor(context));
    if (ceiling == null) {
      return new Answer.Refused(
          Answer.Reason.ABOVE_CEILING, "'" + name + COULD_NOT_SAY_WHAT_IT_ACCEPTS);
    }
    if (!admits(ceiling, entry.label())) {
      because.set(because(entry.label(), ceiling));
      return new Answer.Refused(
          Answer.Reason.ABOVE_CEILING, held.id() + " may not be looked at by '" + name + "'");
    }
    if (!entry.typeName().equals(spec.inputType().name())) {
      return new Answer.Refused(
          Answer.Reason.WRONG_TYPE,
          "'%s' asks about a %s, but %s is a %s"
              .formatted(name, spec.inputType().name(), held.id(), entry.typeName()));
    }
    I subject = storage.value(held.id(), spec.inputType().type()).orElse(null);
    if (subject == null) {
      return new Answer.Refused(Answer.Reason.NO_SUCH_VALUE, NOT_HOLDING + held.id());
    }
    boolean answer;
    try {
      answer = spec.asking().test(subject, against, context);
    } catch (RuntimeException _) {
      // It has already read the plaintext, so this refusal is recorded like any other.
      return new Answer.Refused(
          Answer.Reason.NOT_AVAILABLE_HERE, "'" + name + "' failed while reading the value");
    }
    // The answer, never what was asked: the argument can itself be sensitive.
    audit(
        AuditRecord.Operation.QUERY,
        held.id(),
        name,
        AuditRecord.Outcome.ALLOWED,
        Why.of("answered " + answer),
        entry.label(),
        context);
    return new Answer.Answered(answer);
  }

  public Lineage lineage(String id) {
    return metadataOf(id).lineage();
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
          parents.isEmpty() ? storage.freshId() : parents.getFirst().id(),
          spec.name(),
          AuditRecord.Outcome.REFUSED,
          Why.of(refused.reason().name(), because.get()),
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
    Derived.Refused<O> unusable = unusable(spec, parents, context);
    if (unusable != null) {
      return unusable;
    }
    // Once, not once per parent: a ceiling that reads ambient context is doing real work. Before
    // any parent is looked at, and deliberately before the type check: a caller who may not reach
    // a value must not learn what kind of value it is.
    Ceiling ceiling = ceilingOf(() -> spec.ceilingFor(context));
    if (ceiling == null) {
      return new Derived.Refused<>(
          Derived.Reason.ABOVE_CEILING, "'" + id + COULD_NOT_SAY_WHAT_IT_ACCEPTS);
    }

    List<String> parentIds = new ArrayList<>();
    for (Surrogate<?> parent : parents) {
      parentIds.add(parent.id());
    }
    Vetted<O> vetted = vetting(spec, parents, parentIds, ceiling, refused, because);
    if (vetted.refusal() != null) {
      return vetted.refusal();
    }

    // Only now, and only for parents every check above let through.
    Read<O> read = reading(parents, vetted.wanted());
    if (read.refusal() != null) {
      return read.refusal();
    }

    Optional<O> produced = producing(spec, read.inputs(), context);
    if (produced == null) {
      // It has already seen the plaintext, so this refusal has to be recorded like any other.
      return new Derived.Refused<>(
          Derived.Reason.DECLINED, "'" + id + "' failed while reading the value");
    }
    if (produced.isEmpty()) {
      return new Derived.Refused<>(Derived.Reason.DECLINED, "'" + id + "' declined");
    }

    Relabelled<O> relabelled = relabelling(spec, vetted.joined(), because);
    if (relabelled.refusal() != null) {
      return relabelled.refusal();
    }
    return writing(spec, produced.get(), relabelled.label(), vetted.joined(), parentIds, context);
  }

  /**
   * The refusals that need neither a ceiling nor a parent: nothing to read, and not offered here.
   *
   * <p>{@code null} means nothing here refused it. The arity mismatch in the middle is not a
   * refusal at all -- a fixed-arity capability cannot be called with the wrong number of handles,
   * so reaching it means the spec and the capability that declared it disagree. That is a bug here,
   * not there.
   */
  private <O> Derived.Refused<O> unusable(
      DerivationSpec<O> spec, List<Surrogate<?>> parents, AccessContext context) {
    String id = spec.name();
    if (parents.isEmpty()) {
      return new Derived.Refused<>(
          Derived.Reason.NO_PARENTS, "'" + id + "' needs at least one value");
    }
    if (!spec.fold() && parents.size() != spec.inputTypes().size()) {
      throw new IllegalStateException(
          "'%s' reads %d values and was given %d"
              .formatted(id, spec.inputTypes().size(), parents.size()));
    }
    if (!offeredHere(() -> spec.availableTo().test(context))) {
      return new Derived.Refused<>(
          Derived.Reason.NOT_AVAILABLE_HERE, "'" + id + "' is not offered here");
    }
    return null;
  }

  /**
   * What checking the parents produced: what may now be decoded, and the label the result carries
   * -- or the refusal that stopped it, in which case neither of the others was reached.
   */
  private record Vetted<O>(
      Map<String, TypeRef<?>> wanted, Label joined, Derived.Refused<O> refusal) {

    static <O> Vetted<O> refusing(Derived.Reason reason, String detail) {
      return new Vetted<>(null, null, new Derived.Refused<>(reason, detail));
    }
  }

  /**
   * Every parent's label and type, against the position it was handed in at.
   *
   * <p>Labels first, for every parent at once, and no plaintext anywhere near this. A fold over ten
   * parents used to be ten round trips here and ten more below; it is one and one.
   *
   * <p>The ceiling is checked before the type, for the same reason it is checked before anything: a
   * caller who may not reach a value learns nothing about it beyond that.
   */
  private <O> Vetted<O> vetting(
      DerivationSpec<O> spec,
      List<Surrogate<?>> parents,
      List<String> parentIds,
      Ceiling ceiling,
      AtomicReference<Label> refused,
      AtomicReference<String> because) {
    String id = spec.name();
    Map<String, StoredMetadata> labels = storage.metadata(parentIds);
    Map<String, TypeRef<?>> wanted = new LinkedHashMap<>();
    Label joined = null;
    for (int position = 0; position < parents.size(); position++) {
      Surrogate<?> parent = parents.get(position);
      SurrogateType<?> expected = spec.typeAt(position);
      StoredMetadata entry = labels.get(parent.id());
      if (entry == null) {
        return Vetted.refusing(Derived.Reason.NO_SUCH_VALUE, NOT_HOLDING + parent.id());
      }
      if (!admits(ceiling, entry.label())) {
        because.set(because(entry.label(), ceiling));
        return Vetted.refusing(
            Derived.Reason.ABOVE_CEILING, parent.id() + " may not reach '" + id + "'");
      }
      if (!entry.typeName().equals(expected.name())) {
        return Vetted.refusing(
            Derived.Reason.WRONG_TYPE,
            "'%s' reads a %s in position %d, but %s is a %s"
                .formatted(id, expected.name(), position + 1, parent.id(), entry.typeName()));
      }
      wanted.put(parent.id(), expected.type());
      // Every parent contributes. This is the line that makes a mixed-tenant value unusable.
      joined = joined == null ? entry.label() : joined.join(entry.label());
      refused.set(joined);
    }
    return new Vetted<>(wanted, joined, null);
  }

  /** The plaintext, in the order the parents were handed in -- or the refusal instead of it. */
  private record Read<O>(List<Object> inputs, Derived.Refused<O> refusal) {}

  /** Decodes only what every check above let through, and refuses a parent erased meanwhile. */
  private <O> Read<O> reading(List<Surrogate<?>> parents, Map<String, TypeRef<?>> wanted) {
    Map<String, Object> read = storage.values(wanted);
    List<Object> inputs = new ArrayList<>();
    for (Surrogate<?> parent : parents) {
      Object input = read.get(parent.id());
      if (input == null) {
        return new Read<>(
            null, new Derived.Refused<>(Derived.Reason.NO_SUCH_VALUE, NOT_HOLDING + parent.id()));
      }
      inputs.add(input);
    }
    return new Read<>(inputs, null);
  }

  /**
   * Runs the application's function over the plaintext.
   *
   * <p>{@code null} means it threw; an empty {@link Optional} means it declined. Both are recorded
   * refusals rather than exceptions, because by this point it has been handed the plaintext.
   *
   * <p>A function that answers null has broken its own contract, and that is normalised where it
   * arrives rather than checked later: it is the same event as one that threw, and neither may end
   * as a NullPointerException thrown out of the library, past the audit, after the value was read.
   */
  private <O> Optional<O> producing(
      DerivationSpec<O> spec, List<Object> inputs, AccessContext context) {
    try {
      return Objects.requireNonNullElse(
          spec.function().apply(List.copyOf(inputs), context), Optional.empty());
    } catch (RuntimeException _) {
      return null;
    }
  }

  /**
   * The label the result will carry, or the refusal that says the relabelling was not a lowering.
   */
  private record Relabelled<O>(Label label, Derived.Refused<O> refusal) {

    static <O> Relabelled<O> refusing(String detail) {
      return new Relabelled<>(null, new Derived.Refused<>(Derived.Reason.NOT_A_LOWERING, detail));
    }
  }

  /**
   * What a privileged derivation asked for, checked against what it was allowed to ask for.
   *
   * <p>An ordinary derivation carries the join and comes straight back out. A privileged one has to
   * name a label at or below it, and has to leave no required axis unsaid.
   */
  private <O> Relabelled<O> relabelling(
      DerivationSpec<O> spec, Label joined, AtomicReference<String> because) {
    String id = spec.name();
    if (spec.relabel() == null) {
      return new Relabelled<>(joined, null);
    }
    Label label;
    try {
      label = spec.relabel().apply(joined);
    } catch (RuntimeException _) {
      label = null;
    }
    // Answering with nothing is not answering, and is the same event as throwing. The plaintext has
    // already been read, so this has to be a recorded refusal rather than a NullPointerException
    // thrown past the audit.
    if (label == null) {
      return Relabelled.refusing("'" + id + "' could not say what it was lowering to");
    }
    if (!label.atOrBelow(joined)) {
      because.set(because(label, joined));
      return Relabelled.refusing("'%s' relabelled a value as something not below it".formatted(id));
    }
    // atOrBelow cannot see this one. An axis a label stops mentioning joins as bottom, so a label
    // that drops one is at or below everything -- including the label it came from. The check that
    // runs at the door has to run here too, because this is the other way a value can come to exist
    // with a required axis missing.
    if (leavesARequiredAxisUnsaid(label)) {
      because.set(because(label, joined));
      return Relabelled.refusing(
          ("'%s' relabelled a value so that a required axis is unsaid. Unsaid is the bottom of its"
                  + " order, which is below every ceiling, so the result would have been readable"
                  + " by everyone.")
              .formatted(id));
    }
    return new Relabelled<>(label, null);
  }

  /** The child and the line saying it was made, written as one act. */
  private <O> Derived<O> writing(
      DerivationSpec<O> spec,
      O value,
      Label label,
      Label joined,
      List<String> parentIds,
      AccessContext context) {
    String id = spec.name();
    String newId = storage.freshId();
    AuditRecord entry =
        entry(
            AuditRecord.Operation.DERIVE,
            newId,
            id,
            AuditRecord.Outcome.ALLOWED,
            Why.of(reasonFor(spec, joined, parentIds.size())),
            label,
            context);
    try {
      storage.put(
          newId,
          new StoredValue(value, spec.outputType(), label, Lineage.derivedFrom(parentIds, id)),
          entry);
    } catch (RuntimeException _) {
      // A parent can be erased while the derivation function is running: every check passed, the
      // plaintext was read, and by the time the child is written its ancestry is gone. The read
      // happened, so this is a refusal that has to be recorded, not an exception that escapes
      // past the audit -- the same rule that covers application code failing after it has seen
      // the value.
      audit(
          AuditRecord.Operation.DERIVE,
          newId,
          id,
          AuditRecord.Outcome.REFUSED,
          Why.of("could not be written"),
          label,
          context);
      return new Derived.Refused<>(
          Derived.Reason.NO_SUCH_VALUE, "'" + id + "' could not be completed");
    }
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
          NOT_HOLDING + held.id(),
          null,
          held.id(),
          to,
          null,
          context);
    }
    // The ceiling first, and the type only after it. A reader who may not see this value must not
    // learn what kind of value it is: "is a card, not an email" is the disclosure this library
    // exists to prevent, handed over in the refusal. Being told the wrong type is now something
    // only a reader already entitled to the value can be told.
    Ceiling ceiling = ceilingOf(destination, context);
    if (ceiling == null) {
      return denied(
          Revealed.Reason.ABOVE_CEILING,
          "'" + to + COULD_NOT_SAY_WHAT_IT_ACCEPTS,
          null,
          held.id(),
          to,
          entry.label(),
          context);
    }
    if (!admits(ceiling, entry.label())) {
      return denied(
          Revealed.Reason.ABOVE_CEILING,
          held.id() + " may not reach '" + to + "'",
          because(entry.label(), ceiling),
          held.id(),
          to,
          entry.label(),
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
    audit(
        AuditRecord.Operation.REVEAL,
        held.id(),
        to,
        AuditRecord.Outcome.ALLOWED,
        Why.nothing(),
        entry.label(),
        context);
    // The type was confirmed against what the store wrote, so this decodes a verified fact.
    return storage
        .value(held.id(), expected.type())
        .<Revealed<T>>map(Revealed.Allowed::new)
        .orElseGet(
            () -> new Revealed.Denied<>(Revealed.Reason.NO_SUCH_VALUE, NOT_HOLDING + held.id()));
  }
}
