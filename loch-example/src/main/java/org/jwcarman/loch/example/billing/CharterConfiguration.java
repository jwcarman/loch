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

import static org.jwcarman.loch.example.billing.BillingAxes.Integrity.ENDORSED;
import static org.jwcarman.loch.example.billing.BillingAxes.Integrity.UNENDORSED;
import static org.jwcarman.loch.example.billing.BillingAxes.Sensitivity.CARDHOLDER;
import static org.jwcarman.loch.example.billing.BillingAxes.Sensitivity.ORDINARY;
import static org.jwcarman.loch.example.billing.BillingAxes.Sensitivity.PERSONAL;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jwcarman.loch.AccessContext;
import org.jwcarman.loch.Charter;
import org.jwcarman.loch.Conceal;
import org.jwcarman.loch.Derivation;
import org.jwcarman.loch.Query;
import org.jwcarman.loch.Reveal;
import org.jwcarman.loch.lattice.Axes;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Constraint;
import org.jwcarman.loch.lattice.Label;
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
public class CharterConfiguration {

  private static final Pattern INVOICE = Pattern.compile("INV-\\d+");

  /**
   * The questions this business asks about every value it holds.
   *
   * <p>All this application says about its charter. The charter itself is constructed from these,
   * by the starter, which is therefore the only thing able to seal it -- so nothing here can bring
   * one into force, and nothing here can erase through one. Authority arrives the same way it does
   * everywhere else in this file: because somebody handed it over.
   */
  @Bean
  public Axes billingAxes() {
    return Axes.of(BillingAxes.TENANT, BillingAxes.INTEGRITY, BillingAxes.SENSITIVITY);
  }

  /**
   * The portals the dispute desk needs, and the service that holds them.
   *
   * <p>Nothing here knows when the store is built, and nothing has to: a capability is attached
   * when it is, and none of these is used before the context is ready.
   */
  @Bean
  public DisputeService disputeService(Charter charter, Invoices invoices) {

    // ---- how values get in -----------------------------------------------------
    // The tenant is read from the access, never passed by the caller. Writing at another
    // tenant's label is not refused so much as unsayable: nothing takes a label.
    Conceal<Domain.Mail> customerMail =
        charter.source("customer-mail", Domain.MAIL, ctx -> label(ctx, UNENDORSED, PERSONAL));

    // ---- how values get out ----------------------------------------------------
    Reveal<Domain.Invoice> supportUi =
        charter
            .destination("support-ui", ctx -> ceiling(ctx, ENDORSED, ORDINARY), Domain.INVOICE)
            .reading(Domain.INVOICE);
    Reveal<Domain.Last4> approvalDesk =
        charter
            .destination(
                "approval-desk",
                ctx -> ceiling(ctx, ENDORSED, ctx.has("role", "approver") ? PERSONAL : ORDINARY),
                Domain.LAST4)
            .reading(Domain.LAST4);
    Reveal<Domain.Invoice> paymentProcessor =
        charter
            .destination(
                "payment-processor", ctx -> ceiling(ctx, ENDORSED, CARDHOLDER), Domain.INVOICE)
            .reading(Domain.INVOICE);

    // ---- one value from another ------------------------------------------------
    // The only operation that can raise trust, and it earns it by tying what the customer
    // claimed to the mailbox their message came from.
    Derivation<Domain.Mail, Domain.Invoice> confirmInvoice =
        charter.checking(
            "mail.confirmedInvoice",
            Domain.MAIL,
            Domain.INVOICE,
            (mail, ctx) -> confirm(invoices, mail, ctx),
            d ->
                d.accepting(ctx -> ceiling(ctx, UNENDORSED, PERSONAL))
                    .lowering(joined -> joined.with(BillingAxes.INTEGRITY, ENDORSED)));

    // Truncating a card is a declassification, which is what a PCI reviewer asks about.
    Derivation<Domain.Invoice, Domain.Last4> cardLast4 =
        charter.derivation(
            "invoice.card.last4",
            Domain.INVOICE,
            Domain.LAST4,
            invoice -> new Domain.Last4(last4(invoice.cardToken())),
            d ->
                d.accepting(ctx -> ceiling(ctx, ENDORSED, CARDHOLDER))
                    .lowering(joined -> joined.with(BillingAxes.SENSITIVITY, PERSONAL))
                    .availableTo(ctx -> ctx.has("role", "approver")));

    // ---- one bit, without the value leaving ------------------------------------
    Query<Domain.Mail, String> mailMentions =
        charter.query(
            "mail.mentions",
            Domain.MAIL,
            String.class,
            (mail, text, ctx) -> mail.body().toLowerCase().contains(text.toLowerCase()),
            d -> d.accepting(ctx -> ceiling(ctx, UNENDORSED, PERSONAL)));

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
  private static Label label(
      AccessContext ctx, BillingAxes.Integrity integrity, BillingAxes.Sensitivity sensitivity) {
    return ctx.get("tenant")
        .map(tenant -> Label.of(BillingAxes.TENANT, tenant))
        .orElseGet(Label::nothing)
        .with(BillingAxes.INTEGRITY, integrity)
        .with(BillingAxes.SENSITIVITY, sensitivity);
  }

  /**
   * What a reader may see: a point on every axis, with the tenant taken from the access.
   *
   * <p>A request that says nothing about a tenant gets no ceiling at all rather than a broad one.
   * Throwing here is not sloppiness -- the store treats a ceiling it cannot evaluate as a refusal
   * and writes the line, which is what a gate unable to say it is open should do.
   */
  private static Ceiling ceiling(
      AccessContext ctx, BillingAxes.Integrity integrity, BillingAxes.Sensitivity sensitivity) {
    String tenant =
        ctx.get("tenant")
            .orElseThrow(
                () -> new IllegalStateException("this access says nothing about which tenant"));
    return Ceiling.of(BillingAxes.TENANT, Constraint.atMost(tenant))
        .with(BillingAxes.INTEGRITY, Constraint.atMost(integrity))
        .with(BillingAxes.SENSITIVITY, Constraint.atMost(sensitivity));
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
