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
package org.jwcarman.loch;

/**
 * A function of three arguments.
 *
 * <p>Java supplies {@link java.util.function.Function} and {@link java.util.function.BiFunction}
 * and then stops, so a derivation reading more than two values has nothing to be declared as.
 * Reactor ships {@code Function3} through {@code Function8} for the same reason. These go to five,
 * which is where the useful cases end and where {@code Fold} takes over for many values of one
 * type.
 */
@FunctionalInterface
public interface Function3<I1, I2, I3, O> {
  O apply(I1 one, I2 two, I3 three);
}
