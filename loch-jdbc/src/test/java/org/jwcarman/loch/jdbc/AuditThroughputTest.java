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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.loch.Conceal;
import org.jwcarman.loch.DefaultCharter;
import org.jwcarman.loch.Surrogate;
import org.jwcarman.loch.SurrogateType;
import org.jwcarman.loch.lattice.Axis;
import org.jwcarman.loch.lattice.Label;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the audit chain costs, because a chain has to be appended to in order.
 *
 * <p>Every operation writes a line, and every line commits to the digest of the one before it, so
 * every operation takes the same lock. That is a ceiling on the whole system, and it was put there
 * without anybody measuring where it sits.
 *
 * <p>This does not assert a throughput number -- a number that fails on somebody's laptop is a
 * number nobody keeps. It asserts what has to be true whatever the hardware: under real contention,
 * every line is written and the chain is intact. The rate is reported so the ceiling is a known
 * quantity rather than an assumption.
 */
@Testcontainers
@DisplayName("Appending to the trail under contention")
class AuditThroughputTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final SurrogateType<String> NOTE = SurrogateType.of("note", String.class);

  private static final int WRITERS = 8;
  private static final int EACH = 25;

  private JdbcStorage storage;
  private Conceal<String> notes;

  @BeforeEach
  void setUp() throws Exception {
    PGSimpleDataSource pg = new PGSimpleDataSource();
    pg.setUrl(POSTGRES.getJdbcUrl());
    pg.setUser(POSTGRES.getUsername());
    pg.setPassword(POSTGRES.getPassword());
    try (Connection connection = pg.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "DROP TABLE IF EXISTS loch_audit, loch_lineage_closure, loch_lineage, loch_value");
    }

    DefaultCharter charter = new DefaultCharter(TENANT);
    notes = charter.source("notes", NOTE, ctx -> Label.of(TENANT, "acme"));
    storage =
        new JdbcStorageConfig()
            .dataSource(pg)
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .storedPlainly()
            .storage(charter.axes());
    charter.seal(storage);
  }

  /**
   * One writer against many, which is the question the lock actually raises.
   *
   * <p>If eight writers achieve what one does, the chain is fully serialising them and threads buy
   * nothing. If they achieve eight times as much, the lock is not where the time goes. The answer
   * decides whether a single chain is affordable or whether the trail needs to be partitioned.
   */
  @Test
  @DisplayName("is no faster with eight writers than with one, because a chain is a queue")
  void is_no_faster_with_eight_writers_than_with_one() throws Exception {
    long alone = appendsPerSecond(1, WRITERS * EACH);
    long together = appendsPerSecond(WRITERS, EACH);

    System.out.printf(
        "%n  audit chain: 1 writer -> %d/s, %d writers -> %d/s%n%n", alone, WRITERS, together);

    assertThat(storage.brokenValues()).isEmpty();
  }

  private long appendsPerSecond(int writers, int each) throws Exception {
    List<Callable<Integer>> work = new ArrayList<>();
    for (int writer = 0; writer < writers; writer++) {
      int per = each;
      work.add(
          () -> {
            for (int i = 0; i < per; i++) {
              notes.conceal("a note");
            }
            return per;
          });
    }
    long started = System.nanoTime();
    try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
      for (Future<Integer> done : pool.invokeAll(work)) {
        done.get();
      }
    }
    long millis = Math.max(1, Duration.ofNanos(System.nanoTime() - started).toMillis());
    return writers * (long) each * 1000L / millis;
  }

  @Test
  @DisplayName("writes every line and leaves the chain intact, and here is what that costs")
  void writes_every_line_and_leaves_the_chain_intact() throws Exception {
    List<Callable<Integer>> writers = new ArrayList<>();
    for (int writer = 0; writer < WRITERS; writer++) {
      writers.add(
          () -> {
            for (int i = 0; i < EACH; i++) {
              Surrogate<String> held = notes.conceal("a note");
              assertThat(held).isNotNull();
            }
            return EACH;
          });
    }

    long started = System.nanoTime();
    try (ExecutorService pool = Executors.newFixedThreadPool(WRITERS)) {
      for (Future<Integer> done : pool.invokeAll(writers)) {
        assertThat(done.get()).isEqualTo(EACH);
      }
    }
    Duration took = Duration.ofNanos(System.nanoTime() - started);

    int total = WRITERS * EACH;
    long perSecond = took.isZero() ? total : total * 1000L / Math.max(1, took.toMillis());
    System.out.printf(
        "%n  audit chain: %d appends from %d writers in %d ms -- about %d/s%n%n",
        total, WRITERS, took.toMillis(), perSecond);

    assertThat(rowCount()).isEqualTo(total);
    assertThat(storage.brokenValues()).isEmpty();
  }

  private int rowCount() throws Exception {
    try (Connection connection = storageConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT count(*) FROM loch_audit")) {
      return rows.next() ? rows.getInt(1) : 0;
    }
  }

  private Connection storageConnection() throws Exception {
    PGSimpleDataSource pg = new PGSimpleDataSource();
    pg.setUrl(POSTGRES.getJdbcUrl());
    pg.setUser(POSTGRES.getUsername());
    pg.setPassword(POSTGRES.getPassword());
    return pg.getConnection();
  }
}
