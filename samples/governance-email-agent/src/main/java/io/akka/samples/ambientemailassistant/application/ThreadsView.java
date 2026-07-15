package io.akka.samples.ambientemailassistant.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.samples.ambientemailassistant.domain.EmailThread.ThreadStatus;
import io.akka.samples.ambientemailassistant.domain.EmailThreadEvent;
import io.akka.samples.ambientemailassistant.domain.EmailThreadEvent.MeetingDrafted;
import io.akka.samples.ambientemailassistant.domain.EmailThreadEvent.MeetingScheduled;
import io.akka.samples.ambientemailassistant.domain.EmailThreadEvent.ReplyDrafted;
import io.akka.samples.ambientemailassistant.domain.EmailThreadEvent.ReplySent;
import io.akka.samples.ambientemailassistant.domain.EmailThreadEvent.ThreadApproved;
import io.akka.samples.ambientemailassistant.domain.EmailThreadEvent.ThreadDismissed;
import io.akka.samples.ambientemailassistant.domain.EmailThreadEvent.ThreadReceived;
import io.akka.samples.ambientemailassistant.domain.EmailThreadEvent.ThreadTriaged;
import java.util.List;

@Component(id = "threads-view")
public class ThreadsView extends View {

  /** A compact read-model row for listing threads — not the full entity state. */
  public record ThreadSummary(
      String id, String status, String subject, String category, String suggestedAction) {

    ThreadSummary withStatus(ThreadStatus status) {
      return new ThreadSummary(id, status.name(), subject, category, suggestedAction);
    }
  }

  public record Threads(List<ThreadSummary> threads) {}

  // A single query. Akka cannot auto-index enum columns, so there is no WHERE status filter here;
  // callers filter client-side.
  @Query("SELECT * AS threads FROM threads_view")
  public QueryEffect<Threads> getAllThreads() {
    return queryResult();
  }

  @Consume.FromEventSourcedEntity(EmailThreadEntity.class)
  public static class ThreadsUpdater extends TableUpdater<ThreadSummary> {

    public Effect<ThreadSummary> onEvent(EmailThreadEvent event) {
      var id = updateContext().eventSubject().orElseThrow();
      var row = rowState() == null ? new ThreadSummary(id, "", "", "", "") : rowState();
      var updated =
          switch (event) {
            case ThreadReceived e -> new ThreadSummary(
                id, ThreadStatus.RECEIVED.name(), e.subject(), row.category(), row.suggestedAction());
            case ThreadTriaged e -> new ThreadSummary(
                id, ThreadStatus.TRIAGED.name(), row.subject(), e.category(), e.suggestedAction());
            case ReplyDrafted e -> row.withStatus(ThreadStatus.ACTION_DRAFTED);
            case MeetingDrafted e -> row.withStatus(ThreadStatus.ACTION_DRAFTED);
            case ThreadApproved e -> row.withStatus(ThreadStatus.APPROVED);
            case ThreadDismissed e -> row.withStatus(ThreadStatus.DISMISSED);
            case ReplySent e -> row.withStatus(ThreadStatus.REPLY_SENT);
            case MeetingScheduled e -> row.withStatus(ThreadStatus.MEETING_SCHEDULED);
          };
      return effects().updateRow(updated);
    }
  }
}
