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

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

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
@EnableConfigurationProperties(SurrogateStoreProperties.class)
public class SurrogateStoreAutoConfiguration {}
