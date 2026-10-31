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

package com.crapi.controller;

import com.crapi.entity.OAuthClient;
import com.crapi.entity.OAuthConsent;
import com.crapi.entity.User;
import com.crapi.exception.OAuthException;
import com.crapi.model.OAuthAuthorizeInfo;
import com.crapi.model.OAuthError;
import com.crapi.model.OAuthMetadata;
import com.crapi.model.OAuthTokenResponse;
import com.crapi.model.UserInfoResponse;
import com.crapi.service.OAuthService;
import com.crapi.service.UserService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth 2.0 Authorization Server endpoints, under {@code /identity/api/oauth}.
 *
 * <p>crAPI authenticates users with bearer JWTs rather than browser sessions, so the authorization
 * step is mediated by the web app instead of being a raw browser redirect: {@code GET /authorize}
 * validates the request and describes it (client, scopes) for the consent screen, and {@code POST
 * /authorize} records the user's decision and returns the URL to send the browser back to. Both
 * require the user's crAPI token. {@code /token} is called by the client application itself and
 * authenticates with client credentials, not a user token.
 *
 * <p>Delegates all protocol logic to {@code OAuthService}; this class only maps it to HTTP.
 */
@Slf4j
@CrossOrigin
@RestController
@RequestMapping("/identity/api/oauth")
public class OAuthController {

  @Autowired OAuthService oAuthService;

  @Autowired UserService userService;

  /**
   * Describes a pending authorization request so the web app can render the consent screen. Does
   * not issue anything; approval happens in {@link #approveAuthorization}.
   */
  @GetMapping("/authorize")
  public ResponseEntity<?> authorize(
      @RequestParam(value = "response_type", required = false) String responseType,
      @RequestParam(value = "client_id", required = false) String clientId,
      @RequestParam(value = "redirect_uri", required = false) String redirectUri,
      @RequestParam(value = "scope", required = false) String scope,
      @RequestParam(value = "state", required = false) String state,
      HttpServletRequest request) {
    if (!oAuthService.isEnabled()) {
      return oauthDisabled();
    }
    ResponseEntity<?> invalid = validateClientAndRedirectUri(clientId, redirectUri);
    if (invalid != null) {
      return invalid;
    }
    OAuthClient client = oAuthService.findClient(clientId);

    // From here the request is safe to bounce back to the registered redirect URI.
    if (!"code".equals(responseType)) {
      return redirectWithError(
          redirectUri, "unsupported_response_type", "Only response_type=code is supported", state);
    }

    String grantedScope;
    try {
      grantedScope = oAuthService.validateScope(client, scope);
    } catch (OAuthException e) {
      return redirectWithError(redirectUri, e.getError(), e.getErrorDescription(), state);
    }

    User user = currentUser(request);
    if (user == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(new OAuthError("access_denied", "User authentication is required"));
    }

    OAuthAuthorizeInfo info = new OAuthAuthorizeInfo();
    info.setClientId(client.getClientId());
    info.setClientName(client.getName());
    info.setLogoUrl(client.getLogoUrl());
    info.setRedirectUri(redirectUri);
    info.setScopes(Arrays.asList(grantedScope.split("\\s+")));
    info.setState(state);
    info.setConsentRequired(!oAuthService.hasConsent(user, client, grantedScope));
    return ResponseEntity.status(HttpStatus.OK).body(info);
  }

  /**
   * Records the user's decision on a pending authorization request. On approval an authorization
   * code is issued; either way the response carries the URL to send the browser back to.
   */
  @PostMapping("/authorize")
  public ResponseEntity<?> approveAuthorization(
      @RequestParam(value = "response_type", required = false) String responseType,
      @RequestParam(value = "client_id", required = false) String clientId,
      @RequestParam(value = "redirect_uri", required = false) String redirectUri,
      @RequestParam(value = "scope", required = false) String scope,
      @RequestParam(value = "state", required = false) String state,
      @RequestParam(value = "approved", defaultValue = "true") boolean approved,
      HttpServletRequest request) {
    if (!oAuthService.isEnabled()) {
      return oauthDisabled();
    }
    ResponseEntity<?> invalid = validateClientAndRedirectUri(clientId, redirectUri);
    if (invalid != null) {
      return invalid;
    }
    OAuthClient client = oAuthService.findClient(clientId);

    if (!"code".equals(responseType)) {
      return redirectWithError(
          redirectUri, "unsupported_response_type", "Only response_type=code is supported", state);
    }

    User user = currentUser(request);
    if (user == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(new OAuthError("access_denied", "User authentication is required"));
    }

    if (!approved) {
      return redirectWithError(redirectUri, "access_denied", "The user denied the request", state);
    }

    String grantedScope;
    try {
      grantedScope = oAuthService.validateScope(client, scope);
    } catch (OAuthException e) {
      return redirectWithError(redirectUri, e.getError(), e.getErrorDescription(), state);
    }

    String code = oAuthService.issueAuthorizationCode(user, client, redirectUri, grantedScope);
    Map<String, String> params = new HashMap<>();
    params.put("code", code);
    if (state != null) {
      params.put("state", state);
    }
    Map<String, String> body = new HashMap<>();
    body.put("redirect_url", buildRedirect(redirectUri, params));
    return ResponseEntity.status(HttpStatus.OK).body(body);
  }

