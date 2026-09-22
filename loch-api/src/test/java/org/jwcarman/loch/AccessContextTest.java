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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("An access context")
class AccessContextTest {

  @Test
  @DisplayName("holds nobody in particular when empty")
  void holds_nobody_in_particular_when_empty() {
    assertThat(AccessContext.empty().attributes()).isEmpty();
  }

  @Test
  @DisplayName("holds the one attribute it was built from")
  void holds_the_one_attribute_it_was_built_from() {
    AccessContext context = AccessContext.of("tenant", "acme");

    assertThat(context.get("tenant")).contains("acme");
  }

  @Test
  @DisplayName("holds whatever map it was built from")
  void holds_whatever_map_it_was_built_from() {
    AccessContext context = AccessContext.of(Map.of("tenant", "acme", "clearance", "cardholder"));

    assertThat(context.get("tenant")).contains("acme");
    assertThat(context.get("clearance")).contains("cardholder");
  }

  @Test
  @DisplayName("answers empty for an attribute it was never given")
  void answers_empty_for_an_unset_attribute() {
    AccessContext context = AccessContext.empty();

    assertThat(context.get("tenant")).isEqualTo(Optional.empty());
  }

  @Test
  @DisplayName("says an attribute has a value only when it matches exactly")
  void says_an_attribute_has_a_value_only_when_it_matches() {
    AccessContext context = AccessContext.of("tenant", "acme");

    assertThat(context.has("tenant", "acme")).isTrue();
    assertThat(context.has("tenant", "globex")).isFalse();
  }

  @Test
  @DisplayName("says an unset attribute has no value, whatever is asked")
  void says_an_unset_attribute_has_no_value() {
    AccessContext context = AccessContext.empty();

    assertThat(context.has("tenant", "acme")).isFalse();
  }

  /**
   * The map handed to {@code of} is not held by reference -- a caller mutating its own map
   * afterward must not reach back into an already-built context.
   */
  @Test
  @DisplayName("copies the map it is built from, rather than holding it by reference")
  void copies_the_map_it_is_built_from() {
    Map<String, String> mutable = new HashMap<>();
    mutable.put("tenant", "acme");

    AccessContext context = AccessContext.of(mutable);
    mutable.put("tenant", "globex");
    mutable.put("clearance", "cardholder");

    assertThat(context.get("tenant")).contains("acme");
    assertThat(context.get("clearance")).isEqualTo(Optional.empty());
  }
}
