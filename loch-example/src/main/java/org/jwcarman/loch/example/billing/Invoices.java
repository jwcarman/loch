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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The billing system of record. Somebody else's database in a real deployment. */
@Repository
public class Invoices {

  private static final List<Domain.Invoice> ROWS =
      List.of(
          new Domain.Invoice(
              "INV-4471",
              "acme",
              "dana@acme.example",
              new BigDecimal("412.00"),
              "tok_live_9911554821"),
          new Domain.Invoice(
              "INV-9999",
              "globex",
              "sam@globex.example",
              new BigDecimal("12.00"),
              "tok_live_7733221188"));

  public Optional<Domain.Invoice> find(String number) {
    return ROWS.stream().filter(invoice -> invoice.number().equals(number)).findFirst();
  }
}
