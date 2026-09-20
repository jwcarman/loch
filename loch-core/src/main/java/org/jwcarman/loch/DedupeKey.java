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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

/**
 * Recognises work already done, so repeating it does not store a second copy.
 *
 * <p><b>Deduplication, and nothing grander.</b> An earlier version of this claimed to make replay
 * safe, which was wrong: storage is durable, so a re-run finds the first value still there and the
 * first handle still resolving. Deriving again would simply write a duplicate. That is wasted space
 * rather than a correctness problem, and this exists to avoid the waste.
 *
 * <p><b>An index, never an identifier.</b> A key computed from its inputs is computable by anyone
 * who knows them, and an identifier a caller can derive rather than be given is one they can use to
 * ask questions -- does this exist, what is it labelled -- about values nobody handed them. Handles
 * stay random; this sits beside them in storage, where no caller sees it.
 *
 * <p>The version is in the key on purpose: change what a derivation does and it stops matching, so
 * old values are not silently reused under new behaviour.
 */
final class DedupeKey {

  private DedupeKey() {}

  static String of(List<HeldId> parents, String derivationId, int version) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (HeldId parent : parents) {
        digest.update(parent.value().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
      }
      digest.update(derivationId.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(Integer.toString(version).getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every Java platform", e);
    }
  }
}
