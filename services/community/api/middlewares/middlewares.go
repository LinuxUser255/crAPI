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

// Package middlewares holds the handler wrappers applied in router/routes.go.
//
// SetMiddlewareJSON sets the JSON content type and CORS headers.
// SetMiddlewareAuthentication is the gate on every API route: it pulls the
// Bearer token off the request and asks auth.ExtractTokenID to validate it
// (which defers to the Identity service), rejecting with 401 otherwise.
// SetMiddlewareScope requires an OAuth scope in secure mode and skips the check
// in vulnerable mode, following the shared OAUTH_MODE flag. AccessControlMiddleware
// answers CORS preflight requests.
package middlewares

import (
	"errors"
	"net/http"
	"os"
	"strings"

	"crapi.proj/goservice/api/auth"
	"crapi.proj/goservice/api/responses"
	"github.com/jinzhu/gorm"
)

// scopeEnforcementEnabled reports whether this service enforces OAuth scopes.
// Driven by the shared OAUTH_MODE flag: secure (or unset) enforces, vulnerable
// does not.
func scopeEnforcementEnabled() bool {
	return !strings.EqualFold(os.Getenv("OAUTH_MODE"), "vulnerable")
}

// SetMiddlewareScope requires a delegated OAuth token to hold requiredScope.
// A first-party token (no scope claim) passes through. In vulnerable mode the
// check is skipped entirely, so delegated tokens are over-privileged.
func SetMiddlewareScope(next http.HandlerFunc, requiredScope string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if scopeEnforcementEnabled() {
			scope, isDelegated := auth.ExtractScope(r)
			if isDelegated && !scopeContains(scope, requiredScope) {
				responses.ERROR(w, http.StatusForbidden, errors.New("insufficient_scope"))
				return
			}
		}
		next(w, r)
	}
}

func scopeContains(scope, required string) bool {
	for _, s := range strings.Fields(scope) {
		if s == required {
			return true
		}
	}
	return false
}

//SetMiddlewareJSON set content type and options
func SetMiddlewareJSON(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "POST, GET, OPTIONS, PUT, DELETE")
		w.Header().Set("Access-Control-Allow-Headers", "Accept, Content-Type, Content-Length, Accept-Encoding, X-CSRF-Token, Authorization")
		if r.Method == "OPTIONS" {
			return
		}
		next(w, r)
	}
}

//AccessControlMiddleware set content type of header
func AccessControlMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS,PUT")
		w.Header().Set("Access-Control-Allow-Headers", "authorization,content-type")

		if r.Method == "OPTIONS" {
			return
		}

		next.ServeHTTP(w, r)
	})
}

//SetMiddlewareAuthentication checks Authentication token for each request.
func SetMiddlewareAuthentication(next http.HandlerFunc, db *gorm.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		_, err := auth.ExtractTokenID(r, db)
		if err != nil {
			responses.ERROR(w, http.StatusUnauthorized, errors.New("Unauthorized"))
			return
		}
		next(w, r)
	}
}
