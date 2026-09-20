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

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jwcarman.loch.AccessContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Who is asking, as this service knows it.
 *
 * <p>Read from the request the container is already holding, which is why no service method in this
 * application takes an {@code AccessContext} parameter. Identity is known at the edge and needed at
 * the gate several layers down, and threading it through everything in between is how a safety
 * feature becomes the most annoying thing in a codebase.
 *
 * <p>Headers here because the example has no identity provider. A real service would read {@code
 * SecurityContextHolder}; Loch never learns the difference, because it is handed a supplier and
 * asks it.
 */
public final class CurrentAccess {

  private CurrentAccess() {}

  public static AccessContext get() {
    if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
      return AccessContext.empty();
    }
    HttpServletRequest request = attrs.getRequest();
    Map<String, String> who = new LinkedHashMap<>();
    put(who, "tenant", request.getHeader("X-Tenant"));
    put(who, "role", request.getHeader("X-Role"));
    put(who, "principal", request.getHeader("X-User"));
    return AccessContext.of(who);
  }

  private static void put(Map<String, String> who, String key, String value) {
    if (value != null && !value.isBlank()) {
      who.put(key, value);
    }
  }
}
