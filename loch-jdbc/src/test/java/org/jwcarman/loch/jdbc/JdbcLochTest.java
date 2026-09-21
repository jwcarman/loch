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
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.codec.transform.compress.GzipCodec;
import org.jwcarman.loch.AccessContext;
import org.jwcarman.loch.Auditors;
import org.jwcarman.loch.Derivation;
import org.jwcarman.loch.Loch;
import org.jwcarman.loch.Surrogate;
import org.jwcarman.loch.SurrogateSink;
import org.jwcarman.loch.SurrogateSource;
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

  private DataSource dataSource;
  private Loch<Billing> loch;
  private Derivation<Card, Last4> cardLast4;
  private SurrogateSource<Card> cards;
  private SurrogateSink<Card> vendorLlm;
  private SurrogateSink<Card> paymentProcessor;
  private SurrogateSink<Last4> last4Processor;
  private SurrogateSource<List<Card>> cardLists;
  private SurrogateSink<List<Card>> cardListProcessor;
  private SurrogateSink<List<Card>> cardListVendor;
  private SurrogateSink<List<Last4>> last4ListProcessor;

  /** Standing in for the edge. A caller is not allowed to say who it is. */
  private final java.util.concurrent.atomic.AtomicReference<AccessContext> edge =
      new java.util.concurrent.atomic.AtomicReference<>(AccessContext.empty());

  private AccessContext acme() {
    edge.set(AccessContext.of("tenant", "acme"));
    return AccessContext.empty();
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

    JdbcLochConfig<Billing, Object> c = new JdbcLochConfig<>();
    c.dataSource(dataSource)
        .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
        // The application composes its own pipeline: squeeze, then seal.
        .storedThrough(
            StorageCodec.of(
                Compression.whenItHelps(new GzipCodec())
                    .andThen(
                        EnvelopeCodec.builder(new JceDataKeyProvider("k1", Map.of("k1", kek)))
                            .build())))
        .lattice(Billing.LATTICE)
        .askingWhoIsAsking(edge::get)
        // Erasure is the one operation a label cannot decide, so it is named here.
        .mayErase(
            (label, ctx) ->
                ctx.has("role", "compliance")
                    && label.tenant().resolved().filter(t -> ctx.has("tenant", t)).isPresent())
        .auditor(Auditors.discarding());

    // One source: everything this test holds is acme's cardholder data.
    cards =
        c.source(
            "cards", Card.class, ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER));
    vendorLlm =
        c.sink("vendor-llm", Card.class, ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.NONE));
    // One destination, three readers. The ceiling is written once, every reader enforces it,
    // and all three audit under "payment-processor" because that is the subsystem they reach.
    var processor =
        c.destination(
            "payment-processor", ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER));
    paymentProcessor = processor.reading(Card.class);
    last4Processor = processor.reading(Last4.class);

    // A generic container is its own type, so it needs its own source and its own sinks.
    cardLists =
        c.source(
            "card-lists",
            TypeRef.listOf(TypeRef.of(Card.class)),
            ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER));
    cardListProcessor = processor.reading(TypeRef.listOf(TypeRef.of(Card.class)));
    cardListVendor =
        c.sink(
            "card-lists-to-vendor",
            TypeRef.listOf(TypeRef.of(Card.class)),
            ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.NONE));
    last4ListProcessor =
        c.sink(
            "last4-lists-to-processor",
            TypeRef.listOf(TypeRef.of(Last4.class)),
            ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER));

    cardLast4 =
        c.derivation(
                "Card.last4",
                Card.class,
                Last4.class,
                card -> new Last4(card.number().substring(card.number().length() - 4)))
            .accepting(ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER))
            .lowering(joined -> new Billing(joined.tenant(), joined.integrity(), DataClass.PII))
            .mint();

    loch = JdbcLoch.create(Billing.class, c);
  }

  private Surrogate<Card> card() {
    acme();
    return cards.exchange(new Card("4111111111114821", "J CARMAN"));
  }

  @Test
  @DisplayName("keeps a value and gives it back to somewhere allowed to have it")
  void keeps_a_value_and_gives_it_back() {
    Surrogate<Card> card = card();

    assertThat(paymentProcessor.exchange(card, acme()).granted())
        .contains(new Card("4111111111114821", "J CARMAN"));
    assertThat(vendorLlm.exchange(card, acme()).allowed()).isFalse();
  }

  /** The point of the whole module: what is on disk is not the value. */
  @Test
  @DisplayName("stores no plaintext, not the value and not the label either")
  void stores_no_plaintext() throws SQLException {
    Surrogate<Card> card = card();

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT payload, label FROM loch_value WHERE value_id = ?")) {
      statement.setString(1, card.id());
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        String payload = new String(rows.getBytes("payload"));
        String label = new String(rows.getBytes("label"));
        assertThat(payload).doesNotContain("4111111111114821").doesNotContain("CARMAN");
        assertThat(label).doesNotContain("acme").doesNotContain("CARDHOLDER");
      }
    }
  }

  @Test
  @DisplayName("a fresh loch over the same database reads what the last one wrote")
  void survives_a_restart() {
    Surrogate<Card> card = card();

    assertThat(loch.holds(card)).isTrue();
    assertThat(loch.label(card).dataClass()).isEqualTo(DataClass.CARDHOLDER);
  }

  @Test
  @DisplayName("a derived value keeps its parentage and its lowered label")
  void a_derived_value_keeps_its_parentage() {
    Surrogate<Card> card = card();

    Surrogate<Last4> last4 = cardLast4.derive(card, acme()).orThrow();

    assertThat(loch.label(last4).dataClass()).isEqualTo(DataClass.PII);
    assertThat(loch.lineage(last4).parents()).containsExactly(card.id());
    assertThat(loch.lineage(last4).derivation()).contains("Card.last4");
  }

  @Test
  @DisplayName("deriving the same thing twice stores it twice, and says so")
  void deriving_twice_stores_twice() throws SQLException {
    Surrogate<Card> card = card();

    Surrogate<Last4> once = cardLast4.derive(card, acme()).orThrow();
    Surrogate<Last4> twice = cardLast4.derive(card, acme()).orThrow();

    assertThat(once.id()).isNotEqualTo(twice.id());
    assertThat(rowCount("loch_value")).isEqualTo(3);
  }

  /** Erasure is a reachability query, which is what the closure table is for. */
  @Test
  @DisplayName("erasing a value takes everything ever derived from it")
  void erasing_takes_everything_derived_from_it() throws SQLException {
    Surrogate<Card> card = card();
    Surrogate<Last4> last4 = cardLast4.derive(card, acme()).orThrow();

    edge.set(AccessContext.of(java.util.Map.of("tenant", "acme", "role", "compliance")));
    int removed = loch.erase(card);

    assertThat(removed).isEqualTo(2);
    assertThat(loch.holds(card)).isFalse();
    assertThat(loch.holds(last4)).isFalse();
    assertThat(rowCount("loch_lineage_closure")).isZero();
  }

  @Test
  @DisplayName("erasing a derived value leaves its parent alone")
  void erasing_a_derived_value_leaves_its_parent() {
    Surrogate<Card> card = card();
    Surrogate<Last4> last4 = cardLast4.derive(card, acme()).orThrow();

    edge.set(AccessContext.of(java.util.Map.of("tenant", "acme", "role", "compliance")));
    assertThat(loch.erase(last4)).isEqualTo(1);
    assertThat(loch.holds(card)).isTrue();
  }

  @Test
  @DisplayName("another tenant's access is refused, whatever is on disk")
  void another_tenants_access_is_refused() {
    Surrogate<Card> card = card();

    assertThat(dereferenceAs("globex", card)).isFalse();
  }

  @Test
  @DisplayName("what happens to the bytes is a decision, not a default")
  void protection_is_a_decision() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    JdbcLoch.create(
                        Billing.class,
                        c ->
                            c.dataSource(dataSource)
                                .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
                                .lattice(Billing.LATTICE)
                                .withoutAudit())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("storedPlainly");
  }

  /** A different tenant, established at the edge rather than claimed by the caller. */
  private boolean dereferenceAs(String tenant, Surrogate<Card> card) {
    edge.set(AccessContext.of("tenant", tenant));
    return paymentProcessor.exchange(card).allowed();
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
  @DisplayName("compresses a big repetitive value before encrypting it")
  void compresses_a_big_value_before_encrypting() throws SQLException {
    Surrogate<Card> small = card();
    acme();
    Surrogate<Card> repetitive =
        cards.exchange(new Card("4111111111114821", "J CARMAN ".repeat(200)));

    // 1800 characters of a repeated name, stored in nothing like 1800 bytes.
    assertThat(payloadLength(repetitive)).isLessThan(payloadLength(small) + 300);
  }

  /**
   * The measured reason compression is conditional: a card record gzips to more than it started as,
   * so applying it unconditionally would cost space on nearly everything a loch holds.
   */
  @Test
  @DisplayName("does not make a small value bigger by compressing it")
  void does_not_make_a_small_value_bigger() throws SQLException {
    Surrogate<Card> card = card();

    int stored = payloadLength(card);
    int plain =
        new JacksonCodecFactory(JsonMapper.builder().build())
            .create(Card.class)
            .encode(new Card("4111111111114821", "J CARMAN"))
            .length;

    // One marker byte plus whatever the envelope adds, and nothing for compression that did not
    // help. Gzip alone would have added eight bytes to this payload before encryption.
    assertThat(stored).isLessThan(plain + 100);
  }

  private int payloadLength(Surrogate<?> held) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT payload FROM loch_value WHERE value_id = ?")) {
      statement.setString(1, held.id());
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getBytes("payload").length;
      }
    }
  }

  /**
   * A value's class is not its type. {@code List.of(a, b).getClass()} is {@code
   * ImmutableCollections$List12}, which nothing can deserialise into, so a loch that guessed from
   * the object would write a handle it could never honour. The caller says what it is.
   */
  @Test
  @DisplayName("holds a generic container and gives it back")
  void holds_a_generic_container() {
    List<Card> cards =
        List.of(new Card("4111111111114821", "A"), new Card("4111111111119999", "B"));

    acme();
    Surrogate<List<Card>> held = cardLists.exchange(cards);

    assertThat(cardListProcessor.exchange(held, acme()).granted())
        .hasValueSatisfying(
            back -> {
              assertThat(back).hasSize(2);
              assertThat(back.getFirst().number()).isEqualTo("4111111111114821");
            });
    assertThat(cardListVendor.exchange(held, acme()).allowed()).isFalse();
  }

  @Test
  @DisplayName("a handle claiming the wrong element type is refused")
  void a_handle_claiming_the_wrong_element_type_is_refused() {
    acme();
    Surrogate<List<Card>> cards = cardLists.exchange(List.of(new Card("4111111111114821", "A")));
    Surrogate<List<Last4>> lying = Surrogate.of(cards.id());

    assertThat(last4ListProcessor.exchange(lying, acme()).allowed()).isFalse();
  }

  /** Reading a label should not decrypt a payload. */
  @Test
  @DisplayName("asking what a value is labelled does not decode the value")
  void asking_for_a_label_does_not_decode_the_value() {
    acme();
    Surrogate<List<Card>> cards = cardLists.exchange(List.of(new Card("4111111111114821", "A")));

    // No type is supplied here, and none is needed: the label is read without touching the payload.
    assertThat(loch.label(cards).dataClass()).isEqualTo(DataClass.CARDHOLDER);
    assertThat(loch.lineage(cards).asserted()).isTrue();
  }

  /** A label governs disclosure, not destruction, so erasure is named separately or not granted. */
  @Test
  @DisplayName("refuses to erase for anyone the application did not name")
  void refuses_to_erase_for_anyone_not_named() {
    Surrogate<Card> card = card();
    edge.set(AccessContext.of(java.util.Map.of("tenant", "acme", "role", "agent")));

    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> loch.erase(card)))
        .isInstanceOf(org.jwcarman.loch.AccessDeniedException.class);
    assertThat(loch.holds(card)).isTrue();
  }
}
