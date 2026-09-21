/*
 * Copyright © ${year} James Carman
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
package org.jwcarman.loch.example.billing;

import org.jwcarman.loch.AccessDeniedException;
import org.jwcarman.loch.HandleId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP surface.
 *
 * <p>References cross the wire as ids, which is all a {@link HandleId} is. A client can hold one,
 * log it, put it in a URL and hand it back tomorrow; none of that is permission to read anything.
 */
@RestController
@RequestMapping("/disputes")
public class DisputeController {

  private final DisputeService disputes;

  public DisputeController(DisputeService disputes) {
    this.disputes = disputes;
  }

  public record Raise(String from, String body) {}

  public record Reference(String id) {}

  @PostMapping
  public Reference raise(@RequestHeader("X-Tenant") String tenant, @RequestBody Raise raise) {
    return new Reference(
        disputes.receive(tenant, new Domain.Mail(raise.from(), raise.body())).value());
  }

  @GetMapping("/{id}/mentions")
  public boolean mentions(@PathVariable String id, @RequestParam String text) {
    return disputes.mentions(new HandleId(id), text);
  }

  @PostMapping("/{id}/confirm")
  public Reference confirm(@PathVariable String id) {
    return new Reference(disputes.confirm(new HandleId(id)).value());
  }

  @GetMapping("/{id}/card")
  public Domain.Last4 card(@PathVariable String id) {
    return disputes.cardForApproval(new HandleId(id));
  }

  @GetMapping("/{id}")
  public Domain.Invoice invoice(@PathVariable String id) {
    return disputes.forSupportScreen(new HandleId(id));
  }

  @PostMapping("/{id}/refund")
  public String refund(@PathVariable String id) {
    return disputes.refund(new HandleId(id));
  }

  /** A refusal is a 403, and says which gate said no without saying what was behind it. */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<String> refused(AccessDeniedException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
  }
}
