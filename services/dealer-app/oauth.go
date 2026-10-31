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

package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// OAuthClient is DealerConnect's side of the OAuth 2.0 authorization-code flow
// against crAPI. The browser is sent to the public authorization endpoint; the
// code is exchanged and the APIs are called server-to-server over the internal
// URL.
type OAuthClient struct {
	config Config
	http   *http.Client
}

func NewOAuthClient(config Config) *OAuthClient {
	return &OAuthClient{
		config: config,
		http: &http.Client{
			Timeout: 15 * time.Second,
			// The lab's internal crAPI uses a self-signed certificate.
			Transport: &http.Transport{
				TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
			},
		},
	}
}

// AuthorizeURL is where the user's browser is sent to approve access. It points
// at crAPI's public consent page and carries the state value for this login.
func (c *OAuthClient) AuthorizeURL(state string) string {
	query := url.Values{}
	query.Set("response_type", "code")
	query.Set("client_id", c.config.ClientID)
	query.Set("redirect_uri", c.config.RedirectURI)
	query.Set("scope", c.config.Scope)
	query.Set("state", state)
	return c.config.CrapiPublicURL + "/oauth-authorize?" + query.Encode()
}

// Token is the subset of crAPI's token response DealerConnect keeps.
type Token struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	Scope        string `json:"scope"`
	ExpiresIn    int64  `json:"expires_in"`
}

// ExchangeCode trades an authorization code for tokens at crAPI's token
// endpoint, authenticating with the client id and secret.
func (c *OAuthClient) ExchangeCode(ctx context.Context, code string) (*Token, error) {
	form := url.Values{}
	form.Set("grant_type", "authorization_code")
	form.Set("code", code)
	form.Set("redirect_uri", c.config.RedirectURI)
	form.Set("client_id", c.config.ClientID)
	form.Set("client_secret", c.config.ClientSecret)
	return c.postToken(ctx, form)
}

// Refresh obtains a fresh access token from a refresh token.
func (c *OAuthClient) Refresh(ctx context.Context, refreshToken string) (*Token, error) {
	form := url.Values{}
	form.Set("grant_type", "refresh_token")
	form.Set("refresh_token", refreshToken)
	form.Set("client_id", c.config.ClientID)
	form.Set("client_secret", c.config.ClientSecret)
	return c.postToken(ctx, form)
}

func (c *OAuthClient) postToken(ctx context.Context, form url.Values) (*Token, error) {
	endpoint := c.config.CrapiInternalURL + "/identity/api/oauth/token"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("token endpoint returned %d: %s", resp.StatusCode, string(body))
	}
	var token Token
	if err := json.Unmarshal(body, &token); err != nil {
		return nil, err
	}
	return &token, nil
}

// Get calls a crAPI API path with the access token and decodes the JSON result
// into out. path begins with a slash, e.g. "/identity/api/v2/vehicle/vehicles".
func (c *OAuthClient) Get(ctx context.Context, accessToken, path string, out interface{}) error {
	return c.do(ctx, http.MethodGet, accessToken, path, nil, out)
}

// PostJSON sends a JSON body to a crAPI API path with the access token.
func (c *OAuthClient) PostJSON(ctx context.Context, accessToken, path string, in, out interface{}) error {
	return c.do(ctx, http.MethodPost, accessToken, path, in, out)
}

func (c *OAuthClient) do(ctx context.Context, method, accessToken, path string, in, out interface{}) error {
	var bodyReader io.Reader
	if in != nil {
		encoded, err := json.Marshal(in)
		if err != nil {
			return err
		}
		bodyReader = strings.NewReader(string(encoded))
	}
	req, err := http.NewRequestWithContext(ctx, method, c.config.CrapiInternalURL+path, bodyReader)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+accessToken)
	if in != nil {
		req.Header.Set("Content-Type", "application/json")
	}

	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("crAPI %s %s returned %d: %s", method, path, resp.StatusCode, string(body))
	}
	if out == nil {
		return nil
	}
	return json.Unmarshal(body, out)
}
