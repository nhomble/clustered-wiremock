package io.nhomble.clusteredwiremock.journal;

import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.DataSerializable;
import java.io.IOException;

// A journal map value: the serve event as JSON plus a time-ordered ULID for cluster-wide ordering.
// DataSerializable so Hazelcast moves it between members without registration or Java serialization.
public final class JournalEntry implements DataSerializable {

  private String ulid;
  private String eventJson;

  public JournalEntry() {} // for Hazelcast deserialization

  public JournalEntry(String ulid, String eventJson) {
    this.ulid = ulid;
    this.eventJson = eventJson;
  }

  public String ulid() {
    return ulid;
  }

  public String eventJson() {
    return eventJson;
  }

  @Override
  public void writeData(ObjectDataOutput out) throws IOException {
    out.writeString(ulid);
    out.writeString(eventJson);
  }

  @Override
  public void readData(ObjectDataInput in) throws IOException {
    ulid = in.readString();
    eventJson = in.readString();
  }
}
