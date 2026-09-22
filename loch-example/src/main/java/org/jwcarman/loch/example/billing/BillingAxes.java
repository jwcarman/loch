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
package org.jwcarman.loch.example.billing;

import org.jwcarman.loch.lattice.Axis;

/**
 * What this business needs to know about any piece of data it holds.
 *
 * <p>Three questions, asked independently of each other, and this is the only place the application
 * states its own security vocabulary. The library ships no mandatory scheme, because a regulated
 * company already has one it is required to use and a library is in no position to argue with it.
 */
public final class BillingAxes {

  private BillingAxes() {}

  /**
   * Has something we already trust agreed with this? That is the whole of it.
   *
   * <p>Note which end is which. Untrusted data is the <i>more</i> constrained one, because it is
   * the dangerous thing to handle -- so vouching for something moves it down this ladder, which is
   * why the operation that does it is called lowering.
   */
  public enum Integrity {
    ENDORSED,
    UNENDORSED
  }

  public enum Sensitivity {
    ORDINARY,
    PERSONAL,
    CARDHOLDER
  }

  /**
   * Whose data this is.
   *
   * <p>Required, because a tenant nobody said is the bottom of this axis, which sits below every
   * ceiling, which means readable by everyone. Marked required, the store refuses the write instead
   * of quietly storing something anybody can read.
   *
   * <p>Two tenants do not combine into either of them. They combine into a mixture that no ceiling
   * admits, so a value made from both exists, keeps honest lineage to both parents, and reaches
   * nobody. That is not a rule anyone has to remember to apply.
   */
  public static final Axis<String> TENANT = Axis.matching("tenant").required();

  public static final Axis<Integrity> INTEGRITY =
      Axis.ladder("integrity", Integrity.ENDORSED, Integrity.UNENDORSED);

  public static final Axis<Sensitivity> SENSITIVITY =
      Axis.ladder(
          "sensitivity", Sensitivity.ORDINARY, Sensitivity.PERSONAL, Sensitivity.CARDHOLDER);
}
