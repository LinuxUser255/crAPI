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

package com.crapi.exception;

import lombok.Getter;

/**
 * An OAuth 2.0 protocol error (RFC 6749). Carries the {@code error} code and a human-readable
 * description so the controller can render the standard JSON error body or redirect error.
 */
@Getter
public class OAuthException extends RuntimeException {

  private final String error;
  private final String errorDescription;

  public OAuthException(String error, String errorDescription) {
    super(error + ": " + errorDescription);
    this.error = error;
    this.errorDescription = errorDescription;
  }
}
