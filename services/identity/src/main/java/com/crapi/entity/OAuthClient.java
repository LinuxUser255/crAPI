/*
 * Licensed under the Apache License, Version 2.0 (the “License”);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an “AS IS” BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.crapi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Data;

/**
 * A registered OAuth 2.0 client application (for example the DealerConnect portal). Clients are
 * seeded by {@code InitialDataConfig}; there is no self-service registration.
 *
 * <p>{@code clientSecret} holds a BCrypt hash, never the plaintext. {@code redirectUris} and {@code
 * scopes} are stored space-separated and exposed as a list/set through the helpers.
 */
@Entity
@Table(name = "oauth_client")
@Data
public class OAuthClient {

  /** Public client identifier, used as the natural primary key. */
  @Id private String clientId;

  /** BCrypt hash of the client secret (confidential clients only). */
  @Column(length = 1000)
  private String clientSecret;

  private String name;

  /** "confidential" (can keep a secret) or "public" (cannot). */
  private String clientType;

  private String logoUrl;

  /** Space-separated list of exactly-registered redirect URIs. */
  @Column(length = 2000)
  private String redirectUris;

  /** Space-separated set of scopes this client may request. */
  @Column(length = 1000)
  private String scopes;

  public OAuthClient() {}

  public OAuthClient(
      String clientId,
      String clientSecret,
      String name,
      String clientType,
      String redirectUris,
      String scopes,
      String logoUrl) {
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.name = name;
    this.clientType = clientType;
    this.redirectUris = redirectUris;
    this.scopes = scopes;
    this.logoUrl = logoUrl;
  }

  public List<String> getRedirectUriList() {
    if (redirectUris == null || redirectUris.isBlank()) {
      return List.of();
    }
    return Arrays.asList(redirectUris.trim().split("\\s+"));
  }

  public Set<String> getScopeSet() {
    Set<String> result = new LinkedHashSet<>();
    if (scopes != null && !scopes.isBlank()) {
      result.addAll(Arrays.asList(scopes.trim().split("\\s+")));
    }
    return result;
  }

  public boolean isConfidential() {
    return "confidential".equalsIgnoreCase(clientType);
  }
}
