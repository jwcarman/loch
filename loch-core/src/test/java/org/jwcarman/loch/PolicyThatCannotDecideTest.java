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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Exact;
import org.jwcarman.loch.lattice.Lattices;

/**
 * Every gate in this library is application code, and application code throws.
 *
 * <p>A ceiling reads a tenant id out of a context that turns out to be empty; a lowering rule
 * consults a table that is not there yet. What matters is what the loch does about it, because the
 * two available behaviours are not equally safe: a policy that cannot be evaluated has not said
 * yes, and an exception that escapes leaves the operation with no audit line at all.
 *
 * <p>The last case is the sharp one. A derivation receives plaintext in order to compute. If it
 * fails <i>after</i> reading it, the read happened, and the record has to say so.
 */
@DisplayName("A policy that cannot decide")
class PolicyThatCannotDecideTest {

  private static final DestinationId ANYWHERE = DestinationId.of("anywhere");
  private static final QuestionId<String, String> CEILING_THROWS =
      QuestionId.of("question-whose-ceiling-throws");
  private static final QuestionId<String, String> GATE_THROWS =
      QuestionId.of("question-whose-gate-throws");
  private static final DerivationId DERIVATION_CEILING_THROWS =
      DerivationId.of("derivation-whose-ceiling-throws");
  private static final DerivationId LOWERING_THROWS =
      DerivationId.of("derivation-whose-lowering-throws");
  private static final DerivationId FUNCTION_THROWS =
      DerivationId.of("derivation-that-fails-midway");

  private final List<AuditRecord> audit = new ArrayList<>();

  private final LochConfig<Exact<String>> config =
      new LochConfig<Exact<String>>()
          .lattice(Lattices.exact())
          .auditor(audit::add)
          .destination(Destinations.varying(ANYWHERE, ctx -> boom()))
          .question(
              Question.<Exact<String>, String, String>of(
                      CEILING_THROWS, String.class, String::equals)
                  .accepting(ctx -> boom())
                  .build())
          .question(
              Question.<Exact<String>, String, String>of(GATE_THROWS, String.class, String::equals)
                  .acceptingAnything()
                  .availableTo(ctx -> boom())
                  .build());

  private final Derivation<String, String> ceilingThrows =
      config
          .derivation(DERIVATION_CEILING_THROWS, String.class, String.class, String::toUpperCase)
          .accepting(ctx -> boom())
          .mint();

  private final Derivation<String, String> loweringThrows =
      config
          .derivation(LOWERING_THROWS, String.class, String.class, String::toUpperCase)
          .acceptingAnything()
          .lowering(joined -> boom())
          .mint();

  private final Derivation<String, String> functionThrows =
      config
          .derivation(FUNCTION_THROWS, String.class, String.class, value -> boom())
          .acceptingAnything()
          .mint();

  private final Loch<Exact<String>> loch = MemoryLoch.create(config);

  private final Handle<String> held = loch.hold("secret", String.class, Exact.of("acme"));

  /** Whatever a real one would be: the point is only that it is unchecked and unhandled. */
  private static <T> T boom() {
    throw new IllegalStateException("the policy blew up");
  }

  @Test
  @DisplayName("is not a destination that accepts the value")
  void is_not_a_destination_that_accepts_the_value() {
    Dereferenced<String> result = loch.dereference(held, ANYWHERE, AccessContext.empty());

    assertThat(result.allowed()).isFalse();
    assertThat(result).isInstanceOf(Dereferenced.Denied.class);
  }

  @Test
  @DisplayName("is not a question that may read the value")
  void is_not_a_question_that_may_read_the_value() {
    Answer result = loch.ask(held, CEILING_THROWS, "secret", AccessContext.empty());

    assertThat(result).isInstanceOf(Answer.Refused.class);
    assertThat(((Answer.Refused) result).reason()).isEqualTo(Answer.Reason.ABOVE_CEILING);
  }

  @Test
  @DisplayName("is not a question that is offered here")
  void is_not_a_question_that_is_offered_here() {
    Answer result = loch.ask(held, GATE_THROWS, "secret", AccessContext.empty());

    assertThat(result).isInstanceOf(Answer.Refused.class);
    assertThat(((Answer.Refused) result).reason()).isEqualTo(Answer.Reason.NOT_AVAILABLE_HERE);
  }

  @Test
  @DisplayName("is not a derivation that may read the value")
  void is_not_a_derivation_that_may_read_the_value() {
    Derived<String> result = ceilingThrows.derive(held, AccessContext.empty());

    assertThat(result.made()).isEmpty();
    assertThat(((Derived.Refused<String>) result).reason()).isEqualTo(Derived.Reason.ABOVE_CEILING);
  }

  @Test
  @DisplayName("is not a lowering that lowered anything")
  void is_not_a_lowering_that_lowered_anything() {
    Derived<String> result = loweringThrows.derive(held, AccessContext.empty());

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
    audit.clear();

    Derived<String> result = functionThrows.derive(held, AccessContext.empty());

    assertThat(result.made()).isEmpty();
    assertThat(((Derived.Refused<String>) result).reason()).isEqualTo(Derived.Reason.DECLINED);
    assertThat(audit).isNotEmpty();
    assertThat(audit)
        .allSatisfy(record -> assertThat(record.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED));
  }

  /** Nothing above reached the caller as a stack trace, and every one of them was recorded. */
  @Test
  @DisplayName("is recorded as a refusal, never raised as an exception")
  void is_recorded_as_a_refusal() {
    audit.clear();

    loch.dereference(held, ANYWHERE, AccessContext.empty());
    loch.ask(held, CEILING_THROWS, "secret", AccessContext.empty());
    loch.ask(held, GATE_THROWS, "secret", AccessContext.empty());
    ceilingThrows.derive(held, AccessContext.empty());
    loweringThrows.derive(held, AccessContext.empty());

    assertThat(audit).hasSize(5);
    assertThat(audit)
        .allSatisfy(record -> assertThat(record.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED));
  }
}
