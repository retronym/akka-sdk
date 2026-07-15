package io.akka.samples.ambientemailassistant.domain;

import akka.javasdk.annotations.TypeName;

public sealed interface EmailThreadEvent {

  @TypeName("thread-received")
  record ThreadReceived(String sender, String subject) implements EmailThreadEvent {}

  @TypeName("thread-triaged")
  record ThreadTriaged(String category, String urgency, String suggestedAction)
      implements EmailThreadEvent {}

  @TypeName("reply-drafted")
  record ReplyDrafted(String subject, String body) implements EmailThreadEvent {}

  @TypeName("meeting-drafted")
  record MeetingDrafted(String title, String time, String attendees) implements EmailThreadEvent {}

  @TypeName("thread-approved")
  record ThreadApproved(String approvedBy, String note) implements EmailThreadEvent {}

  @TypeName("thread-dismissed")
  record ThreadDismissed(String dismissedBy, String note) implements EmailThreadEvent {}

  @TypeName("reply-sent")
  record ReplySent(String actionReference) implements EmailThreadEvent {}

  @TypeName("meeting-scheduled")
  record MeetingScheduled(String actionReference) implements EmailThreadEvent {}
}
