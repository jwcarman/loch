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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Exact;
import org.jwcarman.loch.lattice.Lattices;

/**
 * A derivation says how many values it reads, and the engine believes it before it reads any.
 *
 * <p>Handing a one-at-a-time derivation three handles is a mistake in the calling code, not a
 * decision about whether the access is allowed. The two must not arrive looking alike: a refusal is
 * something the caller is expected to handle, and quietly turning a bug into one buries it.
 */
@DisplayName("Arity")
class ArityTest {

  private static final DerivationId<String, String> ONE_AT_A_TIME = DerivationId.of("one");
  private static final DerivationId<String, String> A_FOLD = DerivationId.of("fold");

  private final AtomicInteger unaryRuns = new AtomicInteger();

  private final Loch<Exact<String>> loch =
      MemoryLoch.create(
          c ->
              c.lattice(Lattices.exact())
                  .withoutAudit()
                  .derivation(
                      Derivations.<Exact<String>, String, String>of(
                              ONE_AT_A_TIME,
                              String.class,
                              String.class,
                              value -> {
                                unaryRuns.incrementAndGet();
                                return value.toUpperCase();
                              })
                          .acceptingAnything()
                          .build())
                  .derivation(
                      Derivations.<Exact<String>, String, String>fromAll(
                              A_FOLD,
                              String.class,
                              String.class,
                              values -> String.join("+", values))
                          .acceptingAnything()
                          .build()));

  private final Handle<String> first = loch.hold("a", String.class, Exact.of("acme"));
  private final Handle<String> second = loch.hold("b", String.class, Exact.of("acme"));

  @Test
  @DisplayName("mismatched on a one-at-a-time derivation is a bug, and says so")
  void mismatched_is_a_bug_and_says_so() {
    List<Handle<String>> two = List.of(first, second);

    assertThatThrownBy(() -> loch.deriveAll(two, ONE_AT_A_TIME))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one value at a time");
  }

  /**
   * The half that matters. Discovering the arity inside the function would mean every parent had
   * already been decoded, and a function that blew up on the data would be indistinguishable from a
   * caller that passed the wrong number of handles.
   */
  @Test
  @DisplayName("is settled before any value is decoded")
  void is_settled_before_any_value_is_decoded() {
    List<Handle<String>> two = List.of(first, second);

    assertThatThrownBy(() -> loch.deriveAll(two, ONE_AT_A_TIME))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(unaryRuns).hasValue(0);
  }

  @Test
  @DisplayName("of one is fine for a fold, which is what folding over one value means")
  void one_is_fine_for_a_fold() {
    assertThat(loch.deriveAll(List.of(first), A_FOLD).succeeded()).isTrue();
  }

  @Test
  @DisplayName("of several is what a fold is for")
  void several_is_what_a_fold_is_for() {
    assertThat(loch.deriveAll(List.of(first, second), A_FOLD).succeeded()).isTrue();
  }

  @Test
  @DisplayName("of none is a refusal, not a bug: an empty list is data, not a mistake")
  void none_is_a_refusal() {
    assertThat(loch.<String, String>deriveAll(List.of(), A_FOLD).made()).isEmpty();
  }
}
