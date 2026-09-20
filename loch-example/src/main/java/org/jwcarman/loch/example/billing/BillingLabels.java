/*
 * Copyright © ${year} James Carman
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
package org.jwcarman.loch.example.billing;

import org.jwcarman.loch.lattice.Exact;
import org.jwcarman.loch.lattice.Lattice;
import org.jwcarman.loch.lattice.Lattices;

/**
 * What this business needs to know about any piece of data it holds.
 *
 * <p>Written once, and the only place the application states its own security vocabulary. Loch
 * ships no mandatory scheme, because a regulated company already has one it is required to use and
 * a library is in no position to argue with it.
 */
public record BillingLabels(Exact<String> tenant, Integrity integrity, Sensitivity sensitivity) {

  /** Has something we already trust agreed with this? That is the whole of it. */
  public enum Integrity {
    ENDORSED,
    UNENDORSED
  }

  public enum Sensitivity {
    ORDINARY,
    PERSONAL,
    CARDHOLDER
  }

  private static final Lattice<Exact<String>> TENANT = Lattices.exact();

  // The order is said here, at the wiring, not inferred from the order of the constants above.
  private static final Lattice<Integrity> INTEGRITY =
      Lattices.ladder(Integrity.ENDORSED, Integrity.UNENDORSED);
  private static final Lattice<Sensitivity> SENSITIVITY =
      Lattices.ladder(Sensitivity.ORDINARY, Sensitivity.PERSONAL, Sensitivity.CARDHOLDER);

  /** Componentwise. The TCK proves it, so nobody has to review it by eye. */
  public static final Lattice<BillingLabels> LATTICE =
      new Lattice<>() {
        @Override
        public BillingLabels join(BillingLabels left, BillingLabels right) {
          return new BillingLabels(
              TENANT.join(left.tenant(), right.tenant()),
              INTEGRITY.join(left.integrity(), right.integrity()),
              SENSITIVITY.join(left.sensitivity(), right.sensitivity()));
        }

        @Override
        public BillingLabels bottom() {
          return new BillingLabels(TENANT.bottom(), INTEGRITY.bottom(), SENSITIVITY.bottom());
        }
      };

  public static BillingLabels of(String tenant, Integrity integrity, Sensitivity sensitivity) {
    return new BillingLabels(Exact.of(tenant), integrity, sensitivity);
  }

  public BillingLabels withIntegrity(Integrity value) {
    return new BillingLabels(tenant, value, sensitivity);
  }

  public BillingLabels withSensitivity(Sensitivity value) {
    return new BillingLabels(tenant, integrity, value);
  }
}
