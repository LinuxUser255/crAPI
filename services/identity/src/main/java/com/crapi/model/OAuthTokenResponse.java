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

package com.crapi.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** Successful response body of the {@code /token} endpoint (RFC 6749 §5.1). */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OAuthTokenResponse {

  @JsonProperty("access_token")
  private String accessToken;

  @JsonProperty("token_type")
  private String tokenType = "Bearer";

  @JsonProperty("expires_in")
  private long expiresIn;

  @JsonProperty("refresh_token")
  private String refreshToken;

  private String scope;

  public OAuthTokenResponse() {}

  public OAuthTokenResponse(String accessToken, long expiresIn, String refreshToken, String scope) {
    this.accessToken = accessToken;
    this.expiresIn = expiresIn;
    this.refreshToken = refreshToken;
    this.scope = scope;
  }
}
