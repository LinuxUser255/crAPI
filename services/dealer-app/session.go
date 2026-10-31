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
	"crypto/rand"
	"encoding/base64"
	"net/http"
	"sync"
	"time"
)

const sessionCookie = "dc_session"

// Session is one browser's server-side state. The browser holds only the
// random session id in a cookie; everything below stays on the server.
type Session struct {
	ID string

	// Set while an OAuth login is in flight, checked when the user returns.
	PendingState string

	// Populated after a successful login.
	LoggedIn     bool
	AccessToken  string
	RefreshToken string
	Scope        string
	Name         string
	Email        string
}

// SessionStore keeps sessions in memory. A restart clears them, which just
// logs everyone out; that is acceptable for a lab.
type SessionStore struct {
	mu       sync.Mutex
	sessions map[string]*Session
}

func NewSessionStore() *SessionStore {
	return &SessionStore{sessions: make(map[string]*Session)}
}

func randomToken() string {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		panic(err)
	}
	return base64.RawURLEncoding.EncodeToString(buf)
}

// Get returns the session named by the request cookie, creating a new one (and
// setting the cookie) when there isn't a valid session yet.
func (s *SessionStore) Get(w http.ResponseWriter, r *http.Request) *Session {
	if cookie, err := r.Cookie(sessionCookie); err == nil {
		s.mu.Lock()
		session, ok := s.sessions[cookie.Value]
		s.mu.Unlock()
		if ok {
			return session
		}
	}
	return s.create(w)
}

func (s *SessionStore) create(w http.ResponseWriter) *Session {
	session := &Session{ID: randomToken()}
	s.mu.Lock()
	s.sessions[session.ID] = session
	s.mu.Unlock()
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookie,
		Value:    session.ID,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Expires:  time.Now().Add(8 * time.Hour),
	})
	return session
}

// Destroy drops the session and clears the cookie.
func (s *SessionStore) Destroy(w http.ResponseWriter, session *Session) {
	s.mu.Lock()
	delete(s.sessions, session.ID)
	s.mu.Unlock()
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookie,
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		MaxAge:   -1,
	})
}
