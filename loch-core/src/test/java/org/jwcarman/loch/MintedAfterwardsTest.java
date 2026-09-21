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
import org.jwcarman.loch.lattice.Exact;
import org.jwcarman.loch.lattice.Lattices;

/**
 * A capability minted after its loch was built is attached to nothing.
 *
 * <p>This is the property that makes holding a capability mean anything. Without it the whole
 * arrangement is theatre, because the configuration is the mint: anyone still holding it could
 * manufacture an inlet at any label or a derivation reading anything, long after startup decided
 * what the application was allowed to do.
 *
 * <p>All three were measured doing exactly that before capabilities were bound individually. An
 * inlet minted after startup planted a value at another tenant's label; a derivation minted after
 * startup read a cardholder token. The outlet failed, but only by accident -- the engine happened
 * to have snapshotted its destinations -- and an accident is not a control.
 *
 * <p>There is no policy here to misconfigure and no check to switch off. A capability reaches its
 * loch through a binding attached when that loch is built, so one minted afterwards has nothing to
 * reach.
 */
@DisplayName("A capability minted after the loch was built")
class MintedAfterwardsTest {

  private final LochConfig<Exact<String>> config =
      new LochConfig<Exact<String>>().lattice(Lattices.exact()).withoutAudit();

  private final Inlet<String> acmeMail =
      config.inlet(InletId.of("acme-mail"), String.class, ctx -> Exact.of("acme"));

  private final Loch<Exact<String>> loch = MemoryLoch.create(config);

  private final Handle<String> secret = acmeMail.hold("acme's cardholder token");

  @Test
  @DisplayName("proves the loch itself still works, so the refusals below mean something")
  void the_loch_itself_still_works() {
    assertThat(loch.label(secret.id())).isEqualTo(Exact.of("acme"));
  }

  @Test
  @DisplayName("cannot be an inlet planting a value at somebody else's label")
  void cannot_be_an_inlet() {
    Inlet<String> forged =
        config.inlet(InletId.of("forged"), String.class, ctx -> Exact.of("globex"));

    assertThatThrownBy(() -> forged.hold("globex owes us 1,000,000"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("attached to no loch");

    assertThat(loch.manifest().toString()).doesNotContain("forged");
  }

  @Test
  @DisplayName("cannot be a derivation reading what it was never entitled to")
  void cannot_be_a_derivation() {
    Derivation<String, String> forged =
        config
            .derivation(DerivationId.of("forged"), String.class, String.class, String::toUpperCase)
            .acceptingAnything()
            .mint();

    assertThatThrownBy(() -> forged.derive(secret))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("attached to no loch");
  }

  @Test
  @DisplayName("cannot be an outlet with a ceiling of its own choosing")
  void cannot_be_an_outlet() {
    Outlet<String> forged =
        config.outlet(DestinationId.of("forged"), String.class, ctx -> Exact.conflict());

    assertThatThrownBy(() -> forged.read(secret))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("attached to no loch");
  }

  @Test
  @DisplayName("cannot be a fold either")
  void cannot_be_a_fold() {
    Fold<String, String> forged =
        config
            .fold(
                DerivationId.of("forged-fold"), String.class, String.class, v -> String.join("", v))
            .acceptingAnything()
            .mint();

    assertThatThrownBy(() -> forged.fold(java.util.List.of(secret)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("attached to no loch");
  }
}
