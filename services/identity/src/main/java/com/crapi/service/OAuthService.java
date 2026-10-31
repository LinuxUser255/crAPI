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

package com.crapi.service;

import com.crapi.entity.OAuthClient;
import com.crapi.entity.OAuthConsent;
import com.crapi.entity.User;
import com.crapi.model.OAuthMetadata;
import com.crapi.model.OAuthTokenResponse;
import com.crapi.model.UserInfoResponse;
import java.util.List;

/**
 * Core OAuth 2.0 Authorization Server logic, separated from the HTTP handling in {@code
 * OAuthController}. Phase 1 implements the standard, secure authorization-code and refresh-token
 * flows; the lab weaknesses are added later behind toggles.
 */
public interface OAuthService {

  boolean isEnabled();

  OAuthClient findClient(String clientId);

  /** True if the redirect URI is exactly one the client registered. */
  boolean isRegisteredRedirectUri(OAuthClient client, String redirectUri);

  /**
   * Validates that every requested scope is allowed for the client and returns the normalized,
   * space-separated granted scope.
   */
  String validateScope(OAuthClient client, String requestedScope);

  /** True if the user has already consented to at least the requested scopes for this client. */
  boolean hasConsent(User user, OAuthClient client, String scope);

  /** Records consent and issues a single-use authorization code bound to the request. */
  String issueAuthorizationCode(User user, OAuthClient client, String redirectUri, String scope);

  /** Exchanges an authorization code for tokens (authorization_code grant). */
  OAuthTokenResponse exchangeAuthorizationCode(String code, String redirectUri, OAuthClient client);

  /** Issues a fresh access token from a refresh token (refresh_token grant). */
  OAuthTokenResponse refreshAccessToken(String refreshToken, OAuthClient client);

  /** Returns the client if the supplied credentials authenticate it, otherwise null. */
  OAuthClient authenticateClient(String clientId, String clientSecret);

  /** Builds the userinfo response for a user, limited to the fields the scope allows. */
  UserInfoResponse getUserInfo(User user, String scope);

  /** Revokes an access or refresh token, but only one that was issued to the given client. */
  void revokeToken(String token, OAuthClient client);

  /** The clients a user has authorized (for the "Connected apps" panel). */
  List<OAuthConsent> getUserConsents(User user);

  /** Builds the authorization-server metadata document for the given external base URL. */
  OAuthMetadata buildMetadata(String baseUrl);
}
