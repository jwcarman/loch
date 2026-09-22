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
package org.jwcarman.loch.lattice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The schema a label is checked against, and a value in its own right.
 *
 * <p>Its two refusals are the ones that keep a stored label meaningful: nothing asked about at all,
 * and two axes racing for the same name. Either would make a decode ambiguous about what it is
 * reading.
 */
@DisplayName("Axes")
class AxesTest {

  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axis<String> PROJECT = Axis.matching("project");

  @Test
  @DisplayName("refuses to be built with no axes at all")
  void refuses_to_be_built_with_no_axes() {
    assertThatThrownBy(Axes::of)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one axis");
  }

  /**
   * Two axes racing for one name would make a decode ambiguous about which of them it is reading.
   */
  @Test
  @DisplayName("refuses two axes that both want the same name")
  void refuses_two_axes_with_the_same_name() {
    Axis<String> impostor = Axis.matching("tenant");

    assertThatThrownBy(() -> Axes.of(TENANT, impostor))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenant");
  }

  @Test
  @DisplayName("counts how many questions it asks")
  void counts_how_many_questions_it_asks() {
    assertThat(Axes.of(TENANT, PROJECT).size()).isEqualTo(2);
  }

  @Test
  @DisplayName("renders every axis it holds, in order")
  void renders_every_axis_in_order() {
    assertThat(Axes.of(TENANT, PROJECT)).hasToString("[tenant, project]");
  }

  @Test
  @DisplayName("is equal to another built from the same axes")
  void is_equal_to_another_built_from_the_same_axes() {
    Axes oneWay = Axes.of(TENANT, PROJECT);
    Axes theOther = Axes.of(TENANT, PROJECT);

    assertThat(oneWay).isEqualTo(theOther).hasSameHashCodeAs(theOther);
  }

  @Test
  @DisplayName("is not equal to one built from different axes, or to something else entirely")
  void is_not_equal_to_a_different_axes_or_to_something_else() {
    Axes axes = Axes.of(TENANT);

    assertThat(axes).isNotEqualTo(Axes.of(PROJECT)).isNotEqualTo("tenant");
  }
}
