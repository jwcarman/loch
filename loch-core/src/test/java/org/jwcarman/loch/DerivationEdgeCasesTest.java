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
 * Every way deriving can be refused because of a parent, distinct from a ceiling that could not
 * decide: a parent that is not here, one labelled above what this derivation may read, and one of
 * the wrong type.
 */
@DisplayName("Deriving from a parent")
class DerivationEdgeCasesTest {

  private static final SurrogateType<String> STRING_TYPE = SurrogateType.of(String.class);
  private static final SurrogateType<Integer> INT_TYPE = SurrogateType.of(Integer.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");

  private final DefaultCharter charter = new DefaultCharter(TENANT);

  private final Conceal<String> globexMail =
      charter.source("globex-mail", STRING_TYPE, Label.of(TENANT, "globex"));

  private final Conceal<Integer> numbers =
      charter.source("numbers", INT_TYPE, Label.of(TENANT, "acme"));

  private final Derivation<String, String> onlyAcme =
      charter.derivation(
          "upper",
          STRING_TYPE,
          STRING_TYPE,
          String::toUpperCase,
          d -> d.accepting(Ceiling.of(TENANT, Constraint.atMost("acme"))));

  {
    charter.seal(new MemoryStorage());
  }

  @Test
  @DisplayName("refuses a parent nobody is holding")
  void refuses_a_parent_nobody_is_holding() {
    Derived<String> result = onlyAcme.derive(Surrogate.of("sur_never-minted"));

    assertThat(result)
        .isInstanceOfSatisfying(
            Derived.Refused.class,
            r -> assertThat(r.reason()).isEqualTo(Derived.Reason.NO_SUCH_VALUE));
  }

  @Test
  @DisplayName("refuses a parent labelled above what this derivation may read")
  void refuses_a_parent_above_the_ceiling() {
    Surrogate<String> globexValue = globexMail.conceal("hello from globex");

    Derived<String> result = onlyAcme.derive(globexValue);

    assertThat(result)
        .isInstanceOfSatisfying(
            Derived.Refused.class,
            r -> assertThat(r.reason()).isEqualTo(Derived.Reason.ABOVE_CEILING));
  }

  @Test
  @DisplayName("refuses a parent of the wrong type")
  void refuses_a_parent_of_the_wrong_type() {
    Surrogate<Integer> heldAsInt = numbers.conceal(42);
    Surrogate<String> lying = new Surrogate<>(heldAsInt.id());

    Derived<String> result = onlyAcme.derive(lying);

    assertThat(result)
        .isInstanceOfSatisfying(
            Derived.Refused.class,
            r -> assertThat(r.reason()).isEqualTo(Derived.Reason.WRONG_TYPE));
  }
}
