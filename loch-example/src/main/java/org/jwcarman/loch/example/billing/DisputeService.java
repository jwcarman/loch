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

import static org.jwcarman.loch.example.billing.BillingLabels.Integrity.UNENDORSED;
import static org.jwcarman.loch.example.billing.BillingLabels.Sensitivity.CARDHOLDER;
import static org.jwcarman.loch.example.billing.BillingLabels.Sensitivity.PERSONAL;

import org.jwcarman.loch.Held;
import org.jwcarman.loch.HeldId;
import org.jwcarman.loch.Loch;
import org.springframework.stereotype.Service;

/**
 * What the support desk does.
 *
 * <p>Notice what is not here: no {@code AccessContext} parameters, no copying of labels from one
 * value to another, no "is this safe to show" checks, and no mention of encryption. The methods
 * read like the business operations they are.
 */
@Service
public class DisputeService {

  private final Loch<BillingLabels> loch;

  public DisputeService(Loch<BillingLabels> loch) {
    this.loch = loch;
  }

  /**
   * A customer's message arrives.
   *
   * <p>The one place this application states what something is. After this, labels are computed.
   */
  public HeldId receive(String tenant, Domain.Mail mail) {
    return loch.hold(mail, Domain.Mail.class, BillingLabels.of(tenant, UNENDORSED, PERSONAL)).id();
  }

  /** Does the message mention this? Answered without the message leaving the store. */
  public boolean mentions(HeldId mail, String text) {
    return loch.check(Held.of(mail, Domain.Mail.class), Billing.MAIL_MENTIONS, text).isTrue();
  }

  /**
   * Turns what a customer claimed into something the business can act on.
   *
   * <p>Fails when the invoice does not exist, belongs to another tenant, or was not raised from the
   * address that wrote in. Only then does the result become endorsed.
   */
  public HeldId confirm(HeldId mail) {
    return loch.derive(Held.of(mail, Domain.Mail.class), Billing.CONFIRMED_INVOICE).orThrow().id();
  }

  /** What an approver is shown: four digits, and only if they are an approver. */
  public Domain.Last4 cardForApproval(HeldId invoice) {
    HeldId last4 =
        loch.derive(Held.of(invoice, Domain.Invoice.class), Billing.CARD_LAST4).orThrow().id();
    return loch.dereference(Held.of(last4, Domain.Last4.class), Billing.APPROVAL_DESK).orThrow();
  }

  /**
   * Issues the refund.
   *
   * <p>The card token reaches the payment processor and nowhere else, because that is the only
   * destination whose ceiling admits {@link BillingLabels.Sensitivity#CARDHOLDER}. Not a rule
   * anybody remembered to write: every other destination sits below it in the order.
   */
  public String refund(HeldId invoice) {
    Domain.Invoice confirmed =
        loch.dereference(Held.of(invoice, Domain.Invoice.class), Billing.PAYMENT_PROCESSOR)
            .orThrow();
    return "refunded %s to card ending %s"
        .formatted(confirmed.amount(), last4(confirmed.cardToken()));
  }

  /** What the support agent's screen may show. */
  public Domain.Invoice forSupportScreen(HeldId invoice) {
    return loch.dereference(Held.of(invoice, Domain.Invoice.class), Billing.SUPPORT_UI).orThrow();
  }

  private static String last4(String token) {
    return token.substring(token.length() - 4);
  }

  /** Only used to describe a refusal; never a way to read a value. */
  public BillingLabels labelOf(HeldId id) {
    return loch.label(Held.of(id, Domain.Mail.class));
  }

  static {
    // Referenced so the constants read as documentation of what this service touches.
    assert PERSONAL != CARDHOLDER;
  }
}
