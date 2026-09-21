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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Constraint;
import org.jwcarman.loch.lattice.Label;

/**
 * Every gate in this library is application code, and application code throws.
 *
 * <p>A ceiling reads a tenant id out of a context that turns out to be empty; a lowering rule
 * consults a table that is not there yet. What matters is what the store does about it, because the
 * two available behaviours are not equally safe: a policy that cannot be evaluated has not said
 * yes, and an exception that escapes leaves the operation with no audit line at all.
 *
 * <p>The last case is the sharp one. A derivation receives plaintext in order to compute. If it
 * fails <i>after</i> reading it, the read happened, and the record has to say so.
 */
@DisplayName("A policy that cannot decide")
class PolicyThatCannotDecideTest {

  private static final SurrogateType<String> STRING_TYPE = SurrogateType.of(String.class);

  private static final Axis<String> TENANT = Axis.matching("tenant");

  private final MemoryStorage storage = new MemoryStorage();

  private final Charter config = new Charter(TENANT);

  private final Conceal<String> source =
      config.source("source", STRING_TYPE, ctx -> Label.of(TENANT, "acme"));

  private final Reveal<String> sinkWhoseCeilingThrows =
      config.destination("anywhere", ctx -> boom(), STRING_TYPE).reading(STRING_TYPE);

  private final Query<String, String> queryWhoseCeilingThrows =
      config
          .query("query-ceiling", STRING_TYPE, String.class, (v, q, ctx) -> v.equals(q))
          .accepting(ctx -> boom())
          .mint();

  private final Query<String, String> queryWhoseGateThrows =
      config
          .query("query-gate", STRING_TYPE, String.class, (v, q, ctx) -> v.equals(q))
          .accepting(ctx -> Ceiling.of(TENANT, Constraint.any()))
          .availableTo(ctx -> boom())
          .mint();

  private final Derivation<String, String> derivationWhoseCeilingThrows =
      config
          .derivation("derivation-ceiling", STRING_TYPE, STRING_TYPE, String::toUpperCase)
          .accepting(ctx -> boom())
          .mint();

  private final Derivation<String, String> loweringThrows =
      config
          .derivation("lowering", STRING_TYPE, STRING_TYPE, String::toUpperCase)
          .accepting(ctx -> Ceiling.of(TENANT, Constraint.any()))
          .lowering(joined -> boom())
          .mint();

  private final Derivation<String, String> functionThrows =
      config
          .derivation("function", STRING_TYPE, STRING_TYPE, value -> boom())
          .accepting(ctx -> Ceiling.of(TENANT, Constraint.any()))
          .mint();

  {
    config.seal(storage);
  }

  private final Surrogate<String> held = source.conceal("secret");

  /** Whatever a real one would be: the point is only that it is unchecked and unhandled. */
  private static <T> T boom() {
    throw new IllegalStateException("the policy blew up");
  }

  @Test
  @DisplayName("is not a sink that accepts the value")
  void is_not_a_sink_that_accepts_the_value() {
    assertThat(sinkWhoseCeilingThrows.reveal(held).allowed()).isFalse();
  }

  @Test
  @DisplayName("is not a query that may read the value")
  void is_not_a_query_that_may_read_the_value() {
    Answer result = queryWhoseCeilingThrows.ask(held, "secret");

    assertThat(result).isInstanceOf(Answer.Refused.class);
    assertThat(((Answer.Refused) result).reason()).isEqualTo(Answer.Reason.ABOVE_CEILING);
  }

  @Test
  @DisplayName("is not a query that is offered here")
  void is_not_a_query_that_is_offered_here() {
    Answer result = queryWhoseGateThrows.ask(held, "secret");

    assertThat(result).isInstanceOf(Answer.Refused.class);
    assertThat(((Answer.Refused) result).reason()).isEqualTo(Answer.Reason.NOT_AVAILABLE_HERE);
  }

  @Test
  @DisplayName("is not a derivation that may read the value")
  void is_not_a_derivation_that_may_read_the_value() {
    Derived<String> result = derivationWhoseCeilingThrows.derive(held);

    assertThat(result.made()).isEmpty();
    assertThat(((Derived.Refused<String>) result).reason()).isEqualTo(Derived.Reason.ABOVE_CEILING);
  }

  @Test
  @DisplayName("is not a lowering that lowered anything")
  void is_not_a_lowering_that_lowered_anything() {
    Derived<String> result = loweringThrows.derive(held);

    assertThat(result.made()).isEmpty();
    assertThat(((Derived.Refused<String>) result).reason())
        .isEqualTo(Derived.Reason.NOT_A_LOWERING);
  }

  /**
   * The read already happened. A crash must not be quieter than a decline.
   *
   * <p>If this escaped as an exception instead, a function that had just been handed cardholder
   * data would leave nothing behind in the record at all, and the way to get there is to feed it
   * input it mishandles.
   */
  @Test
  @DisplayName("that fails after reading the value still leaves a line in the record")
  void that_fails_after_reading_still_leaves_a_line() {
    storage.clearAudit();

    Derived<String> result = functionThrows.derive(held);

    assertThat(result.made()).isEmpty();
    assertThat(((Derived.Refused<String>) result).reason()).isEqualTo(Derived.Reason.DECLINED);
    assertThat(storage.audit()).isNotEmpty();
    assertThat(storage.audit())
        .allSatisfy(record -> assertThat(record.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED));
  }

  /** Nothing above reached the caller as a stack trace, and every one of them was recorded. */
  @Test
  @DisplayName("is recorded as a refusal, never raised as an exception")
  void is_recorded_as_a_refusal() {
    storage.clearAudit();

    sinkWhoseCeilingThrows.reveal(held);
    queryWhoseCeilingThrows.ask(held, "secret");
    queryWhoseGateThrows.ask(held, "secret");
    derivationWhoseCeilingThrows.derive(held);
    loweringThrows.derive(held);

    assertThat(storage.audit()).hasSize(5);
    assertThat(storage.audit())
        .allSatisfy(record -> assertThat(record.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED));
  }
}
