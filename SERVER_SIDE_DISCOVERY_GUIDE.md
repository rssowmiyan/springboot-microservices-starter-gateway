# Server-Side Service Discovery Implementation Guide

## Overview

This guide explains how to implement **Server-Side Service Discovery** in your Spring Boot Gateway project using Consul + Nginx (or Traefik).

## What is Server-Side Service Discovery?

In server-side discovery, the client makes a request to a **load balancer** (the "server-side" component), which is responsible for:
1. Querying the service registry (Consul/Eureka)
2. Selecting a healthy instance
3. Forwarding the request to that instance

The client (your gateway) doesn't need discovery logic—it just routes to a single load balancer endpoint.

---

## Architecture

```
Client Request
    ↓
Gateway (Spring Boot)
    ↓
http://nginx-lb:80/product-service/
    ↓
Nginx Load Balancer (queries Consul)
    ↓
Selects healthy instance
    ↓
Product Service Instance #1, #2, or #3
```

---

## Implementation Options

### Option 1: Nginx + Consul Template (Recommended)

**Components:**
- **Consul**: Service registry (port 8500)
- **Consul Template**: Watches Consul and auto-generates Nginx config
- **Nginx**: Load balancer that forwards requests
- **Gateway**: Routes to `http://nginx-lb:80/service-name`

#### 1. docker-compose.yaml Addition

```yaml
services:
  # Service Registry
  consul:
    image: consul:1.15
    container_name: consul
    command: agent -server -ui -bootstrap-expect=1 -client=0.0.0.0 -bind=0.0.0.0
    ports:
      - "8500:8500"  # HTTP API & UI
      - "8600:8600/udp"  # DNS
    environment:
      CONSUL_BIND_INTERFACE: eth0
    healthcheck:
      test: ["CMD", "consul", "members"]
      interval: 5s
      timeout: 3s
      retries: 10

  # Load Balancer
  nginx-lb:
    image: nginx:alpine
    container_name: nginx-lb
    ports:
      - "8080:80"
    volumes:
      - nginx-config:/etc/nginx/conf.d
    depends_on:
      consul:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost/health"]
      interval: 10s
      timeout: 3s
      retries: 3

  # Config Generator
  consul-template:
    image: hashicorp/consul-template:alpine
    container_name: consul-template
    command: >
      consul-template
      -consul-addr=consul:8500
      -template="/templates/nginx.conf.tmpl:/etc/nginx/conf.d/default.conf:pkill -HUP nginx || true"
      -retry 5s
      -log-level=info
    volumes:
      - ./docker/nginx/nginx.conf.tmpl:/templates/nginx.conf.tmpl:ro
      - nginx-config:/etc/nginx/conf.d
    depends_on:
      consul:
        condition: service_healthy
      nginx-lb:
        condition: service_started

volumes:
  nginx-config:
```

#### 2. Nginx Template (docker/nginx/nginx.conf.tmpl)

Create this file:

```nginx
# Nginx configuration template for Consul-based service discovery
# Consul Template will process this and generate the actual nginx config

# Default upstream for health checks
upstream health_check {
    server 127.0.0.1:80;
}

{{range services}}
# Upstream for {{.Name}}
upstream {{.Name}}_backend {
    least_conn;  # Load balancing algorithm
    {{range service .Name}}
    server {{.Address}}:{{.Port}} max_fails=3 fail_timeout=30s;
    {{else}}
    # Fallback when no instances are available
    server 127.0.0.1:80 down;
    {{end}}
}
{{end}}

server {
    listen 80;
    server_name _;

    # Health check endpoint
    location /health {
        access_log off;
        return 200 "healthy\n";
        add_header Content-Type text/plain;
    }

    {{range services}}
    # Route for {{.Name}}
    location /{{.Name}}/ {
        proxy_pass http://{{.Name}}_backend/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # Timeouts
        proxy_connect_timeout 5s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
        
        # Error handling
        proxy_next_upstream error timeout http_502 http_503 http_504;
        proxy_next_upstream_tries 2;
    }
    {{end}}
}
```

#### 3. Gateway Routes (Routes.java)

Update your routes to point to the load balancer:

