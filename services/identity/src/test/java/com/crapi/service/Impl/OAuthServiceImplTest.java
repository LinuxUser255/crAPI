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
import com.crapi.enums.ERole;
import com.crapi.exception.OAuthException;
import com.crapi.model.OAuthTokenResponse;
import com.crapi.repository.OAuthAccessTokenRepository;
import com.crapi.repository.OAuthAuthorizationCodeRepository;
import com.crapi.repository.OAuthClientRepository;
import com.crapi.repository.OAuthConsentRepository;
import com.crapi.repository.UserDetailsRepository;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.Assertions;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/** Covers the protocol rules of the authorization-code and refresh-token grants. */
@RunWith(MockitoJUnitRunner.class)
public class OAuthServiceImplTest {

  private static final String CLIENT_ID = "dealer-connect";
  private static final String REDIRECT_URI = "http://localhost:8900/oauth/callback";

  @InjectMocks private OAuthServiceImpl oAuthService;

  @Mock private OAuthClientRepository oAuthClientRepository;
  @Mock private OAuthAuthorizationCodeRepository oAuthAuthorizationCodeRepository;
  @Mock private OAuthAccessTokenRepository oAuthAccessTokenRepository;
  @Mock private OAuthConsentRepository oAuthConsentRepository;
  @Mock private UserDetailsRepository userDetailsRepository;
  @Mock private JwtProvider jwtProvider;
  @Mock private PasswordEncoder encoder;

  @Before
  public void setUp() {
    // @Value fields are not populated outside a Spring context.
    ReflectionTestUtils.setField(oAuthService, "oauthEnabled", true);
    ReflectionTestUtils.setField(oAuthService, "accessTokenExpiration", 3600000L);
    ReflectionTestUtils.setField(oAuthService, "codeExpiration", 600000L);
  }

  @Test
  public void registeredRedirectUriMatchesExactly() {
    OAuthClient client = getDummyClient();
    Assertions.assertTrue(oAuthService.isRegisteredRedirectUri(client, REDIRECT_URI));
  }

  @Test
  public void unregisteredRedirectUriIsRejected() {
    OAuthClient client = getDummyClient();
    // Anything that is not character-for-character the registered URI must be refused.
    Assertions.assertFalse(
        oAuthService.isRegisteredRedirectUri(client, "http://localhost:8900/oauth/callback/x"));
    Assertions.assertFalse(
        oAuthService.isRegisteredRedirectUri(client, "http://localhost:8900/oauth"));
    Assertions.assertFalse(oAuthService.isRegisteredRedirectUri(client, null));
    Assertions.assertFalse(oAuthService.isRegisteredRedirectUri(client, ""));
  }

  @Test
  public void validateScopeAcceptsSubsetOfRegisteredScopes() {
    OAuthClient client = getDummyClient();
    Assertions.assertEquals("read:vehicles", oAuthService.validateScope(client, "read:vehicles"));
  }

  @Test
  public void validateScopeDefaultsToEveryRegisteredScope() {
    OAuthClient client = getDummyClient();
    Assertions.assertEquals(
        "profile read:vehicles write:orders", oAuthService.validateScope(client, null));
  }

  @Test(expected = OAuthException.class)
  public void validateScopeRejectsScopeTheClientDidNotRegister() {
    oAuthService.validateScope(getDummyClient(), "read:vehicles admin");
  }

  @Test
  public void authenticateClientRejectsWrongSecret() {
    Mockito.when(oAuthClientRepository.findByClientId(CLIENT_ID)).thenReturn(getDummyClient());
    Mockito.when(encoder.matches(Mockito.anyString(), Mockito.anyString())).thenReturn(false);
    Assertions.assertNull(oAuthService.authenticateClient(CLIENT_ID, "wrong"));
  }

  @Test
  public void authenticateClientRejectsMissingSecretForConfidentialClient() {
    Mockito.when(oAuthClientRepository.findByClientId(CLIENT_ID)).thenReturn(getDummyClient());
    Assertions.assertNull(oAuthService.authenticateClient(CLIENT_ID, null));
  }

