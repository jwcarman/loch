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

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Constraint;

/**
 * What a query or a derivation says it may read, when nobody said anything at all.
 *
 * <p>Both of these specs are only ever built by {@link DefaultCharter}, which already refuses to
 * declare either one without a ceiling -- so through the portal, {@code ceiling} is never null. The
 * two are built directly here to check the fallback itself, the same way a getter is tested without
 * needing a caller who happens to have left a field unset.
 */
@DisplayName("A spec with no ceiling of its own")
class SpecDefaultsTest {

  private static final SurrogateType<String> STRING_TYPE = SurrogateType.of(String.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");

  @Test
  @DisplayName("a query says it did not decide, rather than throwing")
  void a_query_says_it_did_not_decide() {
    QuerySpec<String, String> spec =
        new QuerySpec<>("unconfigured", STRING_TYPE, (v, q, ctx) -> true, null, ctx -> true);

    assertThat(spec.ceilingFor(AccessContext.empty())).isNull();
  }

  @Test
  @DisplayName("a derivation says it did not decide, rather than throwing")
  void a_derivation_says_it_did_not_decide() {
    DerivationSpec<String> spec =
        new DerivationSpec<>(
            "unconfigured",
            List.of(STRING_TYPE),
            STRING_TYPE,
            (values, ctx) -> Optional.of(values.getFirst().toString()),
            null,
            null,
            ctx -> true,
            false);

    assertThat(spec.ceilingFor(AccessContext.empty())).isNull();
  }

  /** The constant-ceiling overload every other test reaches through a lambda instead. */
  @Test
  @DisplayName("a query may accept a ceiling that does not depend on who is asking")
  void a_query_may_accept_a_constant_ceiling() {
    Ceiling ceiling = Ceiling.of(TENANT, Constraint.any());
    DefaultCharter charter = new DefaultCharter(TENANT);
    Conceal<String> source =
        charter.source(
            "mail", STRING_TYPE, ctx -> org.jwcarman.loch.lattice.Label.of(TENANT, "acme"));
    Query<String, String> asksSomething =
        charter.query(
            "mentions",
            STRING_TYPE,
            String.class,
            (v, q, ctx) -> v.contains(q),
            d -> d.accepting(ceiling));
    charter.seal(new MemoryStorage());

    Surrogate<String> held = source.conceal("hello world");

    assertThat(asksSomething.ask(held, "world").isTrue()).isTrue();
  }
}
