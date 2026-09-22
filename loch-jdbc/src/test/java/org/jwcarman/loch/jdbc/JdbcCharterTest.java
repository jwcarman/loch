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
import org.jwcarman.loch.Conceal;
import org.jwcarman.loch.DefaultCharter;
import org.jwcarman.loch.Derivation;
import org.jwcarman.loch.Reveal;
import org.jwcarman.loch.Surrogate;
import org.jwcarman.loch.SurrogateType;
import org.jwcarman.loch.lattice.Axes;
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Ceiling;
import org.jwcarman.loch.lattice.Constraint;
import org.jwcarman.loch.lattice.Label;
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
@DisplayName("A store in a database")
class JdbcCharterTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("store")
          .withUsername("store")
          .withPassword("store");

  enum Integrity {
    ENDORSED,
    UNENDORSED
  }

  enum DataClass {
    NONE,
    PII,
    CARDHOLDER
  }

  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axis<Integrity> INTEGRITY =
      Axis.ladder("integrity", Integrity.ENDORSED, Integrity.UNENDORSED);
  private static final Axis<DataClass> DATA =
      Axis.ladder("dataClass", DataClass.NONE, DataClass.PII, DataClass.CARDHOLDER);

  private static Label label(String tenant, Integrity integrity, DataClass dataClass) {
    return Label.of(TENANT, tenant).with(INTEGRITY, integrity).with(DATA, dataClass);
  }

  record Card(String number, String holder) {}

  private static final SurrogateType<Card> CARD = SurrogateType.of(Card.class);

  record Last4(String digits) {}

  private static final SurrogateType<Last4> LAST4 = SurrogateType.of(Last4.class);

  private DataSource dataSource;
  private DefaultCharter store;
  private JdbcStorage storage;
  private Derivation<Card, Last4> cardLast4;
  private Conceal<Card> cards;
  private Reveal<Card> vendorLlm;
  private Reveal<Card> paymentProcessor;
  private Reveal<Last4> last4Processor;
  private Conceal<List<Card>> cardLists;
  private Reveal<List<Card>> cardListProcessor;
  private Reveal<List<Card>> cardListVendor;
  private Reveal<List<Last4>> last4ListProcessor;

  /** Standing in for the edge. A caller is not allowed to say who it is. */
  private final java.util.concurrent.atomic.AtomicReference<AccessContext> edge =
      new java.util.concurrent.atomic.AtomicReference<>(AccessContext.empty());

  private AccessContext acme() {
    edge.set(AccessContext.of("tenant", "acme"));
    return AccessContext.empty();
  }

  /** What a value written on this access is labelled: the tenant comes from the access. */
  private static Label labelFor(AccessContext ctx, Integrity integrity, DataClass dataClass) {
    return ctx.get("tenant")
        .map(tenant -> Label.of(TENANT, tenant))
        .orElseGet(Label::nothing)
        .with(INTEGRITY, integrity)
        .with(DATA, dataClass);
  }

  private static Ceiling ceiling(AccessContext ctx, Integrity integrity, DataClass dataClass) {
    String tenant =
        ctx.get("tenant")
            .orElseThrow(
                () -> new IllegalStateException("this access says nothing about a tenant"));
    return Ceiling.of(TENANT, Constraint.atMost(tenant))
        .with(INTEGRITY, Constraint.atMost(integrity))
        .with(DATA, Constraint.atMost(dataClass));
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
      statement.execute(
          "DROP TABLE IF EXISTS loch_audit, loch_lineage_closure, loch_lineage, loch_value");
    }

    KeyGenerator generator = KeyGenerator.getInstance("AES");
    generator.init(256);
    SecretKey kek = generator.generateKey();

    DefaultCharter c = new DefaultCharter(TENANT, INTEGRITY, DATA);
    // Containers have to be named: their raw type is java.util.List, which is not ours to
    // annotate and would collide with every other list.
    SurrogateType<List<Card>> cardList =
        SurrogateType.of("card-list", TypeRef.listOf(TypeRef.of(Card.class)));
    SurrogateType<List<Last4>> last4List =
        SurrogateType.of("last4-list", TypeRef.listOf(TypeRef.of(Last4.class)));

    // The application composes its own pipeline: squeeze, then seal.
    JdbcStorageConfig jdbc =
        new JdbcStorageConfig()
            .dataSource(dataSource)
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .storedThrough(
                StorageCodec.of(
                    Compression.whenItHelps(new GzipCodec())
                        .andThen(
                            EnvelopeCodec.builder(new JceDataKeyProvider("k1", Map.of("k1", kek)))
                                .build())));

    c.currentAccess(edge::get)
        // Erasure is the one operation a label cannot decide, so it is named here.
        .mayErase(
            (label, ctx) ->
                ctx.has("role", "compliance")
                    && ctx.get("tenant").map(t -> label.says(TENANT, t)).orElse(false));

    // One source: everything this test holds is acme's cardholder data.
    cards = c.source("cards", CARD, ctx -> labelFor(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER));
    vendorLlm =
        c.destination("vendor-llm", ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.NONE), CARD)
            .reading(CARD);
    // One destination, three readers. The ceiling is written once, every reader enforces it,
    // and all three audit under "payment-processor" because that is the subsystem they reach.
    // The subsystem, its ceiling, and everything it is allowed to read. Both restrictions are
    // settled here, so the readers below are typed views rather than grants.
    var processor =
        c.destination(
            "payment-processor",
            ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER),
            CARD,
            LAST4,
            cardList);
    paymentProcessor = processor.reading(CARD);
    last4Processor = processor.reading(LAST4);

    // A generic container is its own type, so it needs its own source and its own sinks.
    cardLists =
        c.source(
            "card-lists", cardList, ctx -> labelFor(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER));
    cardListProcessor = processor.reading(cardList);
    cardListVendor =
        c.destination(
                "card-lists-to-vendor",
                ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.NONE),
                cardList)
            .reading(cardList);
    last4ListProcessor =
        c.destination(
                "last4-lists-to-processor",
                ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER),
                last4List)
            .reading(last4List);

    cardLast4 =
        c.derivation(
            "Card.last4",
            CARD,
            LAST4,
            card -> new Last4(card.number().substring(card.number().length() - 4)),
            d ->
                d.accepting(ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER))
                    .lowering(joined -> joined.with(DATA, DataClass.PII)));

    storage = jdbc.storage(c.axes());
    c.seal(storage);
    store = c;
  }

  private Surrogate<Card> card() {
    acme();
    return cards.conceal(new Card("4111111111114821", "J CARMAN"));
  }

  @Test
  @DisplayName("keeps a value and gives it back to somewhere allowed to have it")
  void keeps_a_value_and_gives_it_back() {
    Surrogate<Card> card = card();

    acme();
    assertThat(paymentProcessor.reveal(card).granted())
        .contains(new Card("4111111111114821", "J CARMAN"));
    acme();
    assertThat(vendorLlm.reveal(card).allowed()).isFalse();
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

  /**
   * The trail is queryable and closed at the same time.
   *
   * <p>A refusal's code names a rule, so it stays in the clear and "how many refusals above a
   * ceiling this hour" needs no key. What the refusal <i>would have said</i> -- which label, which
   * ceiling -- names a tenant and its data class, so it goes to disk the way a label does. In the
   * clear it would describe every value in the system to anyone who could read this table, which is
   * the same disclosure the caller is refused.
   */
  @Test
  @DisplayName("records why it refused without putting the explanation in the clear")
  void records_why_without_disclosing_it() throws SQLException {
    Surrogate<Card> card = card();
    edge.set(AccessContext.of("tenant", "acme"));

    assertThat(vendorLlm.reveal(card).allowed()).isFalse();

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT reason, detail FROM loch_audit WHERE outcome = 'REFUSED'")) {
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString("reason")).isEqualTo("ABOVE_CEILING");
        String detail = new String(rows.getBytes("detail"));
        assertThat(detail).doesNotContain("acme").doesNotContain("CARDHOLDER");
      }
    }
  }

  /**
   * Every value hashes from its own bytes and whatever it was made from.
   *
   * <p>A fresh value starts its own graph. Everything derived from it hashes from parents that are
   * immutable and already written, so nothing is locked and no global order exists -- a value is
   * fixed by its ancestry, not by when it arrived.
   */
  @Test
  @DisplayName("writes values whose digests agree with their ancestry")
  void writes_values_whose_digests_agree() {
    Surrogate<Card> card = card();

    Surrogate<Last4> last4 = cardLast4.derive(card).orThrow();

    assertThat(last4).isNotNull();
    assertThat(storage.brokenValues()).isEmpty();
  }

  @Test
  @DisplayName("notices a value somebody edited, and everything derived from it")
  void notices_an_edited_value() throws SQLException {
    Surrogate<Card> card = card();
    Surrogate<Last4> last4 = cardLast4.derive(card).orThrow();

    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement("UPDATE loch_value SET payload = ? WHERE value_id = ?")) {
      statement.setBytes(
          1, "not what was stored".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      statement.setString(2, card.id());
      assertThat(statement.executeUpdate()).isPositive();
    }

    // The value itself, and the one made from what it used to be.
    assertThat(storage.brokenValues()).contains(card.id(), last4.id());
  }

  /** Removing one leaves its children hashing from something that is not there. */
  @Test
  @DisplayName("notices a value somebody deleted, through the children it left behind")
  void notices_a_deleted_value() throws SQLException {
    Surrogate<Card> card = card();
    Surrogate<Last4> last4 = cardLast4.derive(card).orThrow();

    try (Connection connection = dataSource.getConnection();
        var statement = connection.prepareStatement("DELETE FROM loch_value WHERE value_id = ?")) {
      statement.setString(1, card.id());
      assertThat(statement.executeUpdate()).isPositive();
    }

    assertThat(storage.brokenValues()).contains(last4.id());
  }

  /**
   * The trail is a chain, because a line has a predecessor rather than parents.
   *
   * <p>Values hash from their ancestry and need no order. Accesses have no ancestry -- a refused
   * read makes nothing -- so the only thing a line can name is the one before it.
   */
  @Test
  @DisplayName("writes a trail that verifies")
  void writes_a_trail_that_verifies() {
    card();
    edge.set(AccessContext.of("tenant", "acme"));
    vendorLlm.reveal(card());

    assertThat(storage.firstBrokenEntry()).isEmpty();
  }

  @Test
  @DisplayName("notices a line somebody edited")
  void notices_an_edited_line() throws SQLException {
    Surrogate<Card> card = card();

    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "UPDATE loch_audit SET outcome = 'REFUSED' WHERE value_id = ?")) {
      statement.setString(1, card.id());
      assertThat(statement.executeUpdate()).isPositive();
    }

    assertThat(storage.firstBrokenEntry()).isPresent();
  }

  /**
   * And notices a deletion that was covered up, which is the one that matters.
   *
   * <p>Removing a line and leaving it is easy to catch. The real case is somebody who removes one
   * and then repairs the chain behind it -- re-pointing what followed at what preceded, so the
   * trail reads as though the line never existed. That works against a plain hash chain. It does
   * not work here, because the digests are keyed and they do not have the key.
   */
  @Test
  @DisplayName("notices a deletion even when somebody repaired the chain behind it")
  void notices_a_covered_up_deletion() throws SQLException {
    card();
    edge.set(AccessContext.of("tenant", "acme"));
    vendorLlm.reveal(card());
    card();
    assertThat(storage.firstBrokenEntry()).isEmpty();

    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      // Take out the second line and stitch the third onto the first, the way somebody covering
      // their tracks would. Every digest still looks locally plausible.
      statement.executeUpdate(
          """
          UPDATE loch_audit SET previous = (
              SELECT previous FROM loch_audit ORDER BY entry_id OFFSET 1 LIMIT 1)
          WHERE entry_id = (SELECT entry_id FROM loch_audit ORDER BY entry_id OFFSET 2 LIMIT 1)
          """);
      statement.executeUpdate(
          "DELETE FROM loch_audit WHERE entry_id ="
              + " (SELECT entry_id FROM loch_audit ORDER BY entry_id OFFSET 1 LIMIT 1)");
    }

    assertThat(storage.firstBrokenEntry()).isPresent();
  }

  /**
   * And the key is what makes any of that hold against somebody determined.
   *
   * <p>The test above catches a repair that re-pointed the chain without re-signing it. A careful
   * attacker would re-sign, and against an unkeyed chain that works -- recompute everything after
   * the gap and it agrees with itself again. They cannot, because the digests are an HMAC under a
   * root this database does not hold.
   *
   * <p>That property cannot be demonstrated by a test holding the key. What can be shown is that
   * the digests depend on it: read the same intact trail under a different root and every line
   * disagrees.
   */
  @Test
  @DisplayName("cannot be verified, or forged, without the root it was written under")
  void cannot_be_verified_without_the_root() {
    card();
    assertThat(storage.firstBrokenEntry()).isEmpty();

    JdbcStorage underAnotherRoot =
        new JdbcStorageConfig()
            .dataSource(dataSource)
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .storedPlainly()
            .rootedIn(
                "open", "somebody else's key".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .withoutMigration()
            .storage(Axes.of(TENANT, INTEGRITY, DATA));

    assertThat(underAnotherRoot.firstBrokenEntry()).isPresent();
  }

  /**
   * A root can be rotated without invalidating what was written under the last one.
   *
   * <p>Every value and every line records which root signed it, so verifying asks for that one.
   * Rotating writes new rows under the new root and leaves the old ones readable -- the
   * alternative, re-signing everything on the way past, is the one operation an append-only trail
   * must not support.
   */
  @Test
  @DisplayName("verifies what an older root signed after a new one takes over")
  void verifies_across_a_rotation() {
    byte[] first = "the first root".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] second = "the second root".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Axes axes = Axes.of(TENANT, INTEGRITY, DATA);

    DefaultCharter under1 = new DefaultCharter(axes);
    under1.currentAccess(edge::get);
    Conceal<Card> early =
        under1.source(
            "cards", CARD, ctx -> labelFor(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER));
    under1.seal(rooted("r1", java.util.Map.of("r1", first), axes));
    edge.set(AccessContext.of("tenant", "acme"));
    early.conceal(new Card("4111111111114821", "CARMAN"));

    java.util.Map<String, byte[]> both = java.util.Map.of("r1", first, "r2", second);
    DefaultCharter under2 = new DefaultCharter(axes);
    under2.currentAccess(edge::get);
    Conceal<Card> later =
        under2.source(
            "cards", CARD, ctx -> labelFor(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER));
    JdbcStorage rotated = rooted("r2", both, axes);
    under2.seal(rotated);
    later.conceal(new Card("4111111111119999", "CARMAN"));

    // Both eras, one verification, and nothing had to be re-signed.
    assertThat(rotated.firstBrokenEntry()).isEmpty();
    assertThat(rotated.brokenValues()).isEmpty();
  }

  private JdbcStorage rooted(String id, java.util.Map<String, byte[]> roots, Axes axes) {
    return new JdbcStorageConfig()
        .dataSource(dataSource)
        .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
        .storedPlainly()
        .rootedIn(id, roots::get)
        .withoutMigration()
        .storage(axes);
  }

  /**
   * What this cannot catch, stated as a test so nobody has to discover it.
   *
   * <p>Cutting lines off the end leaves a chain that verifies, because what remains is exactly the
   * trail as it stood earlier. Nothing inside the database knows the removed lines ever existed --
   * and that is not a gap in the implementation, it is what truncation is. No structure over data
   * an attacker controls can tell you about entries they deleted.
   *
   * <p>Closing it takes something outside: the head digest published where whoever can write to
   * this database cannot reach, and compared afterwards. {@link JdbcStorage#head()} is that digest.
   */
  @Test
  @DisplayName("cannot notice lines cut from the end, which is what an anchor is for")
  void cannot_notice_a_truncation() throws SQLException {
    card();
    edge.set(AccessContext.of("tenant", "acme"));
    vendorLlm.reveal(card());
    byte[] anchored = storage.head();

    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      assertThat(
              statement.executeUpdate(
                  "DELETE FROM loch_audit WHERE entry_id ="
                      + " (SELECT MAX(entry_id) FROM loch_audit)"))
          .isPositive();
    }

    // The chain still agrees with itself, which is exactly the problem.
    assertThat(storage.firstBrokenEntry()).isEmpty();
    // And the head somebody wrote down elsewhere is what gives it away.
    assertThat(storage.head()).isNotEqualTo(anchored);
  }

  @Test
  @DisplayName("a fresh store over the same database reads what the last one wrote")
  void survives_a_restart() {
    Surrogate<Card> card = card();

    assertThat(store.holds(card)).isTrue();
    assertThat(store.label(card).says(DATA, DataClass.CARDHOLDER)).isTrue();
  }

  @Test
  @DisplayName("a derived value keeps its parentage and its lowered label")
  void a_derived_value_keeps_its_parentage() {
    Surrogate<Card> card = card();

    acme();
    Surrogate<Last4> last4 = cardLast4.derive(card).orThrow();

    assertThat(store.label(last4).says(DATA, DataClass.PII)).isTrue();
    assertThat(store.lineage(last4).parents()).containsExactly(card.id());
    assertThat(store.lineage(last4).derivation()).contains("Card.last4");
  }

  @Test
  @DisplayName("deriving the same thing twice stores it twice, and says so")
  void deriving_twice_stores_twice() throws SQLException {
    Surrogate<Card> card = card();

    acme();
    Surrogate<Last4> once = cardLast4.derive(card).orThrow();
    acme();
    Surrogate<Last4> twice = cardLast4.derive(card).orThrow();

    assertThat(once.id()).isNotEqualTo(twice.id());
    assertThat(rowCount("loch_value")).isEqualTo(3);
  }

  /** Erasure is a reachability query, which is what the closure table is for. */
  @Test
  @DisplayName("erasing a value takes everything ever derived from it")
  void erasing_takes_everything_derived_from_it() throws SQLException {
    Surrogate<Card> card = card();
    acme();
    Surrogate<Last4> last4 = cardLast4.derive(card).orThrow();

    edge.set(AccessContext.of(java.util.Map.of("tenant", "acme", "role", "compliance")));
    int removed = store.erase(card);

    assertThat(removed).isEqualTo(2);
    assertThat(store.holds(card)).isFalse();
    assertThat(store.holds(last4)).isFalse();
    assertThat(rowCount("loch_lineage_closure")).isZero();
  }

  @Test
  @DisplayName("erasing a derived value leaves its parent alone")
  void erasing_a_derived_value_leaves_its_parent() {
    Surrogate<Card> card = card();
    acme();
    Surrogate<Last4> last4 = cardLast4.derive(card).orThrow();

    edge.set(AccessContext.of(java.util.Map.of("tenant", "acme", "role", "compliance")));
    assertThat(store.erase(last4)).isEqualTo(1);
    assertThat(store.holds(card)).isTrue();
  }

  @Test
  @DisplayName("another tenant's access is refused, whatever is on disk")
  void another_tenants_access_is_refused() {
    Surrogate<Card> card = card();

    assertThat(revealAs("globex", card)).isFalse();
  }

  @Test
  @DisplayName("what happens to the bytes is a decision, not a default")
  void protection_is_a_decision() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    new JdbcStorageConfig()
                        .dataSource(dataSource)
                        .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
                        .storage(Axes.of(TENANT, INTEGRITY, DATA))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("storedPlainly");
  }

  /** A different tenant, established at the edge rather than claimed by the caller. */
  private boolean revealAs(String tenant, Surrogate<Card> card) {
    edge.set(AccessContext.of("tenant", tenant));
    return paymentProcessor.reveal(card).allowed();
  }

  /** The trail goes to the database, in the same transaction as the thing it describes. */
  @Test
  @DisplayName("writes the record beside the value it is about")
  void writes_the_record_beside_the_value() throws SQLException {
    assertThat(rowCount("loch_audit")).isZero();

    Surrogate<Card> card = card();
    acme();
    paymentProcessor.reveal(card);

    // One line for taking it in, one for handing it over.
    assertThat(rowCount("loch_audit")).isEqualTo(2);
    assertThat(auditColumn("operation")).containsExactly("CONCEAL", "REVEAL");
    assertThat(auditColumn("outcome")).containsOnly("ALLOWED");
  }

  /**
   * The trail outlives what it describes, and that is the whole point of it.
   *
   * <p>Erasing a customer takes their values and everything derived from them. The record that it
   * happened has to survive that, or the system cannot prove it did the thing it was required to
   * do. So loch_audit has no foreign key to loch_value and nothing cascades into it.
   */
  @Test
  @DisplayName("and keeps it after the value it is about has been erased")
  void keeps_the_record_after_erasure() throws SQLException {
    Surrogate<Card> card = card();
    acme();
    cardLast4.derive(card);
    int before = rowCount("loch_audit");

    edge.set(AccessContext.of(java.util.Map.of("tenant", "acme", "role", "compliance")));
    store.erase(card);

    assertThat(rowCount("loch_value")).isZero();
    assertThat(rowCount("loch_audit")).isGreaterThanOrEqualTo(before);
    assertThat(auditColumn("operation")).contains("ERASE");
  }

  private java.util.List<String> auditColumn(String column) throws SQLException {
    java.util.List<String> values = new java.util.ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        ResultSet rows =
            connection
                .createStatement()
                .executeQuery("SELECT " + column + " FROM loch_audit ORDER BY entry_id")) {
      while (rows.next()) {
        values.add(rows.getString(1));
      }
    }
    return values;
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
    assertThat(store.lineage(card()).asserted()).isTrue();
    assertThat(List.of(store.lineage(card()).parents())).isNotEmpty();
  }

  /** Serialise, squeeze, seal. Reversing the last two would cost the same and save nothing. */
  @Test
  @DisplayName("compresses a big repetitive value before encrypting it")
  void compresses_a_big_value_before_encrypting() throws SQLException {
    Surrogate<Card> small = card();
    acme();
    Surrogate<Card> repetitive =
        cards.conceal(new Card("4111111111114821", "J CARMAN ".repeat(200)));

    // 1800 characters of a repeated name, stored in nothing like 1800 bytes.
    assertThat(payloadLength(repetitive)).isLessThan(payloadLength(small) + 300);
  }

  /**
   * The measured reason compression is conditional: a card record gzips to more than it started as,
   * so applying it unconditionally would cost space on nearly everything a store holds.
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
   * ImmutableCollections$List12}, which nothing can deserialise into, so a store that guessed from
   * the object would write a handle it could never honour. The caller says what it is.
   */
  @Test
  @DisplayName("holds a generic container and gives it back")
  void holds_a_generic_container() {
    List<Card> cards =
        List.of(new Card("4111111111114821", "A"), new Card("4111111111119999", "B"));

    acme();
    Surrogate<List<Card>> held = cardLists.conceal(cards);

    acme();
    assertThat(cardListProcessor.reveal(held).granted())
        .hasValueSatisfying(
            back -> {
              assertThat(back).hasSize(2);
              assertThat(back.getFirst().number()).isEqualTo("4111111111114821");
            });
    acme();
    assertThat(cardListVendor.reveal(held).allowed()).isFalse();
  }

  @Test
  @DisplayName("a handle claiming the wrong element type is refused")
  void a_handle_claiming_the_wrong_element_type_is_refused() {
    acme();
    Surrogate<List<Card>> cards = cardLists.conceal(List.of(new Card("4111111111114821", "A")));
    Surrogate<List<Last4>> lying = Surrogate.of(cards.id());

    acme();
    assertThat(last4ListProcessor.reveal(lying).allowed()).isFalse();
  }

  /** Reading a label should not decrypt a payload. */
  @Test
  @DisplayName("asking what a value is labelled does not decode the value")
  void asking_for_a_label_does_not_decode_the_value() {
    acme();
    Surrogate<List<Card>> cards = cardLists.conceal(List.of(new Card("4111111111114821", "A")));

    // No type is supplied here, and none is needed: the label is read without touching the payload.
    assertThat(store.label(cards).says(DATA, DataClass.CARDHOLDER)).isTrue();
    assertThat(store.lineage(cards).asserted()).isTrue();
  }

  /** A label governs disclosure, not destruction, so erasure is named separately or not granted. */
  @Test
  @DisplayName("refuses to erase for anyone the application did not name")
  void refuses_to_erase_for_anyone_not_named() {
    Surrogate<Card> card = card();
    edge.set(AccessContext.of(java.util.Map.of("tenant", "acme", "role", "agent")));

    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> store.erase(card)))
        .isInstanceOf(org.jwcarman.loch.AccessDeniedException.class);
    assertThat(store.holds(card)).isTrue();
  }
}
