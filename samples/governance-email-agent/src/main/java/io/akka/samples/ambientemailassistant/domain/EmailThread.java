package io.akka.samples.ambientemailassistant.domain;

import java.util.Optional;

/**
 * State of a single email thread. Each stage of the lifecycle adds one small nested record, so the
 * fields that only exist once a stage has happened are grouped and {@link Optional} rather than
 * spread across the top level.
 */
public record EmailThread(
    String id,
    String sender,
    String subject,
    ThreadStatus status,
    Optional<Triage> triage,
    Optional<ReplyDraft> reply,
    Optional<MeetingProposal> meeting,
    Optional<Approval> approval,
    Optional<Dismissal> dismissal,
    Optional<String> actionReference) {

  public enum ThreadStatus {
    RECEIVED,
    TRIAGED,
    ACTION_DRAFTED,
    APPROVED,
    REPLY_SENT,
    MEETING_SCHEDULED,
    DISMISSED
  }

  public record Triage(String category, String urgency, String suggestedAction) {}

  public record Approval(String approvedBy, String note) {}

  public record Dismissal(String dismissedBy, String note) {}

  /** An empty thread with the given id. Used as the entity's empty state (no context reference). */
  public static EmailThread initial(String id) {
    return new EmailThread(
        id, "", "", ThreadStatus.RECEIVED,
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.empty());
  }

  public boolean isApproved() {
    return status == ThreadStatus.APPROVED;
  }

  /** A thread whose id is not known resolves to the empty state, which has no sender. */
  public boolean exists() {
    return !sender.isEmpty();
  }

  public Optional<String> suggestedAction() {
    return triage.map(Triage::suggestedAction);
  }

  public EmailThread received(String sender, String subject) {
    return new EmailThread(
        id, sender, subject, ThreadStatus.RECEIVED, triage, reply, meeting, approval, dismissal,
        actionReference);
  }

  public EmailThread triaged(Triage triage) {
    return new EmailThread(
        id, sender, subject, ThreadStatus.TRIAGED, Optional.of(triage), reply, meeting, approval,
        dismissal, actionReference);
  }

  public EmailThread replyDrafted(ReplyDraft reply) {
    return new EmailThread(
        id, sender, subject, ThreadStatus.ACTION_DRAFTED, triage, Optional.of(reply), meeting,
        approval, dismissal, actionReference);
  }

  public EmailThread meetingDrafted(MeetingProposal meeting) {
    return new EmailThread(
        id, sender, subject, ThreadStatus.ACTION_DRAFTED, triage, reply, Optional.of(meeting),
        approval, dismissal, actionReference);
  }

  public EmailThread approved(Approval approval) {
    return new EmailThread(
        id, sender, subject, ThreadStatus.APPROVED, triage, reply, meeting, Optional.of(approval),
        dismissal, actionReference);
  }

  public EmailThread dismissed(Dismissal dismissal) {
    return new EmailThread(
        id, sender, subject, ThreadStatus.DISMISSED, triage, reply, meeting, approval,
        Optional.of(dismissal), actionReference);
  }

  public EmailThread actionCompleted(ThreadStatus terminalStatus, String reference) {
    return new EmailThread(
        id, sender, subject, terminalStatus, triage, reply, meeting, approval, dismissal,
        Optional.of(reference));
  }
}
