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

import java.math.BigDecimal;

/** The things this service deals in. */
public final class Domain {

  private Domain() {}

  /**
   * Everything this application is willing to put in a loch.
   *
   * <p>Ours, not the library's: it imports nothing from Loch, and Loch only ever sees it as the
   * bound on a type parameter. What it buys is that {@code Source<String>} does not compile. A sink
   * over {@code String} would read every surrogate for a {@code String} whose label permitted it,
   * and a card token and a customer's display name are the same type to Java.
   */
  public interface BillingValue {}

  /** What a customer sent. Untrusted, and full of their own personal details. */
  public record Mail(String from, String body) implements BillingValue {}

  /** A row from the billing system: authoritative, and holding a live card token. */
  public record Invoice(
      String number, String tenant, String customerEmail, BigDecimal amount, String cardToken)
      implements BillingValue {}

  /** Four digits, which is all an approver needs to recognise a card. */
  public record Last4(String digits) implements BillingValue {}
}
