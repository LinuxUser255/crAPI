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
	"html/template"
	"log"
	"net/http"
	"strconv"
	"strings"
)

// App holds DealerConnect's dependencies and serves its pages.
type App struct {
	config    Config
	oauth     *OAuthClient
	sessions  *SessionStore
	templates *template.Template
}

func NewApp(config Config) (*App, error) {
	templates, err := loadTemplates()
	if err != nil {
		return nil, err
	}
	return &App{
		config:    config,
		oauth:     NewOAuthClient(config),
		sessions:  NewSessionStore(),
		templates: templates,
	}, nil
}

func (a *App) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/", a.handleHome)
	mux.HandleFunc("/login", a.handleLogin)
	mux.HandleFunc("/oauth/callback", a.handleCallback)
	mux.HandleFunc("/dashboard", a.handleDashboard)
	mux.HandleFunc("/order", a.handleOrder)
	mux.HandleFunc("/logout", a.handleLogout)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	return mux
}

func (a *App) render(w http.ResponseWriter, name string, data interface{}) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := a.templates.ExecuteTemplate(w, name, data); err != nil {
		log.Printf("render %s: %v", name, err)
		http.Error(w, "template error", http.StatusInternalServerError)
	}
}

func (a *App) handleHome(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	session := a.sessions.Get(w, r)
	a.render(w, "home", map[string]interface{}{"Session": session})
}

// handleLogin starts the OAuth flow: a fresh state value is stored in the
// session and the browser is sent to crAPI's consent page.
func (a *App) handleLogin(w http.ResponseWriter, r *http.Request) {
	session := a.sessions.Get(w, r)
	session.PendingState = randomToken()
	http.Redirect(w, r, a.oauth.AuthorizeURL(session.PendingState), http.StatusFound)
}

// handleCallback is crAPI's redirect target. It checks state, exchanges the
// code and loads the user's profile.
func (a *App) handleCallback(w http.ResponseWriter, r *http.Request) {
	session := a.sessions.Get(w, r)
	query := r.URL.Query()

	if errCode := query.Get("error"); errCode != "" {
		a.renderError(w, "crAPI declined the request", errCode+": "+query.Get("error_description"))
		return
	}

	state := query.Get("state")
	if session.PendingState == "" || state != session.PendingState {
		a.renderError(w, "This login can't be completed", "The state value did not match. Please start again.")
		return
	}
	session.PendingState = ""

	code := query.Get("code")
	if code == "" {
		a.renderError(w, "This login can't be completed", "crAPI did not return an authorization code.")
		return
	}

	token, err := a.oauth.ExchangeCode(r.Context(), code)
	if err != nil {
		log.Printf("code exchange failed: %v", err)
		a.renderError(w, "This login can't be completed", "The authorization code could not be exchanged.")
		return
	}

	info, err := a.oauth.UserInfo(r.Context(), token.AccessToken)
	if err != nil {
		log.Printf("userinfo failed: %v", err)
		a.renderError(w, "This login can't be completed", "Your crAPI profile could not be loaded.")
		return
	}

	session.LoggedIn = true
	session.AccessToken = token.AccessToken
	session.RefreshToken = token.RefreshToken
	session.Scope = token.Scope
	session.Name = info.Name
	session.Email = info.Email
	http.Redirect(w, r, "/dashboard", http.StatusFound)
}

func (a *App) handleDashboard(w http.ResponseWriter, r *http.Request) {
	session := a.sessions.Get(w, r)
	if !session.LoggedIn {
		http.Redirect(w, r, "/", http.StatusFound)
		return
	}

	data := map[string]interface{}{
		"Session":   session,
		"ScopeList": strings.Fields(session.Scope),
		"Notice":    r.URL.Query().Get("notice"),
	}

	if vehicles, err := a.oauth.Vehicles(r.Context(), session.AccessToken); err != nil {
		log.Printf("vehicles failed: %v", err)
		data["VehiclesError"] = true
	} else {
		data["Vehicles"] = vehicles
	}

	if products, credit, err := a.oauth.Shop(r.Context(), session.AccessToken); err != nil {
		log.Printf("shop failed: %v", err)
		data["ShopError"] = true
	} else {
		data["Products"] = products
		data["Credit"] = credit
	}

	a.render(w, "dashboard", data)
}

// handleOrder places an order through crAPI with the user's delegated token.
func (a *App) handleOrder(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	session := a.sessions.Get(w, r)
	if !session.LoggedIn {
		http.Redirect(w, r, "/", http.StatusFound)
		return
	}
	productID, err := strconv.Atoi(r.FormValue("product_id"))
	if err != nil {
		http.Redirect(w, r, "/dashboard?notice=bad-request", http.StatusFound)
		return
	}
	if err := a.oauth.PlaceOrder(r.Context(), session.AccessToken, productID, 1); err != nil {
		log.Printf("order failed: %v", err)
		http.Redirect(w, r, "/dashboard?notice=order-failed", http.StatusFound)
		return
	}
	http.Redirect(w, r, "/dashboard?notice=ordered", http.StatusFound)
}

func (a *App) handleLogout(w http.ResponseWriter, r *http.Request) {
	session := a.sessions.Get(w, r)
	a.sessions.Destroy(w, session)
	http.Redirect(w, r, "/", http.StatusFound)
}

func (a *App) renderError(w http.ResponseWriter, title, detail string) {
	w.WriteHeader(http.StatusBadRequest)
	a.render(w, "error", map[string]interface{}{"Title": title, "Detail": detail})
}
