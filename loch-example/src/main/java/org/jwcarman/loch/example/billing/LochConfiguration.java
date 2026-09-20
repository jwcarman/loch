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

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.jwcarman.codec.crypto.EnvelopeCodec;
import org.jwcarman.codec.crypto.JceDataKeyProvider;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.codec.transform.compress.GzipCodec;
import org.jwcarman.loch.AccessContext;
import org.jwcarman.loch.Auditor;
import org.jwcarman.loch.Check;
import org.jwcarman.loch.Derivations;
import org.jwcarman.loch.Destinations;
import org.jwcarman.loch.Loch;
import org.jwcarman.loch.jdbc.Compression;
import org.jwcarman.loch.jdbc.JdbcLoch;
import org.jwcarman.loch.jdbc.StorageCodec;
import org.jwcarman.loch.lattice.Exact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.json.JsonMapper;

/** This application's entire information-flow policy, in one file. */
@Configuration
public class LochConfiguration {

  private static final Logger log = LoggerFactory.getLogger(LochConfiguration.class);
  private static final Pattern INVOICE = Pattern.compile("INV-\\d+");

  /** Every access, allowed or refused, ends up here. */
  @Bean
  public Auditor auditor() {
    return record -> log.info("[loch] {}", record);
  }

  @Bean
  public StorageCodec storageCodec(@Value("${billing.key}") String key) {
    SecretKey kek = new SecretKeySpec(java.util.Base64.getDecoder().decode(key), "AES");
    return StorageCodec.of(
        Compression.whenItHelps(new GzipCodec())
            .andThen(
                EnvelopeCodec.builder(new JceDataKeyProvider("k1", Map.of("k1", kek))).build()));
  }

  @Bean
  public Loch<BillingLabels> loch(
      DataSource dataSource, StorageCodec storageCodec, Auditor auditor, Invoices invoices) {
    return JdbcLoch.create(
        BillingLabels.class,
        c ->
            c.dataSource(dataSource)
                .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
                .storedThrough(storageCodec)
                .lattice(BillingLabels.LATTICE)
                .auditor(auditor)
                .askingWhoIsAsking(CurrentAccess::get)

                // ---- where values may go ------------------------------------------------
                .destination(
                    Destinations.varying(
                        Billing.SUPPORT_UI, ctx -> ceiling(ctx, ENDORSED, ORDINARY)))
                .destination(
                    Destinations.varying(
                        Billing.APPROVAL_DESK,
                        ctx ->
                            ceiling(
                                ctx, ENDORSED, ctx.has("role", "approver") ? PERSONAL : ORDINARY)))
                .destination(
                    Destinations.varying(
                        Billing.PAYMENT_PROCESSOR, ctx -> ceiling(ctx, ENDORSED, CARDHOLDER)))

                // ---- one value from another ---------------------------------------------
                // The only operation that can raise trust, and it earns it by tying what the
                // customer claimed to the mailbox their message came from.
                .derivation(
                    Derivations.<BillingLabels, Domain.Mail, Domain.Invoice>checking(
                            Billing.CONFIRMED_INVOICE,
                            Domain.Mail.class,
                            Domain.Invoice.class,
                            (mail, ctx) -> confirm(invoices, mail, ctx))
                        .lowering(joined -> joined.withIntegrity(ENDORSED))
                        .build())

                // Truncating a card is a declassification, which is what a PCI reviewer asks about.
                .derivation(
                    Derivations.<BillingLabels, Domain.Invoice, Domain.Last4>of(
                            Billing.CARD_LAST4,
                            Domain.Invoice.class,
                            Domain.Last4.class,
                            invoice -> new Domain.Last4(last4(invoice.cardToken())))
                        .lowering(joined -> joined.withSensitivity(PERSONAL))
                        .availableTo(ctx -> ctx.has("role", "approver"))
                        .build())

                // ---- questions answered without handing the value over ------------------
                .check(
                    Check.<BillingLabels, Domain.Mail, String>of(
                            Billing.MAIL_MENTIONS,
                            Domain.Mail.class,
                            (mail, text) -> mail.body().toLowerCase().contains(text.toLowerCase()))
                        // A ceiling, like a destination's, takes the tenant from the access: there
                        // is no fixed ceiling meaning "any one tenant but not a mixture".
                        .accepting(ctx -> ceiling(ctx, UNENDORSED, PERSONAL))
                        .build()));
  }

  /** Printed once at startup, so what this service will allow is in the log. */
  @EventListener(ApplicationReadyEvent.class)
  public void announce(ApplicationReadyEvent event) {
    log.info("\n{}", event.getApplicationContext().getBean(Loch.class).manifest());
  }

  /**
   * An access is always on behalf of one tenant, and a ceiling says so.
   *
   * <p>Naming nobody gives a ceiling of "no tenant", which admits only unattributed values: the
   * fail-closed answer, and deliberate.
   */
  private static BillingLabels ceiling(
      AccessContext ctx, BillingLabels.Integrity integrity, BillingLabels.Sensitivity sensitivity) {
    return new BillingLabels(
        ctx.get("tenant").<Exact<String>>map(Exact::of).orElseGet(Exact::none),
        integrity,
        sensitivity);
  }

  /** An endorsement is only as strong as what it bound: here, the sender's own mailbox. */
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
