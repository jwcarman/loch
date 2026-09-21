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

import org.jwcarman.loch.DerivationId;
import org.jwcarman.loch.DestinationId;
import org.jwcarman.loch.QuestionId;

/**
 * The names this application uses to talk to its loch.
 *
 * <p>All inert. Anyone may write one down and gain nothing by it: an unregistered name is refused,
 * and a registered one resolves to the implementation somebody reviewed.
 */
public final class Billing {

  private Billing() {}

  public static final DestinationId SUPPORT_UI = DestinationId.of("support-ui");
  public static final DestinationId APPROVAL_DESK = DestinationId.of("approval-desk");
  public static final DestinationId PAYMENT_PROCESSOR = DestinationId.of("payment-processor");

  public static final DerivationId<Domain.Mail, Domain.Invoice> CONFIRMED_INVOICE =
      DerivationId.of("mail.confirmedInvoice");
  public static final DerivationId<Domain.Invoice, Domain.Last4> CARD_LAST4 =
      DerivationId.of("invoice.card.last4");

  public static final QuestionId<Domain.Mail, String> MAIL_MENTIONS =
      QuestionId.of("mail.mentions");
}
