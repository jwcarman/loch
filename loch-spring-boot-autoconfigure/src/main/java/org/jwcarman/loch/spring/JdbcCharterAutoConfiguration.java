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
package org.jwcarman.loch.spring;

import javax.sql.DataSource;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.loch.AccessContextProvider;
import org.jwcarman.loch.Charter;
import org.jwcarman.loch.jdbc.JdbcStorage;
import org.jwcarman.loch.jdbc.JdbcStorageConfig;
import org.jwcarman.loch.jdbc.StorageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/**
 * A store kept in a database, wired when that module is on the classpath.
 *
 * <p>Takes the lifecycle off the application. Building a store is the moment its access space is
 * fixed: every capability declared beforehand is attached, and anything declared afterwards reaches
 * nothing. So the build must come last, after every bean that declares a portal has been
 * constructed -- which by hand means one class orchestrating the whole startup.
 *
 * <p>Here it is Spring's job. Declare a {@link JdbcStorageConfig} bean saying what your application
 * allows, take it as a parameter wherever you declare portals, and this supplies the plumbing and
 * builds the store once the context has finished making singletons.
 *
 * <p>Everything it supplies is {@link ConditionalOnMissingBean}, so any of it can be replaced by
 * declaring your own: the serialisation, the encryption, or the data source itself.
 */
@AutoConfiguration(
    after = CharterAutoConfiguration.class,
    // Named rather than referenced: Boot 4 moved this into its own module, and naming it keeps
    // that module off our compile path.
    afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass({JdbcStorage.class, DataSource.class})
public class JdbcCharterAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(JdbcCharterAutoConfiguration.class);

  /** How values are serialised, before they are compressed and sealed. */
  @Bean
  @ConditionalOnMissingBean
  public CodecFactory surrogateCodecFactory() {
    return new JacksonCodecFactory(JsonMapper.builder().build());
  }

  /**
   * Builds it, last.
   *
   * <p>{@link SmartInitializingSingleton} runs once the context has finished creating singletons,
   * which is the first moment every portal has been declared and the last moment a store can be
   * built before one is used.
   *
   * <p>Conditional on the application having declared what it allows. Without that there is no
   * policy to build a store from, and guessing one would be the worst thing this could do.
   */
  @Bean
  @ConditionalOnBean({Charter.class, StorageCodec.class})
  public SmartInitializingSingleton surrogateStoreBuilder(
      Charter config,
      java.util.Optional<AccessContextProvider> access,
      DataSource dataSource,
      CodecFactory codecs,
      StorageCodec storageCodec,
      CharterProperties properties) {
    // A wildcard rather than type variables: a generic @Bean method gives Spring an injection
    // point it cannot resolve, and the bean silently never matches.
    // Declared as a bean rather than set on the configuration: identity is where it comes from,
    // not what this application allows, so it belongs with the wiring.
    access.ifPresent(config::currentAccess);
    return () -> build(config, dataSource, codecs, storageCodec, properties);
  }

  /** Seals the charter the application wrote to the storage this module supplies. */
  private static void build(
      Charter charter,
      DataSource dataSource,
      CodecFactory codecs,
      StorageCodec storageCodec,
      CharterProperties properties) {
    // The application said what it allows; this says where it goes. Neither knows the other.
    JdbcStorageConfig jdbc =
        new JdbcStorageConfig().dataSource(dataSource).codecs(codecs).storedThrough(storageCodec);
    if (!properties.isMigrate()) {
      jdbc.withoutMigration();
    }
    // One transition, and every portal the application is already holding comes into force.
    charter.seal(jdbc.storage(charter.axes()));
    if (properties.isLogManifest()) {
      log.info("\n{}", charter.manifest());
    }
  }
}
