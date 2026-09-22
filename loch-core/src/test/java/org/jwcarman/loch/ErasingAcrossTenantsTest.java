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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Constraint;
import org.jwcarman.loch.lattice.Label;

/**
 * Erasing somebody else's data.
 *
 * <p>Erasure is the one operation a label cannot decide on its own, because destroying a value is
 * not reading it: a compliance officer is expected to remove data they were never entitled to look
 * at. So the policy sees both what is being erased and who is asking, and this is the test that it
 * really sees both. It was written after a version that saw only the who, under which acme's
 * compliance officer could erase globex's records.
 */
@DisplayName("Erasing somebody else's data")
class ErasingAcrossTenantsTest {

  private static final SurrogateType<Record> RECORD_TYPE = SurrogateType.of(Record.class);

  enum Level {
    LOW,
    HIGH
  }

  record Record(String text) {}

  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axis<Level> LEVEL = Axis.ladder("level", Level.LOW, Level.HIGH);

  @Test
  @DisplayName("is refused, even for a compliance officer")
  void is_refused_even_for_a_compliance_officer() {
    AtomicReference<AccessContext> edge = new AtomicReference<>(AccessContext.empty());

    DefaultCharter config =
        new DefaultCharter(TENANT, LEVEL)
            .currentAccess(edge::get)
            .mayErase(
                (label, ctx) ->
                    ctx.has("role", "compliance")
                        && ctx.get("tenant")
                            .map(
                                tenant ->
                                    Ceiling.of(TENANT, Constraint.atMost(tenant))
                                        .with(LEVEL, Constraint.any())
                                        .permits(label))
                            .orElse(false));

    Conceal<Record> globexRecords =
        config.source(
            "globex-records",
            RECORD_TYPE,
            ctx -> Label.of(TENANT, "globex").with(LEVEL, Level.HIGH));

    config.seal(new MemoryStorage());

    Surrogate<Record> globexRecord = globexRecords.conceal(new Record("globex's records"));

    edge.set(AccessContext.of(Map.of("tenant", "acme", "role", "compliance")));

    assertThat(catchThrowable(() -> config.erase(globexRecord)))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(config.holds(globexRecord.id())).isTrue();
  }

  /**
   * Asking to destroy something that is not here is still an attempt.
   *
   * <p>A trail recording only the attempts that found something cannot show a probe, which looks
   * exactly like this, repeatedly. Every other operation writes a line whether or not the value was
   * there; this one used to return zero in silence.
   */
  @Test
  @DisplayName("records an attempt to erase a value that is not here")
  void records_an_attempt_to_erase_what_is_not_here() {
    MemoryStorage storage = new MemoryStorage();
    DefaultCharter config = new DefaultCharter(TENANT, LEVEL).mayErase((label, ctx) -> true);
    config.seal(storage);

    assertThat(config.erase(Surrogate.of("sur_never-existed"))).isZero();

    assertThat(storage.audit(AuditRecord.Operation.ERASE))
        .isNotEmpty()
        .allSatisfy(line -> assertThat(line.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED));
  }

  /**
   * Removes a root and everything derived from it, however deeply, and writes one line per value
   * removed -- the trail being the only thing afterwards that can say a value ever existed.
   */
  @Test
  @DisplayName("removes a root and everything derived from it, one line per value")
  void removes_a_root_and_everything_derived_from_it() {
    MemoryStorage storage = new MemoryStorage();
    DefaultCharter config = new DefaultCharter(TENANT, LEVEL).mayErase((label, ctx) -> true);
    Conceal<Record> records =
        config.source(
            "records", RECORD_TYPE, ctx -> Label.of(TENANT, "acme").with(LEVEL, Level.LOW));
    Derivation<Record, Record> copy =
        config.derivation(
            "copy",
            RECORD_TYPE,
            RECORD_TYPE,
            r -> new Record(r.text() + "-copy"),
            d -> d.accepting(Ceiling.of(TENANT, Constraint.any()).with(LEVEL, Constraint.any())));
    config.seal(storage);

    Surrogate<Record> root = records.conceal(new Record("root"));
    Surrogate<Record> child = copy.derive(root).orThrow();

    int removed = config.erase(root);

    assertThat(removed).isEqualTo(2);
    assertThat(config.holds(root)).isFalse();
    assertThat(config.holds(child)).isFalse();
    assertThat(storage.audit(AuditRecord.Operation.ERASE))
        .hasSize(2)
        .allSatisfy(line -> assertThat(line.outcome()).isEqualTo(AuditRecord.Outcome.ALLOWED));
  }

  /**
   * A value reached by more than one path from the root -- a fold combining two of the root's own
   * children -- must still be removed exactly once, not once per path that finds it.
   */
  @Test
  @DisplayName("removes a value reached by two different paths from the root only once")
  void removes_a_value_reached_by_two_paths_only_once() {
    record Branch(String tag) {}
    record Combined(String tag) {}
    SurrogateType<Branch> branchType = SurrogateType.of(Branch.class);
    SurrogateType<Combined> combinedType = SurrogateType.of(Combined.class);

    MemoryStorage storage = new MemoryStorage();
    DefaultCharter config = new DefaultCharter(TENANT, LEVEL).mayErase((label, ctx) -> true);
    Conceal<Record> records =
        config.source(
            "records", RECORD_TYPE, ctx -> Label.of(TENANT, "acme").with(LEVEL, Level.LOW));
    Ceiling anything = Ceiling.of(TENANT, Constraint.any()).with(LEVEL, Constraint.any());
    Derivation<Record, Branch> left =
        config.derivation(
            "left", RECORD_TYPE, branchType, r -> new Branch("left"), d -> d.accepting(anything));
    Derivation<Record, Branch> right =
        config.derivation(
            "right", RECORD_TYPE, branchType, r -> new Branch("right"), d -> d.accepting(anything));
    Fold<Branch, Combined> combine =
        config.fold(
            "combine",
            branchType,
            combinedType,
            branches -> new Combined(branches.size() + " branches"),
            d -> d.accepting(anything));
    config.seal(storage);

    Surrogate<Record> root = records.conceal(new Record("root"));
    Surrogate<Branch> leftChild = left.derive(root).orThrow();
    Surrogate<Branch> rightChild = right.derive(root).orThrow();
    Surrogate<Combined> combined = combine.fold(java.util.List.of(leftChild, rightChild)).orThrow();

    int removed = config.erase(root);

    assertThat(removed).isEqualTo(4);
    assertThat(config.holds(combined)).isFalse();
    assertThat(storage.audit(AuditRecord.Operation.ERASE)).hasSize(4);
  }

  /** A policy is application code, and a policy that cannot decide has not said yes. */
  @Test
  @DisplayName("refuses when the erasure policy itself throws, rather than propagating the crash")
  void refuses_when_the_erasure_policy_throws() {
    MemoryStorage storage = new MemoryStorage();
    DefaultCharter config =
        new DefaultCharter(TENANT, LEVEL)
            .mayErase(
                (label, ctx) -> {
                  throw new IllegalStateException("cannot decide");
                });
    Conceal<Record> records =
        config.source(
            "records", RECORD_TYPE, ctx -> Label.of(TENANT, "acme").with(LEVEL, Level.LOW));
    config.seal(storage);

    Surrogate<Record> root = records.conceal(new Record("root"));

    assertThatThrownBy(() -> config.erase(root)).isInstanceOf(AccessDeniedException.class);
    assertThat(config.holds(root)).isTrue();
  }
}
