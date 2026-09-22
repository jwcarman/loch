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

@DisplayName("A derivation outcome")
class DerivedTest {

  @Nested
  @DisplayName("that made a value")
  class ThatMadeAValue {

    private final Surrogate<String> value = Surrogate.of("card-123");
    private final Derived<String> derived = new Derived.Made<>(value);

    @Test
    @DisplayName("succeeded")
    void succeeded() {
      assertThat(derived.succeeded()).isTrue();
    }

    @Test
    @DisplayName("hands back the new handle")
    void hands_back_the_new_handle() {
      assertThat(derived.made()).contains(value);
    }

    @Test
    @DisplayName("hands back the new handle rather than throwing")
    void hands_back_the_new_handle_rather_than_throwing() {
      assertThat(derived.orThrow()).isEqualTo(value);
    }
  }

  @Nested
  @DisplayName("that was refused")
  class ThatWasRefused {

    private final Derived<String> derived =
        new Derived.Refused<>(Derived.Reason.NO_SUCH_VALUE, "no such parent");

    @Test
    @DisplayName("did not succeed")
    void did_not_succeed() {
      assertThat(derived.succeeded()).isFalse();
    }

    @Test
    @DisplayName("has no handle to hand back")
    void has_no_handle_to_hand_back() {
      assertThat(derived.made()).isEmpty();
    }

    @Test
    @DisplayName("throws an exception naming the reason and detail rather than a value")
    void throws_naming_the_reason_and_detail() {
      assertThatThrownBy(derived::orThrow)
          .isInstanceOf(DerivationRefusedException.class)
          .isInstanceOf(AccessDeniedException.class)
          .hasMessageContaining("NO_SUCH_VALUE")
          .hasMessageContaining("no such parent");
    }
  }
}