```java
@Bean
public RouterFunction<ServerResponse> productServiceRoute() {
    return route("product_service")
        .route(RequestPredicates.path("/api/product"), 
               http("http://nginx-lb:80/product-service"))
        .filter(CircuitBreakerFilterFunctions.circuitBreaker(
            "productServiceCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .build();
}

@Bean
public RouterFunction<ServerResponse> orderServiceRoute() {
    return route("order_service")
        .route(RequestPredicates.path("/api/order"), 
               http("http://nginx-lb:80/order-service"))
        .filter(CircuitBreakerFilterFunctions.circuitBreaker(
            "orderServiceCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .build();
}

@Bean
public RouterFunction<ServerResponse> inventoryServiceRoute() {
    return route("inventory_service")
        .route(RequestPredicates.path("/api/inventory"), 
               http("http://nginx-lb:80/inventory-service"))
        .filter(CircuitBreakerFilterFunctions.circuitBreaker(
            "inventoryServiceCircuitBreaker",
            URI.create("forward:/fallbackRoute")))
        .build();
}
```

#### 4. No Code Changes Needed in Gateway

✅ No Eureka/Consul client dependencies
✅ No `@EnableDiscoveryClient` annotation
✅ No service discovery logic in code
✅ Gateway just routes to a single load balancer endpoint

---

### Option 2: Traefik + Consul (Modern Alternative)

Traefik is a modern reverse proxy with built-in Consul integration.

#### docker-compose.yaml

```yaml
services:
  consul:
    image: consul:1.15
    command: agent -server -ui -bootstrap-expect=1 -client=0.0.0.0
    ports:
      - "8500:8500"

  traefik:
    image: traefik:v2.10
    container_name: traefik
    command:
      - --api.insecure=true
      - --providers.consulcatalog=true
      - --providers.consulcatalog.endpoint.address=consul:8500
      - --providers.consulcatalog.exposedByDefault=false
    ports:
      - "8080:80"
      - "8090:8080"  # Traefik dashboard
    depends_on:
      - consul
```

#### Gateway Routes

```java
http("http://traefik:80/product-service")
```

#### Service Registration with Traefik Tags

When registering services with Consul, add Traefik tags:

```json
{
  "Name": "product-service",
  "Tags": [
    "traefik.enable=true",
    "traefik.http.routers.product.rule=PathPrefix(`/product-service`)"
  ]
}
```

---

## Advantages of Server-Side Discovery

### ✅ Simpler Gateway Code
- No discovery client libraries
- No service resolution logic
- Just route to load balancer

### ✅ Language-Agnostic
- Any service (Python, Go, Node.js, .NET) can register with Consul
- No need for Spring Cloud dependencies in every service

### ✅ Centralized Load Balancing
- One place to configure algorithms (round-robin, least-conn, IP hash)
- Centralized health checks
- Easier to debug and monitor

### ✅ Better Operational Control
- Ops teams can manage load balancer separately
- Can update routing rules without redeploying gateway
- Network-level routing (TCP/UDP, not just HTTP)

---

## Disadvantages

### ❌ Additional Infrastructure
- Need to run and maintain load balancer
- More containers to manage

### ❌ Single Point of Failure
- Load balancer becomes critical path
- Need HA setup for production

### ❌ Extra Network Hop
- Request goes through load balancer
- Slight increase in latency

### ❌ More Complexity
- More moving parts to configure
- Need to understand Nginx/Traefik configuration

---

## Verification Steps

### 1. Start Infrastructure

```powershell
# Start Consul, Nginx, and Consul Template
docker compose up -d consul nginx-lb consul-template

# Verify Consul is running
Start-Process "http://localhost:8500"
```

### 2. Register a Service

See [DOWNSTREAM_SERVICE_SETUP.md](./DOWNSTREAM_SERVICE_SETUP.md) for details.

Quick manual registration:

```powershell
$body = @{
    Name = "product-service"
    Port = 9080
    Address = "host.containers.internal"
    Check = @{
        HTTP = "http://host.containers.internal:9080/health"
        Interval = "10s"
    }
} | ConvertTo-Json

Invoke-RestMethod -Method PUT `
    -Uri "http://localhost:8500/v1/agent/service/register" `
    -Body $body `
    -ContentType "application/json"
```

### 3. Verify Nginx Configuration

```powershell
# Wait for consul-template to regenerate config
Start-Sleep -Seconds 10

