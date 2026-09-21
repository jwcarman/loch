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

import org.jwcarman.loch.Handle;
import org.jwcarman.loch.HandleId;
import org.jwcarman.loch.Inlet;
import org.jwcarman.loch.Loch;
import org.jwcarman.loch.Outlet;
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
  private final Inlet<Domain.Mail> customerMail;
  private final Outlet supportUi;
  private final Outlet approvalDesk;
  private final Outlet paymentProcessor;

  // What this class may do is this list. It was handed three outlets, so it can reach three
  // places; it was handed one inlet, so there is exactly one label it can create a value at.
  public DisputeService(
      Loch<BillingLabels> loch,
      Inlet<Domain.Mail> customerMail,
      Outlet supportUi,
      Outlet approvalDesk,
      Outlet paymentProcessor) {
    this.loch = loch;
    this.customerMail = customerMail;
    this.supportUi = supportUi;
    this.approvalDesk = approvalDesk;
    this.paymentProcessor = paymentProcessor;
  }

  /**
   * A customer's message arrives.
   *
   * <p>The one place this application states what something is. After this, labels are computed.
   */
  public HandleId receive(Domain.Mail mail) {
    return customerMail.hold(mail).id();
  }

  /** Does the message mention this? Answered without the message leaving the store. */
  public boolean mentions(HandleId mail, String text) {
    return loch.ask(Handle.of(mail, Domain.Mail.class), Billing.MAIL_MENTIONS, text).isTrue();
  }

  /**
   * Turns what a customer claimed into something the business can act on.
   *
   * <p>Fails when the invoice does not exist, belongs to another tenant, or was not raised from the
   * address that wrote in. Only then does the result become endorsed.
   */
  public HandleId confirm(HandleId mail) {
    return loch.derive(Handle.of(mail, Domain.Mail.class), Billing.CONFIRMED_INVOICE)
        .orThrow()
        .id();
  }

  /** What an approver is shown: four digits, and only if they are an approver. */
  public Domain.Last4 cardForApproval(HandleId invoice) {
    HandleId last4 =
        loch.derive(Handle.of(invoice, Domain.Invoice.class), Billing.CARD_LAST4).orThrow().id();
    return approvalDesk.read(Handle.of(last4, Domain.Last4.class)).orThrow();
  }

  /**
   * Issues the refund.
   *
   * <p>The card token reaches the payment processor and nowhere else, because that is the only
   * destination whose ceiling admits {@link BillingLabels.Sensitivity#CARDHOLDER}. Not a rule
   * anybody remembered to write: every other destination sits below it in the order.
   */
  public String refund(HandleId invoice) {
    Domain.Invoice confirmed =
        paymentProcessor.read(Handle.of(invoice, Domain.Invoice.class)).orThrow();
    return "refunded %s to card ending %s"
        .formatted(confirmed.amount(), last4(confirmed.cardToken()));
  }

  /** What the support agent's screen may show. */
  public Domain.Invoice forSupportScreen(HandleId invoice) {
    return supportUi.read(Handle.of(invoice, Domain.Invoice.class)).orThrow();
  }

  private static String last4(String token) {
    return token.substring(token.length() - 4);
  }

  /** What a value is labelled, for a screen that must decide what to show. */
  public BillingLabels labelOf(HandleId id) {
    return loch.label(id);
  }
}
