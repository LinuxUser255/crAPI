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

package com.crapi.service.Impl;

import com.crapi.config.JwtProvider;
import com.crapi.entity.OAuthAccessToken;
import com.crapi.entity.OAuthAuthorizationCode;
import com.crapi.entity.OAuthClient;
import com.crapi.entity.OAuthConsent;
import com.crapi.entity.User;
import com.crapi.entity.UserDetails;
import com.crapi.exception.OAuthException;
import com.crapi.model.OAuthMetadata;
import com.crapi.model.OAuthTokenResponse;
import com.crapi.model.UserInfoResponse;
import com.crapi.repository.OAuthAccessTokenRepository;
import com.crapi.repository.OAuthAuthorizationCodeRepository;
import com.crapi.repository.OAuthClientRepository;
import com.crapi.repository.OAuthConsentRepository;
import com.crapi.repository.UserDetailsRepository;
import com.crapi.service.OAuthService;
import jakarta.transaction.Transactional;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Standard OAuth 2.0 Authorization Server logic behind {@code OAuthController}.
 *
 * <p>Implements the authorization-code and refresh-token grants the way the RFCs require: redirect
 * URIs are matched exactly against the client's registration, requested scopes must be a subset of
 * what the client registered, authorization codes are single-use, short-lived and bound to the
 * client and redirect URI they were issued for, and confidential clients must present their secret
 * (compared against a BCrypt hash) at the token endpoint.
 *
 * <p>Access tokens are RS256 JWTs signed by the same key as crAPI's login tokens ({@link
 * JwtProvider#generateOAuthToken}), so Workshop and Community accept them through the existing
 * {@code /identity/api/auth/verify} indirection without any change.
 *
 * <p>A single flag, {@code app.oauth.mode} ({@code secure} by default, or {@code vulnerable}),
 * turns the OAuth teaching weaknesses on or off together. In {@code secure} mode the server behaves
 * as the RFCs require; in {@code vulnerable} mode the lab's OAuth challenges open up (for example
 * the loose redirect-URI match in {@link #isRegisteredRedirectUri}).
 */
@Service
@Slf4j
public class OAuthServiceImpl implements OAuthService {

  /** Scopes this authorization server knows about. */
  public static final List<String> SUPPORTED_SCOPES =
      List.of("profile", "read:vehicles", "write:orders", "mechanic", "admin");

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

  @Autowired OAuthClientRepository oAuthClientRepository;

  @Autowired OAuthAuthorizationCodeRepository oAuthAuthorizationCodeRepository;

  @Autowired OAuthAccessTokenRepository oAuthAccessTokenRepository;

  @Autowired OAuthConsentRepository oAuthConsentRepository;

  @Autowired UserDetailsRepository userDetailsRepository;

  @Autowired JwtProvider jwtProvider;

  @Autowired PasswordEncoder encoder;

  @Value("${app.oauth.enabled:true}")
  private boolean oauthEnabled;

  @Value("${app.oauth.accessTokenExpiration:3600000}")
  private long accessTokenExpiration;

  @Value("${app.oauth.codeExpiration:600000}")
  private long codeExpiration;

  // "secure" (default) or "vulnerable": the single flag that turns the OAuth
  // teaching weaknesses on or off together. See the OAuth challenge docs.
  @Value("${app.oauth.mode:secure}")
  private String oauthMode;

  private boolean isVulnerable() {
    return "vulnerable".equalsIgnoreCase(oauthMode);
  }

  @Override
  public boolean isEnabled() {
    return oauthEnabled;
  }

  @Override
  public OAuthClient findClient(String clientId) {
    if (clientId == null || clientId.isBlank()) {
      return null;
    }
    return oAuthClientRepository.findByClientId(clientId);
  }

  @Override
  public boolean isRegisteredRedirectUri(OAuthClient client, String redirectUri) {
    if (client == null || redirectUri == null || redirectUri.isBlank()) {
      return false;
    }
    if (isVulnerable()) {
      // WEAKNESS (vulnerable mode): accept any redirect URI that merely begins
      // with a registered one. A value like "<registered>.attacker.example" or
      // "<registered>/../evil" then passes, letting the authorization code be
      // sent somewhere the client never registered.
      for (String registered : client.getRedirectUriList()) {
        if (redirectUri.startsWith(registered)) {
          return true;
        }
      }
      return false;
    }
    // Exact match only: a redirect target is either registered or it is not.
    return client.getRedirectUriList().contains(redirectUri);
  }

  @Override
  public String validateScope(OAuthClient client, String requestedScope) {
    Set<String> allowed = client.getScopeSet();
    if (requestedScope == null || requestedScope.isBlank()) {
      // Scope is optional on the request; fall back to everything the client registered.
      return String.join(" ", allowed);
    }
    Set<String> requested = new LinkedHashSet<>(Arrays.asList(requestedScope.trim().split("\\s+")));
    for (String scope : requested) {
      if (!allowed.contains(scope)) {
        throw new OAuthException(
            "invalid_scope", "Scope '" + scope + "' is not allowed for this client");
      }
    }
    return String.join(" ", requested);
  }

  @Override
  public boolean hasConsent(User user, OAuthClient client, String scope) {
    OAuthConsent consent = oAuthConsentRepository.findByUserAndClientId(user, client.getClientId());
    if (consent == null) {
      return false;
    }
    return toScopeSet(consent.getScope()).containsAll(toScopeSet(scope));
  }

  @Override
  @Transactional
  public String issueAuthorizationCode(
      User user, OAuthClient client, String redirectUri, String scope) {
    recordConsent(user, client, scope);

    OAuthAuthorizationCode authorizationCode = new OAuthAuthorizationCode();
    authorizationCode.setCode(randomToken(32));
    authorizationCode.setClientId(client.getClientId());
    authorizationCode.setUser(user);
    authorizationCode.setRedirectUri(redirectUri);
    authorizationCode.setScope(scope);
    authorizationCode.setExpiresAt(System.currentTimeMillis() + codeExpiration);
    authorizationCode.setUsed(false);
    oAuthAuthorizationCodeRepository.save(authorizationCode);

    log.debug(
        "Issued authorization code to client {} for user {} with scope [{}]",
        client.getClientId(),
        user.getEmail(),
        scope);
    return authorizationCode.getCode();
  }

  /** Stores the user's approval, merging with any scopes they had already granted this client. */
  private void recordConsent(User user, OAuthClient client, String scope) {
    OAuthConsent consent = oAuthConsentRepository.findByUserAndClientId(user, client.getClientId());
    if (consent == null) {
      consent = new OAuthConsent();
      consent.setUser(user);
      consent.setClientId(client.getClientId());
      consent.setScope(scope);
    } else {
      Set<String> merged = toScopeSet(consent.getScope());
      merged.addAll(toScopeSet(scope));
      consent.setScope(String.join(" ", merged));
    }
    consent.setGrantedAt(LocalDateTime.now());
    oAuthConsentRepository.save(consent);
  }

  @Override
  @Transactional
  public OAuthTokenResponse exchangeAuthorizationCode(
      String code, String redirectUri, OAuthClient client) {
    if (code == null || code.isBlank()) {
      throw new OAuthException("invalid_request", "code is required");
    }
    OAuthAuthorizationCode authorizationCode = oAuthAuthorizationCodeRepository.findByCode(code);
    if (authorizationCode == null) {
      throw new OAuthException("invalid_grant", "Unknown authorization code");
    }
    if (authorizationCode.isUsed()) {
      throw new OAuthException("invalid_grant", "Authorization code has already been used");
    }
    if (authorizationCode.isExpired()) {
      throw new OAuthException("invalid_grant", "Authorization code has expired");
    }
    if (!authorizationCode.getClientId().equals(client.getClientId())) {
      throw new OAuthException("invalid_grant", "Authorization code was issued to another client");
    }
    if (!authorizationCode.getRedirectUri().equals(redirectUri)) {
      throw new OAuthException(
          "invalid_grant", "redirect_uri does not match the authorization request");
    }

    // Single use: burn the code before issuing anything.
    authorizationCode.setUsed(true);
    oAuthAuthorizationCodeRepository.save(authorizationCode);

    return issueTokens(authorizationCode.getUser(), client, authorizationCode.getScope());
  }

  @Override
  @Transactional
  public OAuthTokenResponse refreshAccessToken(String refreshToken, OAuthClient client) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new OAuthException("invalid_request", "refresh_token is required");
    }
    OAuthAccessToken stored = oAuthAccessTokenRepository.findByRefreshToken(refreshToken);
    if (stored == null || stored.isRevoked()) {
      throw new OAuthException("invalid_grant", "Unknown or revoked refresh token");
    }
    if (!stored.getClientId().equals(client.getClientId())) {
      throw new OAuthException("invalid_grant", "Refresh token was issued to another client");
    }

    String accessToken =
        jwtProvider.generateOAuthToken(
            stored.getUser(), client.getClientId(), stored.getScope(), accessTokenExpiration);
    stored.setAccessToken(accessToken);
    stored.setExpiresAt(System.currentTimeMillis() + accessTokenExpiration);
    oAuthAccessTokenRepository.save(stored);

    return new OAuthTokenResponse(
        accessToken, accessTokenExpiration / 1000, stored.getRefreshToken(), stored.getScope());
  }

  /** Mints the access token JWT and its refresh token, and records the grant. */
  private OAuthTokenResponse issueTokens(User user, OAuthClient client, String scope) {
    String accessToken =
        jwtProvider.generateOAuthToken(user, client.getClientId(), scope, accessTokenExpiration);
    String refreshToken = randomToken(32);

    OAuthAccessToken record = new OAuthAccessToken();
    record.setAccessToken(accessToken);
    record.setRefreshToken(refreshToken);
    record.setClientId(client.getClientId());
    record.setUser(user);
    record.setScope(scope);
    record.setExpiresAt(System.currentTimeMillis() + accessTokenExpiration);
    record.setRevoked(false);
    oAuthAccessTokenRepository.save(record);

    return new OAuthTokenResponse(accessToken, accessTokenExpiration / 1000, refreshToken, scope);
  }

  @Override
  public OAuthClient authenticateClient(String clientId, String clientSecret) {
    OAuthClient client = findClient(clientId);
    if (client == null) {
      return null;
    }
    if (!client.isConfidential()) {
      // Public clients have no secret to present.
      return client;
    }
    if (clientSecret == null || clientSecret.isBlank()) {
      return null;
    }
    if (!encoder.matches(clientSecret, client.getClientSecret())) {
      return null;
    }
    return client;
  }

  @Override
  @Transactional // UserDetails holds a Postgres large object, readable only inside a transaction
  public UserInfoResponse getUserInfo(User user, String scope) {
    Set<String> scopes = toScopeSet(scope);
    UserInfoResponse response = new UserInfoResponse(user.getEmail());
    if (scopes.contains("profile")) {
      response.setEmail(user.getEmail());
      response.setNumber(user.getNumber());
      response.setRole(user.getRole().getName());
      UserDetails details = userDetailsRepository.findByUser_id(user.getId());
      if (details != null) {
        response.setName(details.getName());
      }
    }
    return response;
  }

  @Override
  @Transactional
  public void revokeToken(String token, OAuthClient client) {
    if (token == null || token.isBlank()) {
      return;
    }
    OAuthAccessToken stored = oAuthAccessTokenRepository.findByRefreshToken(token);
    if (stored == null) {
      stored = oAuthAccessTokenRepository.findByAccessToken(token);
    }
    // RFC 7009 §2.1: a client may only revoke its own tokens. Others are silently ignored.
    if (stored != null && stored.getClientId().equals(client.getClientId())) {
      stored.setRevoked(true);
      oAuthAccessTokenRepository.save(stored);
      log.debug("Revoked token issued to client {}", stored.getClientId());
    }
  }

  @Override
  public List<OAuthConsent> getUserConsents(User user) {
    return oAuthConsentRepository.findByUser(user);
  }

  @Override
  @Transactional
  public boolean disconnectClient(User user, String clientId) {
    OAuthConsent consent = oAuthConsentRepository.findByUserAndClientId(user, clientId);
    if (consent == null) {
      return false;
    }
    oAuthConsentRepository.delete(consent);

    // Without this the app would keep working on tokens it already holds.
    for (OAuthAccessToken token :
        oAuthAccessTokenRepository.findByUserAndClientId(user, clientId)) {
      token.setRevoked(true);
      oAuthAccessTokenRepository.save(token);
    }
    for (OAuthAuthorizationCode code :
        oAuthAuthorizationCodeRepository.findByUserAndClientIdAndUsed(user, clientId, false)) {
      code.setUsed(true);
      oAuthAuthorizationCodeRepository.save(code);
    }
    log.debug("User {} disconnected client {}", user.getEmail(), clientId);
    return true;
  }

  @Override
  public OAuthMetadata buildMetadata(String baseUrl) {
    String oauthBase = baseUrl + "/identity/api/oauth";
    OAuthMetadata metadata = new OAuthMetadata();
    metadata.setIssuer(baseUrl);
    // Where the client sends the user's browser: the web app's consent page, which drives the
    // /identity/api/oauth/authorize API with the user's crAPI token.
    metadata.setAuthorizationEndpoint(baseUrl + "/oauth-authorize");
    metadata.setTokenEndpoint(oauthBase + "/token");
    metadata.setUserinfoEndpoint(oauthBase + "/userinfo");
    metadata.setRevocationEndpoint(oauthBase + "/revoke");
    metadata.setJwksUri(baseUrl + "/identity/api/auth/jwks.json");
    metadata.setResponseTypesSupported(List.of("code"));
    metadata.setGrantTypesSupported(List.of("authorization_code", "refresh_token"));
    metadata.setScopesSupported(SUPPORTED_SCOPES);
    metadata.setTokenEndpointAuthMethodsSupported(
        List.of("client_secret_basic", "client_secret_post"));
    return metadata;
  }

  private Set<String> toScopeSet(String scope) {
    Set<String> result = new LinkedHashSet<>();
    if (scope != null && !scope.isBlank()) {
      result.addAll(Arrays.asList(scope.trim().split("\\s+")));
    }
    return result;
  }

  private String randomToken(int bytes) {
    byte[] buffer = new byte[bytes];
    SECURE_RANDOM.nextBytes(buffer);
    return URL_ENCODER.encodeToString(buffer);
  }
}
