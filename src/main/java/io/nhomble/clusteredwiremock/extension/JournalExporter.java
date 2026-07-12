package io.nhomble.clusteredwiremock.extension;

import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.nhomble.clusteredwiremock.journal.SharedJournal;

// Adds every completed serve event to the shared journal. WireMock has no API to insert events into
// another node's native journal, so each node exports here and ClusterJournalAdminApi reads the union.
public final class JournalExporter implements ServeEventListener {

  private final SharedJournal journal;

  public JournalExporter(SharedJournal journal) {
    this.journal = journal;
  }

  @Override
  public String getName() {
    return "clustered-wiremock-journal-exporter";
  }

  @Override
  public boolean applyGlobally() {
    return true;
  }

  @Override
  public void afterComplete(ServeEvent serveEvent, Parameters parameters) {
    journal.add(serveEvent);
  }
}
