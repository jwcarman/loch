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
import org.jwcarman.loch.lattice.Label;

/**
 * A source is a door, and a door can misbehave two ways: handed nothing to label, or unable to say
 * what it labels.
 */
@DisplayName("Concealing through a source")
class ConcealEdgeCasesTest {

  private static final SurrogateType<String> STRING_TYPE = SurrogateType.of(String.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");

  private final DefaultCharter charter = new DefaultCharter(TENANT);

  @Test
  @DisplayName("refuses a null value: a store holds values, not nulls")
  void refuses_a_null_value() {
    Conceal<String> source = charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme"));
    charter.seal(new MemoryStorage());

    assertThatThrownBy(() -> source.conceal(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not nulls");
  }

  @Test
  @DisplayName("refuses a value whose labelling function threw, rather than propagating the crash")
  void refuses_a_value_whose_labelling_function_threw() {
    Conceal<String> source =
        charter.source(
            "mail",
            STRING_TYPE,
            (value, ctx) -> {
              throw new IllegalStateException("cannot decide");
            });
    charter.seal(new MemoryStorage());

    assertThatThrownBy(() -> source.conceal("hello"))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("SOURCE_CANNOT_LABEL");
  }

  @Test
  @DisplayName("refuses a value whose labelling function answered with no label at all")
  void refuses_a_value_whose_labelling_function_answered_with_nothing() {
    Conceal<String> source = charter.source("mail", STRING_TYPE, (value, ctx) -> null);
    charter.seal(new MemoryStorage());

    assertThatThrownBy(() -> source.conceal("hello"))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("SOURCE_CANNOT_LABEL");
  }
}
