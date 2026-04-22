# How to safely shutdown crAPI containers

---

## Check the Container Status

```bash
# See all running crAPI containers and their health
docker ps --filter "name=crapi" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

# More detail on a specific container
docker inspect --format '{{.Name}} {{.State.Health.Status}}' $(docker ps -q)

# Check logs if something looks off
docker compose -f deploy/docker/docker-compose.yml logs --tail=50
```

---

## Useful curl Checks Before Shutdown

```bash
# Web UI (already confirmed working)
curl -s -o /dev/null -w "%{http_code}" http://localhost:8888

# Identity API (your newly changed port)
curl -s -o /dev/null -w "%{http_code}" http://localhost:8090

# Community service
curl -s -o /dev/null -w "%{http_code}" http://localhost:8087

# HTTPS — skip cert verification for self-signed
curl -k -s -o /dev/null -w "%{http_code}" https://localhost:8443
```

The `-s -o /dev/null -w "%{http_code}"` pattern gives you a clean HTTP status code with no noise — useful for scripted health checks.

---

## Shut It Down

```bash
# Clean shutdown — stops and removes containers, networks
docker compose -f deploy/docker/docker-compose.yml down

# If you also want to wipe the volumes (resets all crAPI data)
docker compose -f deploy/docker/docker-compose.yml down -v

# Nuclear option — also removes the pulled images
docker compose -f deploy/docker/docker-compose.yml down -v --rmi all
```

---

## Verify It's Gone

```bash
# Should return nothing
docker ps --filter "name=crapi"

# Confirm no orphaned networks left
docker network ls | grep crapi
```

Use plain `down` for a normal stop — save `down -v` for when you want a clean slate before the next `quickstart.sh` run.


