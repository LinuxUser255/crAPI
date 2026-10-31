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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * A record of an access token (and its refresh token) issued to a client for a user. The access
 * token itself is a signed JWT; this row lets the refresh grant look a token up and lets {@code
 * /revoke} and {@code /userinfo} find it. The resource servers read the JWT directly, so this table
 * is the issuing-side record, not a per-request check.
 */
@Entity
@Table(name = "oauth_access_token")
@Data
public class OAuthAccessToken {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private long id;

  @Column(length = 2000)
  private String accessToken;

  @Column(unique = true, length = 500)
  private String refreshToken;

  private String clientId;

  @ManyToOne private User user;

  @Column(length = 1000)
  private String scope;

  /** Access-token expiry as epoch milliseconds. */
  private long expiresAt;

  private boolean revoked;

  public OAuthAccessToken() {}

  public boolean isExpired() {
    return System.currentTimeMillis() > expiresAt;
  }
}