  @Test
  public void authenticateClientAcceptsCorrectSecret() {
    Mockito.when(oAuthClientRepository.findByClientId(CLIENT_ID)).thenReturn(getDummyClient());
    Mockito.when(encoder.matches(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
    Assertions.assertNotNull(oAuthService.authenticateClient(CLIENT_ID, "right"));
  }

  @Test
  public void exchangeAuthorizationCodeIssuesTokens() {
    OAuthAuthorizationCode code = getDummyCode();
    Mockito.when(oAuthAuthorizationCodeRepository.findByCode("the-code")).thenReturn(code);
    Mockito.when(
            jwtProvider.generateOAuthToken(
                Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong()))
        .thenReturn("signed.jwt.token");

    OAuthTokenResponse response =
        oAuthService.exchangeAuthorizationCode("the-code", REDIRECT_URI, getDummyClient());

    Assertions.assertEquals("signed.jwt.token", response.getAccessToken());
    Assertions.assertEquals("Bearer", response.getTokenType());
    Assertions.assertEquals(3600, response.getExpiresIn());
    Assertions.assertNotNull(response.getRefreshToken());
    Assertions.assertEquals("profile read:vehicles", response.getScope());
    // The code must be burned so it cannot be redeemed twice.
    Assertions.assertTrue(code.isUsed());
    Mockito.verify(oAuthAccessTokenRepository).save(Mockito.any(OAuthAccessToken.class));
  }

  @Test(expected = OAuthException.class)
  public void exchangeAuthorizationCodeRejectsUnknownCode() {
    Mockito.when(oAuthAuthorizationCodeRepository.findByCode("nope")).thenReturn(null);
    oAuthService.exchangeAuthorizationCode("nope", REDIRECT_URI, getDummyClient());
  }

  @Test(expected = OAuthException.class)
  public void exchangeAuthorizationCodeRejectsReuse() {
    OAuthAuthorizationCode code = getDummyCode();
    code.setUsed(true);
    Mockito.when(oAuthAuthorizationCodeRepository.findByCode("the-code")).thenReturn(code);
    oAuthService.exchangeAuthorizationCode("the-code", REDIRECT_URI, getDummyClient());
  }

  @Test(expected = OAuthException.class)
  public void exchangeAuthorizationCodeRejectsExpiredCode() {
    OAuthAuthorizationCode code = getDummyCode();
    code.setExpiresAt(System.currentTimeMillis() - 1000);
    Mockito.when(oAuthAuthorizationCodeRepository.findByCode("the-code")).thenReturn(code);
    oAuthService.exchangeAuthorizationCode("the-code", REDIRECT_URI, getDummyClient());
  }

  @Test(expected = OAuthException.class)
  public void exchangeAuthorizationCodeRejectsMismatchedRedirectUri() {
    Mockito.when(oAuthAuthorizationCodeRepository.findByCode("the-code"))
        .thenReturn(getDummyCode());
    oAuthService.exchangeAuthorizationCode(
        "the-code", "http://localhost:8900/other", getDummyClient());
  }

  @Test(expected = OAuthException.class)
  public void exchangeAuthorizationCodeRejectsAnotherClient() {
    Mockito.when(oAuthAuthorizationCodeRepository.findByCode("the-code"))
        .thenReturn(getDummyCode());
    OAuthClient otherClient = getDummyClient();
    otherClient.setClientId("someone-else");
    oAuthService.exchangeAuthorizationCode("the-code", REDIRECT_URI, otherClient);
  }

  @Test(expected = OAuthException.class)
  public void refreshRejectsRevokedToken() {
    OAuthAccessToken stored = getDummyAccessToken();
    stored.setRevoked(true);
    Mockito.when(oAuthAccessTokenRepository.findByRefreshToken("refresh")).thenReturn(stored);
    oAuthService.refreshAccessToken("refresh", getDummyClient());
  }

  @Test(expected = OAuthException.class)
  public void refreshRejectsTokenOfAnotherClient() {
    Mockito.when(oAuthAccessTokenRepository.findByRefreshToken("refresh"))
        .thenReturn(getDummyAccessToken());
    OAuthClient otherClient = getDummyClient();
    otherClient.setClientId("someone-else");
    oAuthService.refreshAccessToken("refresh", otherClient);
  }

  @Test
  public void refreshIssuesANewAccessTokenAndKeepsTheScope() {
    Mockito.when(oAuthAccessTokenRepository.findByRefreshToken("refresh"))
        .thenReturn(getDummyAccessToken());
    Mockito.when(
            jwtProvider.generateOAuthToken(
                Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong()))
        .thenReturn("fresh.jwt.token");

    OAuthTokenResponse response = oAuthService.refreshAccessToken("refresh", getDummyClient());

    Assertions.assertEquals("fresh.jwt.token", response.getAccessToken());
    Assertions.assertEquals("profile read:vehicles", response.getScope());
    Assertions.assertEquals("refresh", response.getRefreshToken());
  }

  @Test
  public void revokeMarksTheClientsOwnTokenRevoked() {
    OAuthAccessToken stored = getDummyAccessToken();
    Mockito.when(oAuthAccessTokenRepository.findByRefreshToken("refresh")).thenReturn(stored);
    oAuthService.revokeToken("refresh", getDummyClient());
    Assertions.assertTrue(stored.isRevoked());
  }

  @Test
  public void revokeIgnoresATokenIssuedToAnotherClient() {
    OAuthAccessToken stored = getDummyAccessToken();
    Mockito.when(oAuthAccessTokenRepository.findByRefreshToken("refresh")).thenReturn(stored);
    OAuthClient otherClient = getDummyClient();
    otherClient.setClientId("someone-else");
    oAuthService.revokeToken("refresh", otherClient);
    Assertions.assertFalse(stored.isRevoked());
  }

  @Test
  public void hasConsentIsFalseWhenScopeWasNotGranted() {
    User user = getDummyUser();
    OAuthConsent consent = new OAuthConsent();
    consent.setUser(user);
    consent.setClientId(CLIENT_ID);
    consent.setScope("profile");
    Mockito.when(oAuthConsentRepository.findByUserAndClientId(user, CLIENT_ID)).thenReturn(consent);
    Assertions.assertFalse(oAuthService.hasConsent(user, getDummyClient(), "profile write:orders"));
  }

  @Test
  public void hasConsentIsTrueForAPreviouslyGrantedSubset() {
    User user = getDummyUser();
    OAuthConsent consent = new OAuthConsent();
    consent.setUser(user);
    consent.setClientId(CLIENT_ID);
    consent.setScope("profile read:vehicles write:orders");
    Mockito.when(oAuthConsentRepository.findByUserAndClientId(user, CLIENT_ID)).thenReturn(consent);
    Assertions.assertTrue(oAuthService.hasConsent(user, getDummyClient(), "profile read:vehicles"));
  }

  private OAuthClient getDummyClient() {
    return new OAuthClient(
        CLIENT_ID,
        "$2a$10$hashedsecret",
        "DealerConnect",
        "confidential",
        REDIRECT_URI,
        "profile read:vehicles write:orders",
        "images/dealer-connect.png");
  }

  private User getDummyUser() {
    return new User("adam007@example.com", "9876895423", "Adam007!123", ERole.ROLE_USER);
  }

  private OAuthAuthorizationCode getDummyCode() {
    OAuthAuthorizationCode code = new OAuthAuthorizationCode();
    code.setCode("the-code");
    code.setClientId(CLIENT_ID);
    code.setUser(getDummyUser());
    code.setRedirectUri(REDIRECT_URI);
    code.setScope("profile read:vehicles");
    code.setExpiresAt(System.currentTimeMillis() + 600000);
    code.setUsed(false);
    return code;
  }

  private OAuthAccessToken getDummyAccessToken() {
    OAuthAccessToken token = new OAuthAccessToken();
    token.setAccessToken("old.jwt.token");
    token.setRefreshToken("refresh");
    token.setClientId(CLIENT_ID);
    token.setUser(getDummyUser());
    token.setScope("profile read:vehicles");
    token.setExpiresAt(System.currentTimeMillis() + 3600000);
    token.setRevoked(false);
    return token;
  }
}
