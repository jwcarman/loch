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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("A revealed outcome")
class RevealedTest {

  @Nested
  @DisplayName("that was allowed")
  class ThatWasAllowed {

    private final Revealed<String> revealed = new Revealed.Allowed<>("4111-1111-1111-1111");

    @Test
    @DisplayName("was allowed")
    void was_allowed() {
      assertThat(revealed.allowed()).isTrue();
    }

    @Test
    @DisplayName("hands back the value")
    void hands_back_the_value() {
      assertThat(revealed.granted()).contains("4111-1111-1111-1111");
    }

    @Test
    @DisplayName("hands back the value rather than throwing")
    void hands_back_the_value_rather_than_throwing() {
      assertThat(revealed.orThrow()).isEqualTo("4111-1111-1111-1111");
    }

    /**
     * The one thing this arm promises about itself: the value it is carrying never appears by
     * accident, because a record's generated {@code toString} would print it straight into a log
     * line.
     */
    @Test
    @DisplayName("does not print the value it is carrying")
    void does_not_print_the_value_it_is_carrying() {
      assertThat(revealed.toString())
          .isEqualTo("Allowed[value=<held>]")
          .doesNotContain("4111-1111-1111-1111");
    }
  }

  @Nested
  @DisplayName("that was denied")
  class ThatWasDenied {

    private final Revealed<String> revealed =
        new Revealed.Denied<>(Revealed.Reason.ABOVE_CEILING, "clearance too low");

    @Test
    @DisplayName("was not allowed")
    void was_not_allowed() {
      assertThat(revealed.allowed()).isFalse();
    }

    @Test
    @DisplayName("has no value to hand back")
    void has_no_value_to_hand_back() {
      assertThat(revealed.granted()).isEmpty();
    }

    @Test
    @DisplayName("throws an exception naming the reason and detail rather than a value")
    void throws_naming_the_reason_and_detail() {
      assertThatThrownBy(revealed::orThrow)
          .isInstanceOf(AccessDeniedException.class)
          .hasMessageContaining("ABOVE_CEILING")
          .hasMessageContaining("clearance too low");
    }
  }
}
