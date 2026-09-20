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
 * Names a deterministic derivation's result after what produced it.
 *
 * <p>Three things follow, and the first is the one that matters. <b>Replay is idempotent</b>: an
 * agent re-running a turn derives the same handle it derived the first time, so the story it
 * already wrote still refers to something real. A fresh id per run would orphan the first one.
 *
 * <p>Deriving the same thing twice is also free rather than duplicated, and the id is itself
 * evidence of what produced it. The version is in the hash on purpose: changing an implementation
 * gives new handles rather than silently reinterpreting values derived under the old behaviour.
 *
 * <p>This is a name, never an instruction. Nothing about presenting one causes a derivation to run;
 * an unknown id is simply unknown, which is what keeps handles inert.
 */
final class ContentAddress {

  private ContentAddress() {}

  static HeldId of(List<HeldId> parents, String derivationId, int version) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (HeldId parent : parents) {
        digest.update(parent.value().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
      }
      digest.update(derivationId.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(Integer.toString(version).getBytes(StandardCharsets.UTF_8));
      return new HeldId(
          "loch_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest()));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every Java platform", e);
    }
  }
}
