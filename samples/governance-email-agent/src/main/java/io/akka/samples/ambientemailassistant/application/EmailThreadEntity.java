package io.akka.samples.ambientemailassistant.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.samples.ambientemailassistant.domain.ApprovalDecision;
import io.akka.samples.ambientemailassistant.domain.DismissDecision;
import io.akka.samples.ambientemailassistant.domain.EmailClassification;
import io.akka.samples.ambientemailassistant.domain.EmailThread;
import io.akka.samples.ambientemailassistant.domain.EmailThread.Approval;
import io.akka.samples.ambientemailassistant.domain.EmailThread.Dismissal;
import io.akka.samples.ambientemailassistant.domain.EmailThread.ThreadStatus;
import io.akka.samples.ambientemailassistant.domain.EmailThread.Triage;
import io.akka.samples.ambientemailassistant.domain.EmailThreadEvent;
import io.akka.samples.ambientemailassistant.domain.EmailThreadEvent.MeetingDrafted;
import io.akka.samples.ambientemailassistant.domain.EmailThreadEvent.MeetingScheduled;
import io.akka.samples.ambientemailassistant.domain.EmailThreadEvent.ReplyDrafted;
import io.akka.samples.ambientemailassistant.domain.EmailThreadEvent.ReplySent;
import io.akka.samples.ambientemailassistant.domain.EmailThreadEvent.ThreadApproved;
import io.akka.samples.ambientemailassistant.domain.EmailThreadEvent.ThreadDismissed;
import io.akka.samples.ambientemailassistant.domain.EmailThreadEvent.ThreadReceived;
import io.akka.samples.ambientemailassistant.domain.EmailThreadEvent.ThreadTriaged;
import io.akka.samples.ambientemailassistant.domain.MeetingProposal;
import io.akka.samples.ambientemailassistant.domain.ReplyDraft;

@Component(id = "email-thread")
public class EmailThreadEntity extends EventSourcedEntity<EmailThread, EmailThreadEvent> {

  public record IncomingEmail(String sender, String subject, String body) {}

  @Override
  public EmailThread emptyState() {
    return EmailThread.initial(""); // no commandContext() reference in emptyState
  }

  public Effect<Done> receive(IncomingEmail email) {
    return effects()
        .persist(new ThreadReceived(email.sender(), email.subject()))
        .thenReply(__ -> Done.done());
  }

  public Effect<Done> recordTriage(EmailClassification classification) {
    return effects()
        .persist(
            new ThreadTriaged(
                classification.category(),
                classification.urgency(),
                classification.suggestedAction()))
        .thenReply(__ -> Done.done());
  }

  public Effect<Done> recordReplyDraft(ReplyDraft draft) {
    return effects()
        .persist(new ReplyDrafted(draft.subject(), draft.body()))
        .thenReply(__ -> Done.done());
  }

  public Effect<Done> recordMeetingDraft(MeetingProposal proposal) {
    return effects()
        .persist(
            new MeetingDrafted(proposal.title(), proposal.proposedTime(), proposal.attendees()))
        .thenReply(__ -> Done.done());
  }

  public Effect<Done> approve(ApprovalDecision decision) {
    if (currentState().status() != ThreadStatus.ACTION_DRAFTED) {
      return effects()
          .error("Only an ACTION_DRAFTED thread can be approved, was " + currentState().status());
    }
    return effects()
        .persist(new ThreadApproved(decision.approvedBy(), decision.note()))
        .thenReply(__ -> Done.done());
  }

  public Effect<Done> dismiss(DismissDecision decision) {
    if (currentState().status() == ThreadStatus.DISMISSED) {
      return effects().reply(Done.done());
    }
    return effects()
        .persist(new ThreadDismissed(decision.dismissedBy(), decision.note()))
        .thenReply(__ -> Done.done());
  }

  public Effect<Done> recordActionComplete(String actionReference) {
    var event =
        currentState().meeting().isPresent()
            ? new MeetingScheduled(actionReference)
            : new ReplySent(actionReference);
    return effects().persist(event).thenReply(__ -> Done.done());
  }

  public Effect<EmailThread> getThread() {
    return effects().reply(currentState());
  }

  @Override
  public EmailThread applyEvent(EmailThreadEvent event) {
    return switch (event) {
      case ThreadReceived e -> currentState().received(e.sender(), e.subject());
      case ThreadTriaged e -> currentState()
          .triaged(new Triage(e.category(), e.urgency(), e.suggestedAction()));
      case ReplyDrafted e -> currentState().replyDrafted(new ReplyDraft(e.subject(), e.body()));
      case MeetingDrafted e -> currentState()
          .meetingDrafted(new MeetingProposal(e.title(), e.time(), e.attendees()));
      case ThreadApproved e -> currentState().approved(new Approval(e.approvedBy(), e.note()));
      case ThreadDismissed e -> currentState()
          .dismissed(new Dismissal(e.dismissedBy(), e.note()));
      case ReplySent e -> currentState()
          .actionCompleted(ThreadStatus.REPLY_SENT, e.actionReference());
      case MeetingScheduled e -> currentState()
          .actionCompleted(ThreadStatus.MEETING_SCHEDULED, e.actionReference());
    };
  }
}