  /**
   * Token endpoint. Supports the {@code authorization_code} and {@code refresh_token} grants. The
   * caller is the client application, authenticating with HTTP Basic or form credentials.
   */
  @PostMapping("/token")
  public ResponseEntity<?> token(
      @RequestParam(value = "grant_type", required = false) String grantType,
      @RequestParam(value = "code", required = false) String code,
      @RequestParam(value = "redirect_uri", required = false) String redirectUri,
      @RequestParam(value = "refresh_token", required = false) String refreshToken,
      @RequestParam(value = "client_id", required = false) String clientId,
      @RequestParam(value = "client_secret", required = false) String clientSecret,
      HttpServletRequest request) {
    if (!oAuthService.isEnabled()) {
      return oauthDisabled();
    }

    String[] basic = readBasicAuth(request);
    if (basic != null) {
      clientId = basic[0];
      clientSecret = basic[1];
    }

    OAuthClient client = oAuthService.authenticateClient(clientId, clientSecret);
    if (client == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(new OAuthError("invalid_client", "Client authentication failed"));
    }

    try {
      OAuthTokenResponse response;
      if ("authorization_code".equals(grantType)) {
        response = oAuthService.exchangeAuthorizationCode(code, redirectUri, client);
      } else if ("refresh_token".equals(grantType)) {
        response = oAuthService.refreshAccessToken(refreshToken, client);
      } else {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(
                new OAuthError(
                    "unsupported_grant_type",
                    "Supported grants are authorization_code and refresh_token"));
      }
      return ResponseEntity.status(HttpStatus.OK).body(response);
    } catch (OAuthException e) {
      log.debug("Token request rejected: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(new OAuthError(e.getError(), e.getErrorDescription()));
    }
  }

  /** Returns the profile of the user the presented access token was issued for. */
  @GetMapping("/userinfo")
  public ResponseEntity<?> userinfo(HttpServletRequest request) {
    if (!oAuthService.isEnabled()) {
      return oauthDisabled();
    }
    User user = currentUser(request);
    if (user == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(new OAuthError("invalid_token", "A valid access token is required"));
    }
    String scope = readScopeClaim(request);
    UserInfoResponse response = oAuthService.getUserInfo(user, scope);
    return ResponseEntity.status(HttpStatus.OK).body(response);
  }

  /**
   * Token revocation (RFC 7009). Called by the client application, which authenticates with its own
   * credentials exactly as at {@link #token}; it can only revoke tokens issued to it.
   */
  @PostMapping("/revoke")
  public ResponseEntity<?> revoke(
      @RequestParam(value = "token", required = false) String token,
      @RequestParam(value = "client_id", required = false) String clientId,
      @RequestParam(value = "client_secret", required = false) String clientSecret,
      HttpServletRequest request) {
    if (!oAuthService.isEnabled()) {
      return oauthDisabled();
    }
    String[] basic = readBasicAuth(request);
    if (basic != null) {
      clientId = basic[0];
      clientSecret = basic[1];
    }
    OAuthClient client = oAuthService.authenticateClient(clientId, clientSecret);
    if (client == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(new OAuthError("invalid_client", "Client authentication failed"));
    }
    oAuthService.revokeToken(token, client);
    // RFC 7009 §2.2: report success even for unknown tokens, so revocation reveals nothing.
    return ResponseEntity.status(HttpStatus.OK).build();
  }

  /** The clients the calling user has authorized, for the "Connected apps" panel. */
  @GetMapping("/consents")
  public ResponseEntity<?> consents(HttpServletRequest request) {
    if (!oAuthService.isEnabled()) {
      return oauthDisabled();
    }
    User user = currentUser(request);
    if (user == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(new OAuthError("invalid_token", "A valid access token is required"));
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (OAuthConsent consent : oAuthService.getUserConsents(user)) {
      OAuthClient client = oAuthService.findClient(consent.getClientId());
      Map<String, Object> entry = new HashMap<>();
      entry.put("client_id", consent.getClientId());
      entry.put("client_name", client != null ? client.getName() : consent.getClientId());
      entry.put("logo_url", client != null ? client.getLogoUrl() : null);
      entry.put("scopes", Arrays.asList(consent.getScope().split("\\s+")));
      entry.put("granted_at", consent.getGrantedAt());
      result.add(entry);
    }
    return ResponseEntity.status(HttpStatus.OK).body(result);
  }

  /** Authorization server metadata (RFC 8414). */
  @GetMapping("/.well-known/oauth-authorization-server")
  public ResponseEntity<OAuthMetadata> metadata(HttpServletRequest request) {
    String baseUrl = externalBaseUrl(request);
    return ResponseEntity.status(HttpStatus.OK).body(oAuthService.buildMetadata(baseUrl));
  }

  /**
   * Rejects the request when the client or its redirect URI is not registered. These two failures
   * cannot be reported by redirecting, because the redirect target is exactly what is untrusted.
   *
   * @return an error response, or null when both are valid
   */
  private ResponseEntity<?> validateClientAndRedirectUri(String clientId, String redirectUri) {
    OAuthClient client = oAuthService.findClient(clientId);
    if (client == null) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(new OAuthError("invalid_client", "Unknown client_id"));
    }
    if (!oAuthService.isRegisteredRedirectUri(client, redirectUri)) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(
              new OAuthError("invalid_request", "redirect_uri is not registered for this client"));
    }
    return null;
  }

