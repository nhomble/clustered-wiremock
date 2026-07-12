package io.nhomble.clusteredwiremock;

import java.util.Arrays;
import java.util.List;

// How a WireMock node joins the cluster. Map names must match across nodes. Discovery, in precedence:
// kubernetesServiceName set => Kubernetes API discovery (pod IPs from a headless Service); else
// non-empty members => TCP/IP to those host[:port] peers (containers/CI); else multicast (local dev).
public record ClusterConfig(
    String clusterName,
    String journalMapName,
    String stubMapName,
    List<String> members,
    String kubernetesServiceName,
    int port) {

  public static final String DEFAULT_CLUSTER_NAME = "clustered-wiremock";
  public static final String DEFAULT_JOURNAL_MAP = "wiremock-request-journal";
  public static final String DEFAULT_STUB_MAP = "wiremock-stubs";
  public static final int DEFAULT_PORT = 5701;

  public ClusterConfig {
    members = members == null ? List.of() : List.copyOf(members);
  }

  /** Local-dev defaults: multicast discovery, standard names and port. */
  public static ClusterConfig defaults() {
    return new ClusterConfig(
        DEFAULT_CLUSTER_NAME, DEFAULT_JOURNAL_MAP, DEFAULT_STUB_MAP, List.of(), null, DEFAULT_PORT);
  }

  /** Fixed peers discovered over TCP/IP (container/CI friendly). */
  public static ClusterConfig withMembers(List<String> members) {
    return new ClusterConfig(
        DEFAULT_CLUSTER_NAME, DEFAULT_JOURNAL_MAP, DEFAULT_STUB_MAP, members, null, DEFAULT_PORT);
  }

  /** Kubernetes discovery against a headless Service selecting the pods. */
  public static ClusterConfig withKubernetesService(String serviceName) {
    return new ClusterConfig(
        DEFAULT_CLUSTER_NAME, DEFAULT_JOURNAL_MAP, DEFAULT_STUB_MAP, List.of(), serviceName,
        DEFAULT_PORT);
  }

  // From WIREMOCK_CLUSTER_NAME/_PORT/_MEMBERS/_K8S_SERVICE_NAME, WIREMOCK_JOURNAL_MAP,
  // WIREMOCK_STUB_MAP (all optional). Deployment surface for the runner and the auto-loaded extension.
  public static ClusterConfig fromEnv() {
    return new ClusterConfig(
        envOr("WIREMOCK_CLUSTER_NAME", DEFAULT_CLUSTER_NAME),
        envOr("WIREMOCK_JOURNAL_MAP", DEFAULT_JOURNAL_MAP),
        envOr("WIREMOCK_STUB_MAP", DEFAULT_STUB_MAP),
        parseMembers(System.getenv("WIREMOCK_CLUSTER_MEMBERS")),
        envOrNull("WIREMOCK_CLUSTER_K8S_SERVICE_NAME"),
        envInt("WIREMOCK_CLUSTER_PORT", DEFAULT_PORT));
  }

  public boolean usesKubernetes() {
    return kubernetesServiceName != null && !kubernetesServiceName.isBlank();
  }

  public boolean usesMulticast() {
    return members.isEmpty() && !usesKubernetes();
  }

  private static List<String> parseMembers(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  private static String envOr(String key, String fallback) {
    String v = System.getenv(key);
    return v == null || v.isBlank() ? fallback : v;
  }

  private static String envOrNull(String key) {
    String v = System.getenv(key);
    return v == null || v.isBlank() ? null : v;
  }

  private static int envInt(String key, int fallback) {
    String v = System.getenv(key);
    return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
  }
}
