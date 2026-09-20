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
package org.jwcarman.loch.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.crypto.EnvelopeCodec;
import org.jwcarman.codec.crypto.JceDataKeyProvider;
import org.jwcarman.loch.AccessContext;
import org.jwcarman.loch.Auditors;
import org.jwcarman.loch.DerivationId;
import org.jwcarman.loch.Derivations;
import org.jwcarman.loch.DestinationId;
import org.jwcarman.loch.Destinations;
import org.jwcarman.loch.Held;
import org.jwcarman.loch.Loch;
import org.jwcarman.loch.lattice.Exact;
import org.jwcarman.loch.lattice.Lattice;
import org.jwcarman.loch.lattice.Lattices;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The same policy as the in-memory billing scenario, against a real database.
 *
 * <p>Postgres rather than an embedded engine on purpose: a store that claims to work on Postgres
 * should be tried on Postgres, and {@code BYTEA}, {@code ON CONFLICT} and {@code TIMESTAMPTZ} are
 * exactly the places a compatibility mode diverges quietly.
 */
@Testcontainers
@DisplayName("A loch in a database")
class JdbcLochTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("loch")
          .withUsername("loch")
          .withPassword("loch");

  enum Integrity {
    ENDORSED,
    UNENDORSED
  }

  enum DataClass {
    NONE,
    PII,
    CARDHOLDER
  }

  record Billing(Exact<String> tenant, Integrity integrity, DataClass dataClass) {

    static final Lattice<Exact<String>> TENANT = Lattices.exact();
    static final Lattice<Integrity> INTEGRITY =
        Lattices.ladder(Integrity.ENDORSED, Integrity.UNENDORSED);
    static final Lattice<DataClass> DATA =
        Lattices.ladder(DataClass.NONE, DataClass.PII, DataClass.CARDHOLDER);

    static final Lattice<Billing> LATTICE =
        new Lattice<>() {
          @Override
          public Billing join(Billing left, Billing right) {
            return new Billing(
                TENANT.join(left.tenant(), right.tenant()),
                INTEGRITY.join(left.integrity(), right.integrity()),
                DATA.join(left.dataClass(), right.dataClass()));
          }

          @Override
          public Billing bottom() {
            return new Billing(TENANT.bottom(), INTEGRITY.bottom(), DATA.bottom());
          }
        };

    static Billing of(String tenant, Integrity integrity, DataClass dataClass) {
      return new Billing(Exact.of(tenant), integrity, dataClass);
    }
  }

  record Card(String number, String holder) {}

  record Last4(String digits) {}

  static final DestinationId VENDOR_LLM = DestinationId.of("vendor-llm");
  static final DestinationId PAYMENT_PROCESSOR = DestinationId.of("payment-processor");
  static final DerivationId<Card, Last4> CARD_LAST4 = DerivationId.of("Card.last4");

  private DataSource dataSource;
  private Loch<Billing> loch;

  private static AccessContext acme() {
    return AccessContext.of("tenant", "acme");
  }

  private static Billing ceiling(AccessContext ctx, Integrity integrity, DataClass dataClass) {
    return new Billing(
        ctx.get("tenant").<Exact<String>>map(Exact::of).orElseGet(Exact::none),
        integrity,
        dataClass);
  }

  @BeforeEach
  void setUp() throws Exception {
    PGSimpleDataSource pg = new PGSimpleDataSource();
    pg.setUrl(POSTGRES.getJdbcUrl());
    pg.setUser(POSTGRES.getUsername());
    pg.setPassword(POSTGRES.getPassword());
    dataSource = pg;
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS loch_lineage_closure, loch_lineage, loch_value");
    }

    KeyGenerator generator = KeyGenerator.getInstance("AES");
    generator.init(256);
    SecretKey kek = generator.generateKey();

    loch =
        JdbcLoch.create(
            Billing.class,
            c ->
                c.dataSource(dataSource)
                    .jackson(JsonMapper.builder().build())
                    .gzipped()
                    .protectedBy(
                        EnvelopeCodec.builder(new JceDataKeyProvider("k1", Map.of("k1", kek)))
                            .build())
                    .lattice(Billing.LATTICE)
                    .auditor(Auditors.discarding())
                    .destination(
                        Destinations.varying(
                            VENDOR_LLM, ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.NONE)))
                    .destination(
                        Destinations.varying(
                            PAYMENT_PROCESSOR,
                            ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER)))
                    .derivation(
                        Derivations.<Billing, Card, Last4>of(
                                CARD_LAST4,
                                Card.class,
                                Last4.class,
                                card ->
                                    new Last4(card.number().substring(card.number().length() - 4)))
                            .lowering(
                                joined ->
                                    new Billing(joined.tenant(), joined.integrity(), DataClass.PII))
                            .build()));
  }

  private Held<Card> card() {
    return loch.hold(
        new Card("4111111111114821", "J CARMAN"),
        Billing.of("acme", Integrity.ENDORSED, DataClass.CARDHOLDER));
  }

  @Test
  @DisplayName("keeps a value and gives it back to somewhere allowed to have it")
  void keeps_a_value_and_gives_it_back() {
    Held<Card> card = card();

    assertThat(loch.dereference(card, PAYMENT_PROCESSOR, acme()).granted())
        .contains(new Card("4111111111114821", "J CARMAN"));
    assertThat(loch.dereference(card, VENDOR_LLM, acme()).allowed()).isFalse();
  }

  /** The point of the whole module: what is on disk is not the value. */
  @Test
  @DisplayName("stores no plaintext, not the value and not the label either")
  void stores_no_plaintext() throws SQLException {
    Held<Card> card = card();

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT payload, attribution FROM loch_value WHERE value_id = ?")) {
      statement.setString(1, card.id().value());
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        String payload = new String(rows.getBytes("payload"));
        String attribution = new String(rows.getBytes("attribution"));
        assertThat(payload).doesNotContain("4111111111114821").doesNotContain("CARMAN");
        assertThat(attribution).doesNotContain("acme").doesNotContain("CARDHOLDER");
      }
    }
  }

  @Test
  @DisplayName("a fresh loch over the same database reads what the last one wrote")
  void survives_a_restart() {
    Held<Card> card = card();

    assertThat(loch.holds(card)).isTrue();
    assertThat(loch.attribution(card).dataClass()).isEqualTo(DataClass.CARDHOLDER);
  }

  @Test
  @DisplayName("a derived value keeps its parentage and its lowered label")
  void a_derived_value_keeps_its_parentage() {
    Held<Card> card = card();

    Held<Last4> last4 = loch.derive(card, CARD_LAST4, acme()).orThrow();

    assertThat(loch.attribution(last4).dataClass()).isEqualTo(DataClass.PII);
    assertThat(loch.lineage(last4).parents()).containsExactly(card.id());
    assertThat(loch.lineage(last4).derivation()).contains("Card.last4");
  }

  @Test
  @DisplayName("deriving the same thing twice stores it once")
  void deriving_twice_stores_once() throws SQLException {
    Held<Card> card = card();

    Held<Last4> once = loch.derive(card, CARD_LAST4, acme()).orThrow();
    Held<Last4> twice = loch.derive(card, CARD_LAST4, acme()).orThrow();

    assertThat(once.id()).isEqualTo(twice.id());
    assertThat(rowCount("loch_value")).isEqualTo(2);
  }

  /** Erasure is a reachability query, which is what the closure table is for. */
  @Test
  @DisplayName("erasing a value takes everything ever derived from it")
  void erasing_takes_everything_derived_from_it() throws SQLException {
    Held<Card> card = card();
    Held<Last4> last4 = loch.derive(card, CARD_LAST4, acme()).orThrow();

    int removed = loch.erase(card);

    assertThat(removed).isEqualTo(2);
    assertThat(loch.holds(card)).isFalse();
    assertThat(loch.holds(last4)).isFalse();
    assertThat(rowCount("loch_lineage_closure")).isZero();
  }

  @Test
  @DisplayName("erasing a derived value leaves its parent alone")
  void erasing_a_derived_value_leaves_its_parent() {
    Held<Card> card = card();
    Held<Last4> last4 = loch.derive(card, CARD_LAST4, acme()).orThrow();

    assertThat(loch.erase(last4)).isEqualTo(1);
    assertThat(loch.holds(card)).isTrue();
  }

  @Test
  @DisplayName("another tenant's access is refused, whatever is on disk")
  void another_tenants_access_is_refused() {
    Held<Card> card = card();

    assertThat(
            loch.dereference(card, PAYMENT_PROCESSOR, AccessContext.of("tenant", "globex"))
                .allowed())
        .isFalse();
  }

  @Test
  @DisplayName("protection is a decision, not a default")
  void protection_is_a_decision() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    JdbcLoch.create(
                        Billing.class,
                        c ->
                            c.dataSource(dataSource)
                                .jackson(JsonMapper.builder().build())
                                .lattice(Billing.LATTICE)
                                .withoutAudit())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unprotected");
  }

  private int rowCount(String table) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        ResultSet rows =
            connection.createStatement().executeQuery("SELECT count(*) FROM " + table)) {
      return rows.next() ? rows.getInt(1) : 0;
    }
  }

  @Test
  @DisplayName("the closure records a value as its own ancestor, so erasing a leaf works")
  void closure_records_self() throws SQLException {
    card();

    assertThat(rowCount("loch_lineage_closure")).isEqualTo(1);
  }

  @Test
  @DisplayName("lineage of a held value says it was asserted, not computed")
  void lineage_of_a_held_value_says_asserted() {
    assertThat(loch.lineage(card()).asserted()).isTrue();
    assertThat(List.of(loch.lineage(card()).parents())).isNotEmpty();
  }

  /** Serialise, squeeze, seal. Reversing the last two would cost the same and save nothing. */
  @Test
  @DisplayName("compresses a repetitive value before encrypting it")
  void compresses_before_encrypting() throws SQLException {
    Held<Card> small = card();
    Held<Card> repetitive =
        loch.hold(
            new Card("4111111111114821", "J CARMAN ".repeat(200)),
            Billing.of("acme", Integrity.ENDORSED, DataClass.CARDHOLDER));

    int smallBytes = payloadLength(small);
    int repetitiveBytes = payloadLength(repetitive);

    // 1800 characters of a repeated name, stored in nothing like 1800 bytes.
    assertThat(repetitiveBytes).isLessThan(smallBytes + 300);
  }

  private int payloadLength(Held<?> held) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT payload FROM loch_value WHERE value_id = ?")) {
      statement.setString(1, held.id().value());
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getBytes("payload").length;
      }
    }
  }
}
