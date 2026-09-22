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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("A surrogate")
class SurrogateTest {

  @Test
  @DisplayName("is a typed view of the id it was given")
  void is_a_typed_view_of_the_id() {
    Surrogate<String> surrogate = Surrogate.of("abc-123");

    assertThat(surrogate.id()).isEqualTo("abc-123");
  }

  /**
   * The one thing this class promises about itself: nothing about the value it stands in for shows
   * up, because a surrogate travels into logs and prompts and payloads.
   */
  @Test
  @DisplayName("prints as the identifier and nothing else")
  void prints_as_the_identifier_and_nothing_else() {
    Surrogate<String> surrogate = Surrogate.of("abc-123");

    assertThat(surrogate).hasToString("abc-123");
  }

  @Test
  @DisplayName("refuses a null id")
  void refuses_a_null_id() {
    assertThatThrownBy(() -> new Surrogate<String>(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("refuses a blank id")
  void refuses_a_blank_id() {
    assertThatThrownBy(() -> new Surrogate<String>("   "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
