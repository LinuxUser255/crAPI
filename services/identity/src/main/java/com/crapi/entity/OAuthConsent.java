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
import java.time.LocalDateTime;
import lombok.Data;

/**
 * A user's standing approval of a client for a set of scopes. Recorded when the user approves an
 * authorization request, it lets a later request for the same (or a subset of) scopes skip the
 * consent prompt, and it backs the "Connected apps" panel the profile page will show.
 */
@Entity
@Table(name = "oauth_consent")
@Data
public class OAuthConsent {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private long id;

  @ManyToOne private User user;

  private String clientId;

  @Column(length = 1000)
  private String scope;

  private LocalDateTime grantedAt;

  public OAuthConsent() {}
}
