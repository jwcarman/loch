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

import org.jwcarman.loch.Charter;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The charter endpoint, when an application has Actuator.
 *
 * <p>Its own auto-configuration rather than a method on the other one, because a {@code @Bean}
 * method returning a type that imports Actuator cannot be loaded at all when Actuator is absent --
 * a condition on the method is too late. Conditions on a class are evaluated from bytecode, which
 * is the only way to guard something that cannot be loaded.
 */
@AutoConfiguration(after = CharterAutoConfiguration.class)
@ConditionalOnClass(Endpoint.class)
public class CharterEndpointAutoConfiguration {

  @Bean
  @ConditionalOnBean(Charter.class)
  @ConditionalOnAvailableEndpoint(endpoint = CharterEndpoint.class)
  @ConditionalOnMissingBean
  public CharterEndpoint charterEndpoint(Charter charter) {
    return new CharterEndpoint(charter);
  }
}
