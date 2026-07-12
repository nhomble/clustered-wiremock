package io.nhomble.clusteredwiremock.extension;

import static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder.jsonResponse;
import static com.github.tomakehurst.wiremock.http.RequestMethod.DELETE;
import static com.github.tomakehurst.wiremock.http.RequestMethod.GET;

import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.nhomble.clusteredwiremock.journal.SharedJournal;
import java.util.List;
import java.util.Map;

// Serves the shared journal on new routes (the built-in /__admin/requests can't be fed remote events):
//   GET    /__admin/cluster/requests  — all serve events across the cluster, newest first
//   DELETE /__admin/cluster/requests  — clears the shared journal cluster-wide
public final class ClusterJournalAdminApi implements AdminApiExtension {

  private final SharedJournal journal;

  public ClusterJournalAdminApi(SharedJournal journal) {
    this.journal = journal;
  }

  @Override
  public String getName() {
    return "clustered-wiremock-journal-api";
  }

  @Override
  public void contributeAdminApiRoutes(Router router) {
    router.add(
        GET,
        "/cluster/requests",
        (admin, serveEvent, pathParams) -> {
          List<ServeEvent> events = journal.getAll();
          return jsonResponse(Map.of("total", events.size(), "requests", events), 200);
        });

    router.add(
        DELETE,
        "/cluster/requests",
        (admin, serveEvent, pathParams) -> {
          journal.clear();
          return jsonResponse(Map.of("status", "cleared"), 200);
        });
  }
}
