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

import org.jwcarman.loch.Conceal;
import org.jwcarman.loch.Derivation;
import org.jwcarman.loch.Query;
import org.jwcarman.loch.Reveal;
import org.jwcarman.loch.Surrogate;

/**
 * What the support desk does.
 *
 * <p>Notice what is not here: no {@code AccessContext} parameters, no copying of labels from one
 * value to another, no "is this safe to show" checks, and no mention of encryption. The methods
 * read like the business operations they are.
 */
public class DisputeService {

  private final Conceal<Domain.Mail> customerMail;
  private final Reveal<Domain.Invoice> supportUi;
  private final Reveal<Domain.Last4> approvalDesk;
  private final Reveal<Domain.Invoice> paymentProcessor;
  private final Derivation<Domain.Mail, Domain.Invoice> confirmInvoice;
  private final Derivation<Domain.Invoice, Domain.Last4> cardLast4;
  private final Query<Domain.Mail, String> mailMentions;

  // What this class may do is this list. It was handed three outlets, so it can reach three
  // places; it was handed one source, so there is exactly one label it can create a value at.
  public DisputeService(
      Conceal<Domain.Mail> customerMail,
      Reveal<Domain.Invoice> supportUi,
      Reveal<Domain.Last4> approvalDesk,
      Reveal<Domain.Invoice> paymentProcessor,
      Derivation<Domain.Mail, Domain.Invoice> confirmInvoice,
      Derivation<Domain.Invoice, Domain.Last4> cardLast4,
      Query<Domain.Mail, String> mailMentions) {
    this.customerMail = customerMail;
    this.supportUi = supportUi;
    this.approvalDesk = approvalDesk;
    this.paymentProcessor = paymentProcessor;
    this.confirmInvoice = confirmInvoice;
    this.cardLast4 = cardLast4;
    this.mailMentions = mailMentions;
  }

  /**
   * A customer's message arrives.
   *
   * <p>The one place this application states what something is. After this, labels are computed.
   */
  public String receive(Domain.Mail mail) {
    return customerMail.conceal(mail).id();
  }

  /** Does the message mention this? Answered without the message leaving the store. */
  public boolean mentions(String mail, String text) {
    return mailMentions.ask(Surrogate.of(mail), text).isTrue();
  }

  /**
   * Turns what a customer claimed into something the business can act on.
   *
   * <p>Fails when the invoice does not exist, belongs to another tenant, or was not raised from the
   * address that wrote in. Only then does the result become endorsed.
   */
  public String confirm(String mail) {
    return confirmInvoice.derive(Surrogate.of(mail)).orThrow().id();
  }

  /** What an approver is shown: four digits, and only if they are an approver. */
  public Domain.Last4 cardForApproval(String invoice) {
    String last4 = cardLast4.derive(Surrogate.of(invoice)).orThrow().id();
    return approvalDesk.reveal(Surrogate.of(last4)).orThrow();
  }

  /**
   * Issues the refund.
   *
   * <p>The card token reaches the payment processor and nowhere else, because that is the only
   * destination whose ceiling admits {@link BillingAxes.Sensitivity#CARDHOLDER}. Not a rule anybody
   * remembered to write: every other destination sits below it in the order.
   */
  public String refund(String invoice) {
    Domain.Invoice confirmed = paymentProcessor.reveal(Surrogate.of(invoice)).orThrow();
    return "refunded %s to card ending %s"
        .formatted(confirmed.amount(), last4(confirmed.cardToken()));
  }

  /** What the support agent's screen may show. */
  public Domain.Invoice forSupportScreen(String invoice) {
    return supportUi.reveal(Surrogate.of(invoice)).orThrow();
  }

  private static String last4(String token) {
    return token.substring(token.length() - 4);
  }
}
