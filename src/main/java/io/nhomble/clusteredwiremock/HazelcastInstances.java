package io.nhomble.clusteredwiremock;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

// Builds an embedded Hazelcast member from a ClusterConfig — the WireMock node itself IS the cluster
// member, so there's no external datastore. Callers with their own Hazelcast can skip this and pass
// their instance straight to ClusteredWireMock.
public final class HazelcastInstances {

  private HazelcastInstances() {}

  public static HazelcastInstance newMember(ClusterConfig cluster) {
    Config config = new Config();
    config.setClusterName(cluster.clusterName());
    // Use JDK logging (always present) rather than SLF4J: when this runs as an extension inside the
    // stock WireMock standalone image, SLF4J is shaded/relocated and not resolvable at this coordinate.
    config.setProperty("hazelcast.logging.type", "jdk");
    config.setProperty("hazelcast.phone.home.enabled", "false");
    config.getNetworkConfig().setPort(cluster.port()).setPortAutoIncrement(true);

    JoinConfig join = config.getNetworkConfig().getJoin();
    join.getAutoDetectionConfig().setEnabled(false);
    join.getMulticastConfig().setEnabled(false);
    join.getTcpIpConfig().setEnabled(false);

    if (cluster.usesKubernetes()) {
      // Kubernetes API discovery: query the headless Service's endpoints for peer pod IPs.
      // resolve-not-ready-addresses lets pods find each other before they pass readiness, so the
      // cluster can form at startup. Namespace + API server/token come from the pod automatically.
      join.getKubernetesConfig()
          .setEnabled(true)
          .setProperty("service-name", cluster.kubernetesServiceName())
          .setProperty("resolve-not-ready-addresses", "true");
    } else if (cluster.usesMulticast()) {
      join.getMulticastConfig().setEnabled(true);
    } else {
      join.getTcpIpConfig().setEnabled(true).setMembers(cluster.members());
    }

    return Hazelcast.newHazelcastInstance(config);
  }
}
