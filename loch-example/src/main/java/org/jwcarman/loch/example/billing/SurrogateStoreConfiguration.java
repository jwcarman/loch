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

import static org.jwcarman.loch.example.billing.BillingLabels.Integrity.ENDORSED;
import static org.jwcarman.loch.example.billing.BillingLabels.Integrity.UNENDORSED;
import static org.jwcarman.loch.example.billing.BillingLabels.Sensitivity.CARDHOLDER;
import static org.jwcarman.loch.example.billing.BillingLabels.Sensitivity.ORDINARY;
import static org.jwcarman.loch.example.billing.BillingLabels.Sensitivity.PERSONAL;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jwcarman.loch.AccessContext;
import org.jwcarman.loch.Derivation;
import org.jwcarman.loch.Query;
import org.jwcarman.loch.SurrogateSink;
import org.jwcarman.loch.SurrogateSource;
import org.jwcarman.loch.SurrogateStoreConfig;
import org.jwcarman.loch.lattice.Exact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Everything this application is allowed to do, and nothing about how it is wired.
 *
 * <p>The data source, the codecs and the moment the store is built are the starter's business. What
 * is left here is policy: which labels exist, what may be written where, what may be read where,
 * and who gets handed which of it.
 *
 * <p><b>Taking the configuration as a parameter is what declares a portal</b>, and it is root
 * authority: whatever holds it can declare one at any label and any ceiling. That is how portals
 * come into being, so it cannot be otherwise -- but a class taking one in its constructor is a
 * class worth a second look in review. Services take portals, not this.
 */
@Configuration
public class SurrogateStoreConfiguration {

  private static final Pattern INVOICE = Pattern.compile("INV-\\d+");

  /**
   * The labels, and where identity comes from. Nothing about where any of it is kept.
   *
   * <p>This says nothing about databases. The starter supplies the data source, the serialisation
   * and the sealing, and builds the store once every portal has been declared -- so moving this
   * application onto a different backing store changes no line in this file.
   */
  @Bean
  public SurrogateStoreConfig<BillingLabels, Domain.BillingValue> surrogateStoreConfig() {
    // The domain bound is the second parameter. A source over String would not compile.
    return new SurrogateStoreConfig<BillingLabels, Domain.BillingValue>()
        .labelType(BillingLabels.class)
        .lattice(BillingLabels.LATTICE);
  }

  /**
   * The portals the dispute desk needs, and the service that holds them.
   *
   * <p>Nothing here knows when the store is built, and nothing has to: a capability is attached
   * when it is, and none of these is used before the context is ready.
   */
  @Bean
  public DisputeService disputeService(
      SurrogateStoreConfig<BillingLabels, Domain.BillingValue> config, Invoices invoices) {

    // ---- how values get in -----------------------------------------------------
    // The tenant is read from the access, never passed by the caller. Writing at another
    // tenant's label is not refused so much as unsayable: nothing takes a label.
    SurrogateSource<Domain.Mail> customerMail =
        config.source("customer-mail", Domain.MAIL, ctx -> label(ctx, UNENDORSED, PERSONAL));

    // ---- how values get out ----------------------------------------------------
    SurrogateSink<Domain.Invoice> supportUi =
        config.sink("support-ui", Domain.INVOICE, ctx -> label(ctx, ENDORSED, ORDINARY));
    SurrogateSink<Domain.Last4> approvalDesk =
        config.sink(
            "approval-desk",
            Domain.LAST4,
            ctx -> label(ctx, ENDORSED, ctx.has("role", "approver") ? PERSONAL : ORDINARY));
    SurrogateSink<Domain.Invoice> paymentProcessor =
        config.sink("payment-processor", Domain.INVOICE, ctx -> label(ctx, ENDORSED, CARDHOLDER));

    // ---- one value from another ------------------------------------------------
    // The only operation that can raise trust, and it earns it by tying what the customer
    // claimed to the mailbox their message came from.
    Derivation<Domain.Mail, Domain.Invoice> confirmInvoice =
        config
            .checking(
                "mail.confirmedInvoice",
                Domain.MAIL,
                Domain.INVOICE,
                (mail, ctx) -> confirm(invoices, mail, ctx))
            .accepting(ctx -> label(ctx, UNENDORSED, PERSONAL))
            .lowering(joined -> joined.withIntegrity(ENDORSED))
            .mint();

    // Truncating a card is a declassification, which is what a PCI reviewer asks about.
    Derivation<Domain.Invoice, Domain.Last4> cardLast4 =
        config
            .derivation(
                "invoice.card.last4",
                Domain.INVOICE,
                Domain.LAST4,
                invoice -> new Domain.Last4(last4(invoice.cardToken())))
            .accepting(ctx -> label(ctx, ENDORSED, CARDHOLDER))
            .lowering(joined -> joined.withSensitivity(PERSONAL))
            .availableTo(ctx -> ctx.has("role", "approver"))
            .mint();

    // ---- one bit, without the value leaving ------------------------------------
    Query<Domain.Mail, String> mailMentions =
        config
            .query(
                "mail.mentions",
                Domain.MAIL,
                String.class,
                (mail, text, ctx) -> mail.body().toLowerCase().contains(text.toLowerCase()))
            .accepting(ctx -> label(ctx, UNENDORSED, PERSONAL))
            .mint();

    return new DisputeService(
        customerMail,
        supportUi,
        approvalDesk,
        paymentProcessor,
        confirmInvoice,
        cardLast4,
        mailMentions);
  }

  /**
   * A label for this access: the tenant comes from the request, never from the caller.
   *
   * <p>{@code Exact.none()} for a missing tenant would be a cross-tenant leak on its own -- none is
   * the bottom of the order, so it constrains nothing, so the value would be readable by every
   * tenant. It is safe here only because the tenant axis is declared required, which makes the
   * store refuse the write rather than store something anybody can read.
   */
  private static BillingLabels label(
      AccessContext ctx, BillingLabels.Integrity integrity, BillingLabels.Sensitivity sensitivity) {
    return new BillingLabels(
        ctx.get("tenant").<Exact<String>>map(Exact::of).orElseGet(Exact::none),
        integrity,
        sensitivity);
  }

  /** Trust is earned by matching the claim against the mailbox it arrived from. */
  private static Optional<Domain.Invoice> confirm(
      Invoices invoices, Domain.Mail mail, AccessContext ctx) {
    Matcher matcher = INVOICE.matcher(mail.body());
    if (!matcher.find()) {
      return Optional.empty();
    }
    return invoices
        .find(matcher.group())
        .filter(invoice -> ctx.has("tenant", invoice.tenant()))
        .filter(invoice -> invoice.customerEmail().equalsIgnoreCase(mail.from()));
  }

  private static String last4(String token) {
    return token.substring(token.length() - 4);
  }
}
