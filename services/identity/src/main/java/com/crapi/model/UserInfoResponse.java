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
import lombok.Data;

/** Response of the {@code /userinfo} endpoint; fields are populated according to granted scope. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserInfoResponse {

  /** Stable subject identifier (the user's email, matching the token's {@code sub}). */
  private String sub;

  private String email;
  private String name;
  private String number;
  private String role;

  public UserInfoResponse() {}

  public UserInfoResponse(String sub) {
    this.sub = sub;
  }
}
