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
 * A short-lived OAuth 2.0 authorization code, issued by {@code /authorize} and redeemed once at
 * {@code /token}. It binds the code to the user who approved it, the client it was issued to, the
 * redirect URI it was requested with, and the granted scope, so the token endpoint can check that
 * the redemption matches the original request.
 */
@Entity
@Table(name = "oauth_authorization_code")
@Data
public class OAuthAuthorizationCode {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private long id;

  @Column(unique = true, length = 500)
  private String code;

  private String clientId;

  @ManyToOne private User user;

  @Column(length = 2000)
  private String redirectUri;

  @Column(length = 1000)
  private String scope;

  /** Expiry as epoch milliseconds. */
  private long expiresAt;

  /** True once the code has been redeemed; a code is single-use. */
  private boolean used;

  public OAuthAuthorizationCode() {}

  public boolean isExpired() {
    return System.currentTimeMillis() > expiresAt;
  }
}
