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

/** Every way a question can fail to be answered, beyond a ceiling that could not decide. */
@DisplayName("Asking a question")
class QueryEdgeCasesTest {

  private static final SurrogateType<String> STRING_TYPE = SurrogateType.of(String.class);
  private static final SurrogateType<Integer> INT_TYPE = SurrogateType.of(Integer.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");

  private final DefaultCharter charter = new DefaultCharter(TENANT);

  private final Conceal<String> textSource =
      charter.source("text", STRING_TYPE, Label.of(TENANT, "acme"));

  private final Conceal<Integer> intSource =
      charter.source("numbers", INT_TYPE, Label.of(TENANT, "acme"));

  private final Query<String, String> notOfferedHere =
      charter.query(
          "not-offered-here",
          STRING_TYPE,
          String.class,
          (v, q, ctx) -> v.contains(q),
          d -> d.accepting(Ceiling.of(TENANT, Constraint.any())).availableTo(ctx -> false));

  private final Query<String, String> onlyForText =
      charter.query(
          "text.mentions",
          STRING_TYPE,
          String.class,
          (v, q, ctx) -> v.contains(q),
          d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

  private final Query<String, String> asksAndFails =
      charter.query(
          "always-fails",
          STRING_TYPE,
          String.class,
          (v, q, ctx) -> {
            throw new IllegalStateException("cannot read this");
          },
          d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

  {
    charter.seal(new MemoryStorage());
  }

  @Test
  @DisplayName("refuses a question that was not made available here")
  void refuses_a_question_not_available_here() {
    Surrogate<String> held = textSource.conceal("hello");

    Answer answer = notOfferedHere.ask(held, "hello");

    assertThat(answer)
        .isInstanceOfSatisfying(
            Answer.Refused.class,
            r -> assertThat(r.reason()).isEqualTo(Answer.Reason.NOT_AVAILABLE_HERE));
  }

  @Test
  @DisplayName("refuses a question about a value nobody is holding")
  void refuses_a_question_about_a_value_nobody_is_holding() {
    Answer answer = onlyForText.ask(Surrogate.of("sur_never-minted"), "hello");

    assertThat(answer)
        .isInstanceOfSatisfying(
            Answer.Refused.class,
            r -> assertThat(r.reason()).isEqualTo(Answer.Reason.NO_SUCH_VALUE));
  }

  @Test
  @DisplayName("refuses a question asked of a value of the wrong type")
  void refuses_a_question_asked_of_the_wrong_type() {
    Surrogate<Integer> heldAsInt = intSource.conceal(42);
    Surrogate<String> lying = new Surrogate<>(heldAsInt.id());

    Answer answer = onlyForText.ask(lying, "hello");

    assertThat(answer)
        .isInstanceOfSatisfying(
            Answer.Refused.class, r -> assertThat(r.reason()).isEqualTo(Answer.Reason.WRONG_TYPE));
  }

  @Test
  @DisplayName("refuses a question whose own logic failed, after already reading the value")
  void refuses_a_question_whose_own_logic_failed() {
    Surrogate<String> held = textSource.conceal("hello");

    Answer answer = asksAndFails.ask(held, "hello");

    assertThat(answer)
        .isInstanceOfSatisfying(
            Answer.Refused.class,
            r -> assertThat(r.reason()).isEqualTo(Answer.Reason.NOT_AVAILABLE_HERE));
  }
}