  /**
   * The user the request's token belongs to, or null when it cannot be resolved. {@code
   * UserService.getUserFromToken} throws for an unknown subject, which here just means
   * "unauthenticated" — Spring Security has already rejected requests with no token at all.
   */
  private User currentUser(HttpServletRequest request) {
    try {
      return userService.getUserFromToken(request);
    } catch (RuntimeException e) {
      log.debug("Could not resolve the user from the presented token");
      return null;
    }
  }

  private ResponseEntity<?> oauthDisabled() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(new OAuthError("temporarily_unavailable", "OAuth is disabled on this deployment"));
  }

  /** Builds a body carrying the redirect URL that reports an error back to the client. */
  private ResponseEntity<?> redirectWithError(
      String redirectUri, String error, String description, String state) {
    Map<String, String> params = new HashMap<>();
    params.put("error", error);
    params.put("error_description", description);
    if (state != null) {
      params.put("state", state);
    }
    Map<String, String> body = new HashMap<>();
    body.put("redirect_url", buildRedirect(redirectUri, params));
    body.put("error", error);
    body.put("error_description", description);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  private String buildRedirect(String redirectUri, Map<String, String> params) {
    StringBuilder url = new StringBuilder(redirectUri);
    url.append(redirectUri.contains("?") ? '&' : '?');
    boolean first = true;
    for (Map.Entry<String, String> param : params.entrySet()) {
      if (!first) {
        url.append('&');
      }
      url.append(URLEncoder.encode(param.getKey(), StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8));
      first = false;
    }
    return url.toString();
  }

  /** Reads client credentials from an HTTP Basic Authorization header, or null if absent. */
  private String[] readBasicAuth(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith("Basic ")) {
      return null;
    }
    try {
      String decoded =
          new String(
              Base64.getDecoder().decode(header.substring("Basic ".length()).trim()),
              StandardCharsets.UTF_8);
      int separator = decoded.indexOf(':');
      if (separator < 0) {
        return null;
      }
      return new String[] {decoded.substring(0, separator), decoded.substring(separator + 1)};
    } catch (IllegalArgumentException e) {
      log.debug("Malformed Basic authorization header on token request");
      return null;
    }
  }

  /** Reads the {@code scope} claim from the presented access token. */
  private String readScopeClaim(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header == null || header.length() < 8) {
      return "";
    }
    String token = header.substring(7).trim();
    try {
      String[] parts = token.split("\\.");
      if (parts.length < 2) {
        return "";
      }
      String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
      JsonObject claims = JsonParser.parseString(payload).getAsJsonObject();
      return claims.has("scope") ? claims.get("scope").getAsString() : "";
    } catch (RuntimeException e) {
      log.debug("Could not read scope claim from token");
      return "";
    }
  }

  /** The externally visible base URL, honouring the proxy headers crapi-web sets. */
  private String externalBaseUrl(HttpServletRequest request) {
    String proto = request.getHeader("X-Forwarded-Proto");
    String host = request.getHeader("X-Forwarded-Host");
    if (host == null) {
      host = request.getHeader("Host");
    }
    if (proto == null) {
      proto = request.getScheme();
    }
    if (host == null) {
      return "";
    }
    return proto + "://" + host;
  }
}
