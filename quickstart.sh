#!/bin/bash

# crAPI Hacking Lab - One-Line Quick Setup
# Automated deployment for immediate API security testing
# Usage: ./quickstart.sh [--vulnerable] [--localhost-only]

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# Parse arguments
ENABLE_VULNERABILITIES=false
LOCALHOST_ONLY=false

for arg in "$@"; do
    case $arg in
        --vulnerable)
            ENABLE_VULNERABILITIES=true
            shift
            ;;
        --localhost-only)
            LOCALHOST_ONLY=true
            shift
            ;;
        --help)
            echo "crAPI Hacking Lab Quick Setup"
            echo ""
            echo "Usage: $0 [options]"
            echo ""
            echo "Options:"
            echo "  --vulnerable      Enable all vulnerabilities (shell injection, log4j)"
            echo "  --localhost-only  Bind only to localhost (default: all interfaces)"
            echo "  --help           Show this help message"
            echo ""
            echo "Examples:"
            echo "  $0                    # Standard setup with external access"
            echo "  $0 --vulnerable       # Enable all vulnerabilities"
            echo "  $0 --localhost-only   # Restrict to localhost only"
            exit 0
            ;;
    esac
done

echo -e "${CYAN}╔════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║        crAPI Hacking Lab - Automated Setup            ║${NC}"
echo -e "${CYAN}║           OWASP API Top 10 Testing Environment        ║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════╝${NC}"
echo ""

# Check Docker
if ! command -v docker &> /dev/null; then
    echo -e "${RED}[ERROR]${NC} Docker is not installed!"
    echo "Please install Docker first: https://docs.docker.com/get-docker/"
    exit 1
fi

if ! docker compose version &> /dev/null 2>&1; then
    echo -e "${RED}[ERROR]${NC} Docker Compose is not installed or too old!"
    echo "Please update Docker Compose to version 1.27.0 or higher"
    exit 1
fi

echo -e "${GREEN}[✓]${NC} Docker and Docker Compose detected"

# Navigate to docker directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR/deploy/docker"

# Pull latest images
echo -e "${CYAN}[1/4]${NC} Pulling latest crAPI images..."
docker compose pull

# Set configuration
if [ "$LOCALHOST_ONLY" = true ]; then
    LISTEN_IP="127.0.0.1"
    echo -e "${YELLOW}[CONFIG]${NC} Binding to localhost only"
else
    LISTEN_IP="0.0.0.0"
    echo -e "${YELLOW}[CONFIG]${NC} Binding to all network interfaces"
fi

if [ "$ENABLE_VULNERABILITIES" = true ]; then
    ENABLE_SHELL_INJECTION="true"
    ENABLE_LOG4J="true"
    echo -e "${YELLOW}[WARNING]${NC} All vulnerabilities enabled - Maximum risk configuration!"
else
    ENABLE_SHELL_INJECTION="false"
    ENABLE_LOG4J="false"
    echo -e "${GREEN}[CONFIG]${NC} Standard vulnerability configuration"
fi

# Stop any existing instances
echo -e "${CYAN}[2/4]${NC} Cleaning up any existing instances..."
docker compose down -v 2>/dev/null || true

# Start crAPI
echo -e "${CYAN}[3/4]${NC} Starting crAPI hacking lab..."
LISTEN_IP="$LISTEN_IP" \
ENABLE_SHELL_INJECTION="$ENABLE_SHELL_INJECTION" \
ENABLE_LOG4J="$ENABLE_LOG4J" \
TLS_ENABLED="true" \
docker compose -f docker-compose.yml --compatibility up -d

# Wait for services
echo -e "${CYAN}[4/4]${NC} Waiting for services to be ready..."
sleep 15

# Health check
HEALTHY=true
if curl -s -f http://localhost:8888/health > /dev/null 2>&1; then
    echo -e "${GREEN}[✓]${NC} Web interface is ready"
else
    echo -e "${YELLOW}[!]${NC} Web interface not responding yet"
    HEALTHY=false
fi

# Check individual services
for service in crapi-identity crapi-community crapi-workshop; do
    if docker compose exec -T $service /app/health.sh > /dev/null 2>&1; then
        echo -e "${GREEN}[✓]${NC} $service is healthy"
    else
        echo -e "${YELLOW}[!]${NC} $service is still starting..."
        HEALTHY=false
    fi
done

echo ""
echo -e "${GREEN}════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}       crAPI Hacking Lab Successfully Deployed!         ${NC}"
echo -e "${GREEN}════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${CYAN}Access Points:${NC}"
echo -e "  Web UI:        ${GREEN}http://localhost:8888${NC}"
echo -e "  Web UI (TLS):  ${GREEN}https://localhost:8443${NC}"
echo -e "  Mailhog:       ${GREEN}http://localhost:8025${NC}"
echo ""
echo -e "${CYAN}API Endpoints:${NC}"
echo -e "  Identity:      http://localhost:8080"
echo -e "  Community:     http://localhost:8087"
echo -e "  Workshop:      http://localhost:8000"
echo -e "  Chatbot:       http://localhost:5002"
echo ""
echo -e "${CYAN}Default Credentials:${NC}"
echo -e "  Email:         ${YELLOW}admin@example.com${NC}"
echo -e "  Password:      ${YELLOW}Admin!123${NC}"
echo ""
echo -e "${CYAN}Quick Commands:${NC}"
echo -e "  View logs:     ${YELLOW}cd deploy/docker && docker compose logs -f${NC}"
echo -e "  Stop lab:      ${YELLOW}cd deploy/docker && docker compose down${NC}"
echo -e "  Clean stop:    ${YELLOW}cd deploy/docker && docker compose down -v${NC}"
echo ""
echo -e "${CYAN}Documentation:${NC}"
echo -e "  Challenges:    ${YELLOW}$SCRIPT_DIR/docs/challenges.md${NC}"
echo -e "  API Specs:     ${YELLOW}$SCRIPT_DIR/openapi-spec/${NC}"
echo -e "  Postman:       ${YELLOW}$SCRIPT_DIR/postman_collections/${NC}"
echo ""

if [ "$HEALTHY" = false ]; then
    echo -e "${YELLOW}Note: Some services are still starting. Wait a moment and try again.${NC}"
    echo -e "${YELLOW}You can check status with: docker compose ps${NC}"
fi

echo -e "${GREEN}Happy Hacking! 🎯${NC}"
echo ""
echo -e "${CYAN}For more options, run: ./crapi-dev.sh${NC}"