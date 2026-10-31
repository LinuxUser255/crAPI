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
import java.util.List;
import lombok.Data;

/**
 * Description of a pending authorization request, returned by {@code GET /oauth/authorize} so the
 * web app can render the consent screen ("DealerConnect wants to ...") before the user approves.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OAuthAuthorizeInfo {

  @JsonProperty("client_id")
  private String clientId;

  @JsonProperty("client_name")
  private String clientName;

  @JsonProperty("logo_url")
  private String logoUrl;

  @JsonProperty("redirect_uri")
  private String redirectUri;

  private List<String> scopes;

  private String state;

  /** False when the user has already granted these scopes to this client. */
  @JsonProperty("consent_required")
  private boolean consentRequired;
}
