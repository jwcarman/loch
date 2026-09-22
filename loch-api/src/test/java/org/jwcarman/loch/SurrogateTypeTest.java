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

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.TypeRef;

@DisplayName("A surrogate type")
class SurrogateTypeTest {

  private record DisputeClaim() {}

  private record Last4() {}

  private static class Domain {
    private record Invoice() {}
  }

  @SurrogateName("billing.invoice/v1")
  private record VersionedInvoice() {}

  @Test
  @DisplayName("carries the name it was given explicitly")
  void carries_the_name_it_was_given() {
    SurrogateType<String> type = SurrogateType.of("note", String.class);

    assertThat(type.name()).isEqualTo("note");
    assertThat(type.type()).isEqualTo(TypeRef.of(String.class));
  }

  @Test
  @DisplayName("carries a generic container's TypeRef unchanged")
  void carries_a_generic_containers_type_ref() {
    TypeRef<List<String>> listOfStrings = TypeRef.listOf(TypeRef.of(String.class));

    SurrogateType<List<String>> type = SurrogateType.of("notes", listOfStrings);

    assertThat(type.name()).isEqualTo("notes");
    assertThat(type.type()).isEqualTo(listOfStrings);
  }

  @Test
  @DisplayName("names itself after its @SurrogateName when the type carries one")
  void names_itself_after_its_annotation() {
    SurrogateType<VersionedInvoice> type = SurrogateType.of(VersionedInvoice.class);

    assertThat(type.name()).isEqualTo("billing.invoice/v1");
  }

  @Test
  @DisplayName("kebab-cases its simple name when the type carries no @SurrogateName")
  void kebab_cases_its_simple_name_by_default() {
    assertThat(SurrogateType.of(DisputeClaim.class).name()).isEqualTo("dispute-claim");
  }

  @Test
  @DisplayName("splits at the end of a run of digits like any other word boundary")
  void splits_at_the_end_of_a_digit_run() {
    assertThat(SurrogateType.of(Last4.class).name()).isEqualTo("last4");
  }

  @Test
  @DisplayName("drops the enclosing type from a nested class's name")
  void drops_the_enclosing_type() {
    assertThat(SurrogateType.of(Domain.Invoice.class).name()).isEqualTo("invoice");
  }

  @Test
  @DisplayName("refuses a null name")
  void refuses_a_null_name() {
    TypeRef<String> type = TypeRef.of(String.class);

    assertThatThrownBy(() -> new SurrogateType<>(null, type))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("refuses a blank name")
  void refuses_a_blank_name() {
    TypeRef<String> type = TypeRef.of(String.class);

    assertThatThrownBy(() -> new SurrogateType<>("  ", type))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("refuses a null type")
  void refuses_a_null_type() {
    assertThatThrownBy(() -> new SurrogateType<String>("note", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("refuses to name itself after a null class")
  void refuses_to_name_itself_after_a_null_class() {
    assertThatThrownBy(() -> SurrogateType.of((Class<Object>) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("prints as its name and nothing else")
  void prints_as_its_name() {
    assertThat(SurrogateType.of("note", String.class).toString()).isEqualTo("note");
  }
}
