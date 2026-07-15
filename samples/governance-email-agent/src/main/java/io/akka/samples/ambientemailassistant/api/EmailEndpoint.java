package io.akka.samples.ambientemailassistant.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import io.akka.samples.ambientemailassistant.application.EmailThreadEntity;
import io.akka.samples.ambientemailassistant.application.EmailWorkflow;
import io.akka.samples.ambientemailassistant.application.MeetingBookingAgent;
import io.akka.samples.ambientemailassistant.application.ReplyDispatchAgent;
import io.akka.samples.ambientemailassistant.application.ThreadsView;
import io.akka.samples.ambientemailassistant.domain.ApprovalDecision;
import io.akka.samples.ambientemailassistant.domain.DismissDecision;
import io.akka.samples.ambientemailassistant.domain.EmailThread;
import java.util.UUID;

// Opened up for the public internet to make the sample easy to try out. Restrict for production.
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/api")
public class EmailEndpoint {

  public record IncomingEmail(String sender, String subject, String body) {}

  public record ThreadCreated(String threadId) {}

  public record ApproveRequest(String approvedBy, String note) {}

  public record DismissRequest(String dismissedBy, String note) {}

  private final ComponentClient componentClient;

  public EmailEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("/email-threads")
  public ThreadCreated receiveEmail(IncomingEmail email) {
    var threadId = UUID.randomUUID().toString();

    componentClient
        .forEventSourcedEntity(threadId)
        .method(EmailThreadEntity::receive)
        .invoke(new EmailThreadEntity.IncomingEmail(email.sender(), email.subject(), email.body()));

    componentClient
        .forWorkflow(threadId)
        .method(EmailWorkflow::start)
        .invoke(
            new EmailWorkflow.State(
                threadId, email.sender(), email.subject(), email.body(), ""));

    return new ThreadCreated(threadId);
  }

  @Post("/threads/{threadId}/approve")
  public HttpResponse approve(String threadId, ApproveRequest request) {
    var thread = getThreadOrNull(threadId);
    if (thread == null) {
      return HttpResponses.notFound("No thread " + threadId);
    }

    componentClient
        .forEventSourcedEntity(threadId)
        .method(EmailThreadEntity::approve)
        .invoke(new ApprovalDecision(request.approvedBy(), request.note()));

    executeApprovedAction(threadId);
    return HttpResponses.ok();
  }

  @Post("/threads/{threadId}/dismiss")
  public HttpResponse dismiss(String threadId, DismissRequest request) {
    var thread = getThreadOrNull(threadId);
    if (thread == null) {
      return HttpResponses.notFound("No thread " + threadId);
    }
    componentClient
        .forEventSourcedEntity(threadId)
        .method(EmailThreadEntity::dismiss)
        .invoke(new DismissDecision(request.dismissedBy(), request.note()));
    return HttpResponses.ok();
  }

  @Get("/threads")
  public ThreadsView.Threads listThreads() {
    return componentClient.forView().method(ThreadsView::getAllThreads).invoke();
  }

  @Get("/threads/{threadId}")
  public HttpResponse getThread(String threadId) {
    var thread = getThreadOrNull(threadId);
    return thread == null ? HttpResponses.notFound("No thread " + threadId) : HttpResponses.ok(thread);
  }

  /** Drives the guarded outbound action after approval. The thread is APPROVED, so the guardrail
   * allows the tool; the workflow set nothing here — approval is the gate. */
  private void executeApprovedAction(String threadId) {
    var thread = getThreadOrNull(threadId);
    if (thread == null || !thread.isApproved()) {
      return;
    }
    String reference;
    if (thread.meeting().isPresent()) {
      var meeting = thread.meeting().get();
      reference =
          componentClient
              .forAgent()
              .inSession(threadId)
              .method(MeetingBookingAgent::schedule)
              .invoke(
                  new MeetingBookingAgent.ScheduleCommand(
                      meeting.title(), meeting.proposedTime(), meeting.attendees(),
                      thread.isApproved()));
    } else {
      var reply = thread.reply().orElseThrow();
      reference =
          componentClient
              .forAgent()
              .inSession(threadId)
              .method(ReplyDispatchAgent::send)
              .invoke(
                  new ReplyDispatchAgent.SendCommand(
                      thread.sender(), reply.subject(), reply.body(), thread.isApproved()));
    }
    componentClient
        .forEventSourcedEntity(threadId)
        .method(EmailThreadEntity::recordActionComplete)
        .invoke(reference);
  }

  private EmailThread getThreadOrNull(String threadId) {
    var thread =
        componentClient
            .forEventSourcedEntity(threadId)
            .method(EmailThreadEntity::getThread)
            .invoke();
    // Unknown ids resolve to the empty state; treat a thread that was never received as absent.
    return thread.exists() ? thread : null;
  }
}
