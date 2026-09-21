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
 * How a type gets its stored name when nobody gave it one.
 *
 * <p>Takes a {@link Class} and nothing else, deliberately. A generic container has no class to hang
 * a name on -- {@code List<Card>}'s raw type is {@code java.util.List}, which is not yours to
 * annotate and would collide with {@code List<Last4>} -- so those are named explicitly or not at
 * all. Anything a strategy could invent for them would be a guess.
 *
 * <p><b>Whatever this returns is permanent.</b> Names are written beside every value and compared
 * when one is read back, so swapping strategies renames every type at once and orphans everything
 * already stored. That is a migration, not a configuration change, and it does not look like one.
 *
 * <p>Because it sees the whole class, a strategy can read an annotation of your own and keep this
 * library out of your domain model entirely. {@link #standard()} reads ours, {@link SurrogateName},
 * and falls back to the kebab-cased simple name.
 */
@FunctionalInterface
public interface SurrogateNamingStrategy {

  /** The name values of this type are stored under. */
  String nameFor(Class<?> type);

  /**
   * Reads {@link SurrogateName} if it is there, otherwise the kebab-cased simple name.
   *
   * <p>{@code DisputeClaim} becomes {@code dispute-claim}, {@code Last4} becomes {@code last4}, and
   * a nested {@code Domain.Invoice} becomes {@code invoice} -- the enclosing type is dropped, which
   * is why two nested types with the same simple name collide and are refused at startup.
   */
  static SurrogateNamingStrategy standard() {
    return type -> {
      SurrogateName declared = type.getAnnotation(SurrogateName.class);
      if (declared != null) {
        return declared.value();
      }
      return kebab(type.getSimpleName());
    };
  }

  /** Splits on the boundaries a reader would, including the end of an acronym. */
  private static String kebab(String simpleName) {
    // Zero-width boundaries rather than captured groups: nothing to backtrack over.
    return simpleName
        .replaceAll("(?<=[A-Z])(?=[A-Z][a-z])", "-")
        .replaceAll("(?<=[a-z0-9])(?=[A-Z])", "-")
        .toLowerCase(java.util.Locale.ROOT);
  }
}
