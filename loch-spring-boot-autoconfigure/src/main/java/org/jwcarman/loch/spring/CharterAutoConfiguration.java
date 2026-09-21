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

import org.jwcarman.loch.AccessContextProvider;
import org.jwcarman.loch.Charter;
import org.jwcarman.loch.lattice.Axes;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * What every backing store needs, whatever it is backed by.
 *
 * <p>Which turns out to be nothing but settings. The store itself is never published: an
 * application needs the configuration, to declare what it allows, and the portals, to do the work.
 * It has no use for the thing holding the values -- reading a label, a lineage or an existence is
 * an authority in its own right, and handing out an object with all three on it would be giving
 * away three that nobody asked for.
 */
@AutoConfiguration
@EnableConfigurationProperties(CharterProperties.class)
public class CharterAutoConfiguration {
  /**
   * The charter itself, constructed here rather than by the application.
   *
   * <p>Ownership is the whole point. Whoever constructs a charter holds the thing that can seal it
   * and the thing that can erase through it, so the application says what its axes are and receives
   * a charter to declare on, never one it could bring into force or destroy with. That is the same
   * rule the portals follow: authority arrives because somebody handed it to you.
   *
   * <p>Conditional on the application having said what it asks about every value. Guessing a
   * vocabulary would be the worst thing this could do.
   */
  @Bean
  @ConditionalOnBean(Axes.class)
  @ConditionalOnMissingBean
  public Charter charter(Axes axes, java.util.Optional<AccessContextProvider> access) {
    Charter charter = new Charter(axes);
    // Identity is where an access comes from, not what this application allows, so it belongs with
    // the wiring rather than in the charter the application writes.
    access.ifPresent(charter::currentAccess);
    return charter;
  }
}
