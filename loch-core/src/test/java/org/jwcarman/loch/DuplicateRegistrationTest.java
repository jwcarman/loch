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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Constraint;

/**
 * Two declarations that cannot both be registered under the same name, found the moment the charter
 * is sealed rather than the first time a reader picks the wrong one.
 *
 * <p>Destinations are named because a {@link Reveal} is reached by holding it, not by naming it
 * again; but the {@code DestinationSpec} form and the two-arg {@code destination(...)} form both
 * feed the same map, and nothing earlier stops two declarations claiming one name.
 */
@DisplayName("Two declarations claiming the same name")
class DuplicateRegistrationTest {

  private static final SurrogateType<String> STRING_TYPE = SurrogateType.of(String.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");

  @Test
  @DisplayName("two destinations registered as the same name are refused at sealing")
  void two_destinations_with_the_same_name_are_refused_at_sealing() {
    DefaultCharter charter = new DefaultCharter(TENANT);
    charter.destination("outbox", ctx -> Ceiling.nothing(), STRING_TYPE);
    charter.destination(Destinations.fixed("outbox", Ceiling.of(TENANT, Constraint.any())));

    assertThatThrownBy(() -> charter.seal(new MemoryStorage()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outbox");
  }

  @Test
  @DisplayName("two derivations registered as the same name are refused at sealing")
  void two_derivations_with_the_same_name_are_refused_at_sealing() {
    DefaultCharter charter = new DefaultCharter(TENANT);
    charter.derivation(
        "upper",
        STRING_TYPE,
        STRING_TYPE,
        String::toUpperCase,
        d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));
    charter.derivation(
        "upper",
        STRING_TYPE,
        STRING_TYPE,
        String::toLowerCase,
        d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

    assertThatThrownBy(() -> charter.seal(new MemoryStorage()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("upper");
  }
}
