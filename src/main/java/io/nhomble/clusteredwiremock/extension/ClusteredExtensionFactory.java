package io.nhomble.clusteredwiremock.extension;

import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import com.hazelcast.core.HazelcastInstance;
import io.nhomble.clusteredwiremock.ClusterConfig;
import io.nhomble.clusteredwiremock.HazelcastInstances;
import io.nhomble.clusteredwiremock.journal.SharedJournal;
import java.util.List;

// ServiceLoader entry point (META-INF/services/...ExtensionFactory), auto-discovered when the jar is on
// a stock WireMock's classpath — no --extensions flag. Joins the cluster and returns the three
// extensions that make WireMock cluster-aware.
public final class ClusteredExtensionFactory implements ExtensionFactory {

  private final HazelcastInstance provided;

  // Used by ServiceLoader in standalone/Docker: reads cluster settings from the environment and owns
  // the Hazelcast member it creates.
  public ClusteredExtensionFactory() {
    this.provided = null;
  }

  // For embedding (e.g. tests): use a caller-managed Hazelcast instance; its lifecycle stays the
  // caller's. Wire in with WireMockConfiguration.extensions(new ClusteredExtensionFactory(hz)).
  public ClusteredExtensionFactory(HazelcastInstance hazelcast) {
    this.provided = hazelcast;
  }

  @Override
  public List<Extension> create(WireMockServices services) {
    ClusterConfig cluster = ClusterConfig.fromEnv();
    HazelcastInstance hazelcast = provided;
    if (hazelcast == null) {
      hazelcast = HazelcastInstances.newMember(cluster);
      Runtime.getRuntime().addShutdownHook(new Thread(hazelcast::shutdown));
    }

    SharedJournal journal = new SharedJournal(hazelcast, cluster.journalMapName());

    return List.of(
        new StubReplicator(hazelcast, cluster.stubMapName(), services.getAdmin()),
        new JournalExporter(journal),
        new ClusterJournalAdminApi(journal));
  }
}
