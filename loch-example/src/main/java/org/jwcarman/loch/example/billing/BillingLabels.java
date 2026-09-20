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

  /**
   * Has something we already trust agreed with this? That is the whole of it.
   *
   * <p>Each axis owns its own order, named where the constants are, so nothing elsewhere has to
   * remember it.
   */
  public enum Integrity {
    ENDORSED,
    UNENDORSED;

    public static final Lattice<Integrity> LATTICE = Lattices.ladder(ENDORSED, UNENDORSED);
  }

  public enum Sensitivity {
    ORDINARY,
    PERSONAL,
    CARDHOLDER;

    public static final Lattice<Sensitivity> LATTICE =
        Lattices.ladder(ORDINARY, PERSONAL, CARDHOLDER);
  }

  /**
   * The three axes, joined one axis at a time.
   *
   * <p>No join is written here. The product of lattices is a lattice, so composing them is enough,
   * and an axis cannot go missing: the constructor takes as many arguments as there are axes.
   */
  public static final Lattice<BillingLabels> LATTICE =
      Lattices.product(
          BillingLabels::new,
          Lattices.axis(BillingLabels::tenant, Lattices.exact()),
          Lattices.axis(BillingLabels::integrity, Integrity.LATTICE),
          Lattices.axis(BillingLabels::sensitivity, Sensitivity.LATTICE));

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