# Check Nginx upstream
docker exec nginx-lb cat /etc/nginx/conf.d/default.conf
```

Should show:

```nginx
upstream product-service_backend {
    least_conn;
    server host.containers.internal:9080 max_fails=3 fail_timeout=30s;
}
```

### 4. Test Routing

```powershell
# Direct to Nginx
curl http://localhost:8080/product-service/api/product

# Through Gateway (if gateway routes to nginx-lb)
curl http://localhost:9001/api/product
```

---

## Monitoring & Troubleshooting

### Check Consul Services

```powershell
# List all services
Invoke-RestMethod -Uri "http://localhost:8500/v1/catalog/services" | ConvertTo-Json

# Check specific service
Invoke-RestMethod -Uri "http://localhost:8500/v1/catalog/service/product-service" | ConvertTo-Json
```

### Check Consul Template Logs

```powershell
docker logs consul-template -f
```

Should show:
```
rendered config
reloading nginx
```

### Check Nginx Logs

```powershell
# Access logs
docker logs nginx-lb

# Error logs
docker exec nginx-lb cat /var/log/nginx/error.log
```

### Test Nginx Directly

```powershell
# Health check
curl http://localhost:8080/health

# Service endpoint
curl http://localhost:8080/product-service/health
```

---

## Load Balancing Algorithms

Nginx supports several algorithms (configured in nginx.conf.tmpl):

### 1. Round Robin (default)
```nginx
upstream backend {
    server instance1:9080;
    server instance2:9080;
}
```

### 2. Least Connections
```nginx
upstream backend {
    least_conn;
    server instance1:9080;
    server instance2:9080;
}
```

### 3. IP Hash (sticky sessions)
```nginx
upstream backend {
    ip_hash;
    server instance1:9080;
    server instance2:9080;
}
```

### 4. Weighted
```nginx
upstream backend {
    server instance1:9080 weight=3;
    server instance2:9080 weight=1;
}
```

---

## Production Considerations

### 1. High Availability

Run multiple Nginx instances behind a cloud load balancer:

```yaml
  nginx-lb-1:
    image: nginx:alpine
    # ... config

  nginx-lb-2:
    image: nginx:alpine
    # ... same config
```

### 2. Security

- Enable TLS between gateway and load balancer
- Secure Consul with ACLs
- Restrict Nginx access to internal network

### 3. Monitoring

- Add Prometheus exporter for Nginx
- Monitor Consul health
- Set up alerts for service unavailability

### 4. Caching

Add caching to Nginx for frequently accessed endpoints:

```nginx
proxy_cache_path /var/cache/nginx levels=1:2 keys_zone=api_cache:10m;

location /product-service/ {
    proxy_cache api_cache;
    proxy_cache_valid 200 5m;
    # ... other config
}
```

---

## Comparison: Client-Side vs Server-Side

| Aspect | Client-Side (Eureka) | Server-Side (Nginx+Consul) |
|--------|---------------------|---------------------------|
| Gateway Code | Complex (discovery logic) | Simple (static LB URL) |
| Dependencies | Spring Cloud libs | None |
| Language Support | Java/Spring only | Any language |
| Load Balancing | In-app (Spring Cloud LB) | Nginx (battle-tested) |
| Network Hops | Direct to service | Via load balancer |
| Failure Mode | Client handles | LB handles |
| Operational | Developer-focused | Ops-friendly |

---

## When to Use Server-Side Discovery

✅ Polyglot microservices (Python, Go, Node.js, etc.)
✅ Need centralized routing control
✅ Large-scale deployments with ops team
✅ Existing Nginx/HAProxy infrastructure
✅ Advanced routing (path rewriting, rate limiting)

## When to Use Client-Side Discovery

✅ All services are Spring Boot
✅ Small team, simple architecture
✅ Want to minimize infrastructure
✅ Need request-level service selection logic

---

## References

- [Consul Service Discovery](https://www.consul.io/docs/discovery/services)
- [Consul Template Documentation](https://github.com/hashicorp/consul-template)
- [Nginx Reverse Proxy](https://docs.nginx.com/nginx/admin-guide/web-server/reverse-proxy/)
- [Traefik with Consul](https://doc.traefik.io/traefik/providers/consul-catalog/)
- [DOWNSTREAM_SERVICE_SETUP.md](./DOWNSTREAM_SERVICE_SETUP.md)

