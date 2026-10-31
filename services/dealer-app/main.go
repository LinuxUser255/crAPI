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

// DealerConnect: a third-party car-dealership portal that signs users in with
// crAPI over OAuth 2.0 (container dealer-connect, port 8900).
//
// It is crAPI's OAuth client. "Log in with crAPI" sends the browser to crAPI's
// consent page; the callback checks the state value, exchanges the code at the
// token endpoint with the client secret, and then calls crAPI's APIs on the
// user's behalf with the access token: profile, vehicles, the parts shop.
//
// Two crAPI addresses are configured on purpose. CRAPI_PUBLIC_URL is what the
// user's browser reaches (localhost:8889); CRAPI_INTERNAL_URL is what this
// server reaches over the Docker network (http://crapi-web). Tokens stay on the
// server; the browser holds only a session cookie.
package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"time"
)

// Config is read from the environment; see deploy/docker/docker-compose.yml.
type Config struct {
	ListenAddr       string
	CrapiPublicURL   string
	CrapiInternalURL string
	ClientID         string
	ClientSecret     string
	RedirectURI      string
	Scope            string
}

func getenv(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func loadConfig() Config {
	return Config{
		ListenAddr:       getenv("LISTEN_ADDR", ":8900"),
		CrapiPublicURL:   strings.TrimRight(getenv("CRAPI_PUBLIC_URL", "http://localhost:8889"), "/"),
		CrapiInternalURL: strings.TrimRight(getenv("CRAPI_INTERNAL_URL", "http://crapi-web"), "/"),
		ClientID:         getenv("OAUTH_CLIENT_ID", "dealer-connect"),
		ClientSecret:     getenv("OAUTH_CLIENT_SECRET", "DealerConnect!Secret123"),
		RedirectURI:      getenv("OAUTH_REDIRECT_URI", "http://localhost:8900/oauth/callback"),
		Scope:            getenv("OAUTH_SCOPE", "profile read:vehicles write:orders"),
	}
}

// healthcheck probes the running server; used as the container's healthcheck so
// the distroless image needs no shell or curl.
func healthcheck(addr string) int {
	if strings.HasPrefix(addr, ":") {
		addr = "127.0.0.1" + addr
	}
	resp, err := http.Get("http://" + addr + "/healthz")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}

func main() {
	config := loadConfig()

	if len(os.Args) > 1 && os.Args[1] == "-healthcheck" {
		os.Exit(healthcheck(config.ListenAddr))
	}

	app, err := NewApp(config)
	if err != nil {
		log.Fatalf("DealerConnect failed to start: %v", err)
	}

	server := &http.Server{
		Addr:              config.ListenAddr,
		Handler:           app.Routes(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("DealerConnect listening on %s (crAPI browser=%s server=%s)",
		config.ListenAddr, config.CrapiPublicURL, config.CrapiInternalURL)
	log.Fatal(server.ListenAndServe())
}
