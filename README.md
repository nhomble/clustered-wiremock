# clustered-wiremock

A [WireMock](https://wiremock.org) extension that clusters WireMock instances so they share state —
**stub mappings** and the **request journal** — through an embedded [Hazelcast](https://hazelcast.com)
grid. No external datastore: the WireMock nodes *are* the cluster.

Behind a load balancer, stock WireMock nodes don't share anything: a stub added on one node 404s on
another, and each journal only sees its own traffic. This extension fixes both.

- **Shared stubs** — a stub registered on any node (Java client or admin API) is replicated into every
  node's native store, so it matches everywhere.
- **Shared journal** — every node's requests are pooled; read the cluster-wide view at
  `GET /__admin/cluster/requests`.

## Install

Drop the extension jar onto a stock WireMock's classpath — ServiceLoader auto-loads it, no flags. See
the example [`Dockerfile`](Dockerfile):

```dockerfile
FROM wiremock/wiremock:3.13.2
COPY target/clustered-wiremock-*-extension.jar /var/wiremock/extensions/clustered-wiremock.jar
```

```bash
mvn package                       # builds target/clustered-wiremock-*-extension.jar
docker build -t clustered-wiremock .
docker compose up                 # two clustered nodes — see docker-compose.yml
```

Discovery: `WIREMOCK_CLUSTER_MEMBERS` (comma-separated `host[:port]`) for fixed peers,
`WIREMOCK_CLUSTER_K8S_SERVICE_NAME` for [Kubernetes](docs/how-to.md#run-on-kubernetes-deployment--replicaset),
or neither for multicast (local dev). Requires WireMock 3.13.x; the jar targets Java 17.

## Verify it

```bash
curl -X POST localhost:8081/__admin/mappings \
  -d '{"request":{"method":"GET","url":"/hi"},"response":{"status":200,"body":"hi"}}'
curl localhost:8082/hi                        # served by the other node -> hi
curl localhost:8082/__admin/cluster/requests  # sees the whole cluster's traffic
```

## Docs

Full documentation in [`docs/`](docs/) ([Diátaxis](https://diataxis.fr)):
[Tutorial](docs/tutorial.md) · [How-to](docs/how-to.md) · [Reference](docs/reference.md) ·
[Explanation](docs/explanation.md) (how it works, how Hazelcast is used, and the design tradeoffs).

## License

[MIT](LICENSE).
