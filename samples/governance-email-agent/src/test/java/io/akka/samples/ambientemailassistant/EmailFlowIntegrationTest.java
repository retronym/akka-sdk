package io.akka.samples.ambientemailassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import io.akka.samples.ambientemailassistant.api.EmailEndpoint;
import io.akka.samples.ambientemailassistant.application.MeetingSchedulerAgent;
import io.akka.samples.ambientemailassistant.application.ReplyDispatchAgent;
import io.akka.samples.ambientemailassistant.application.ReplyDraftAgent;
import io.akka.samples.ambientemailassistant.domain.EmailThread;
import io.akka.samples.ambientemailassistant.domain.EmailThread.ThreadStatus;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

public class EmailFlowIntegrationTest extends TestKitSupport {

  private final TestModelProvider replyDraftModel = new TestModelProvider();
  private final TestModelProvider meetingProposeModel = new TestModelProvider();
  private final TestModelProvider replyDispatchModel = new TestModelProvider();

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withAdditionalConfig(
            "akka.javasdk.agent.anthropic.api-key = n/a")
        .withModelProvider(ReplyDraftAgent.class, replyDraftModel)
        .withModelProvider(MeetingSchedulerAgent.class, meetingProposeModel)
        .withModelProvider(ReplyDispatchAgent.class, replyDispatchModel);
  }

  @Test
  public void triagesAndDraftsAReply() {
    replyDraftModel
        .whenMessage(msg -> true)
        .reply("{\"subject\":\"Re: hello\",\"body\":\"Thanks for reaching out.\"}");

    var threadId = postEmail("alice@example.com", "hello", "Can you help me with my order?");

    var thread = awaitStatus(threadId, ThreadStatus.ACTION_DRAFTED);
    assertThat(thread.triage()).isPresent();
    assertThat(thread.suggestedAction()).contains("reply");
    assertThat(thread.reply()).isPresent();
  }

  @Test
  public void approvesAndSendsReply() {
    replyDraftModel
        .whenMessage(msg -> true)
        .reply("{\"subject\":\"Re: invoice\",\"body\":\"Attached is the invoice.\"}");
    // The dispatch agent calls the guarded sendReply tool with approved=true, then returns the
    // tool's message id as its reply.
    replyDispatchModel
        .whenMessage(msg -> true)
        .reply(
            new TestModelProvider.ToolInvocationRequest(
                "GmailTool_sendReply",
                "{\"recipient\":\"a@b.com\",\"subject\":\"Re: invoice\",\"body\":\"...\",\"approved\":true}"));
    replyDispatchModel
        .whenToolResult(tr -> tr.name().endsWith("sendReply"))
        .thenReply(tr -> new TestModelProvider.AiResponse(tr.content()));

    var threadId = postEmail("bob@example.com", "invoice", "Please send the invoice.");
    awaitStatus(threadId, ThreadStatus.ACTION_DRAFTED);

    approve(threadId, "operator");

    var thread = awaitStatus(threadId, ThreadStatus.REPLY_SENT);
    assertThat(thread.actionReference()).isPresent();
    assertThat(thread.actionReference().get()).startsWith("MSG-");
  }

  @Test
  public void dismissesAThread() {
    replyDraftModel.whenMessage(msg -> true).reply("{\"subject\":\"Re: spam\",\"body\":\"n/a\"}");

    var threadId = postEmail("spam@example.com", "spam", "Buy now!");
    awaitStatus(threadId, ThreadStatus.ACTION_DRAFTED);

    var response =
        httpClient
            .POST("/api/threads/" + threadId + "/dismiss")
            .withRequestBody(new EmailEndpoint.DismissRequest("operator", "not actionable"))
            .invoke();
    assertThat(response.httpResponse().status().isSuccess()).isTrue();

    var thread = awaitStatus(threadId, ThreadStatus.DISMISSED);
    assertThat(thread.dismissal()).isPresent();
    assertThat(thread.dismissal().get().note()).isEqualTo("not actionable");
  }

  @Test
  public void guardrailBlocksUnapprovedSend() {
    // Directly invoke the dispatch agent with approved=false: the before-tool-call guardrail must
    // deny the sendReply tool, so the outbound action never runs.
    replyDispatchModel
        .whenMessage(msg -> true)
        .reply(
            new TestModelProvider.ToolInvocationRequest(
                "GmailTool_sendReply",
                "{\"recipient\":\"a@b.com\",\"subject\":\"x\",\"body\":\"y\",\"approved\":false}"));

    assertThatThrownBy(
            () ->
                componentClient
                    .forAgent()
                    .inSession("unapproved-thread")
                    .method(ReplyDispatchAgent::send)
                    .invoke(
                        new ReplyDispatchAgent.SendCommand("a@b.com", "x", "y", false)))
        .isInstanceOf(RuntimeException.class);
  }

  private String postEmail(String sender, String subject, String body) {
    var created =
        httpClient
            .POST("/api/email-threads")
            .withRequestBody(new EmailEndpoint.IncomingEmail(sender, subject, body))
            .responseBodyAs(EmailEndpoint.ThreadCreated.class)
            .invoke();
    assertThat(created.body().threadId()).isNotBlank();
    return created.body().threadId();
  }

  private void approve(String threadId, String approver) {
    var response =
        httpClient
            .POST("/api/threads/" + threadId + "/approve")
            .withRequestBody(new EmailEndpoint.ApproveRequest(approver, "looks good"))
            .invoke();
    assertThat(response.httpResponse().status().isSuccess()).isTrue();
  }

  private EmailThread awaitStatus(String threadId, ThreadStatus expected) {
    return Awaitility.await()
        .ignoreExceptions()
        .atMost(20, TimeUnit.SECONDS)
        .until(
            () ->
                httpClient
                    .GET("/api/threads/" + threadId)
                    .responseBodyAs(EmailThread.class)
                    .invoke()
                    .body(),
            thread -> thread.status() == expected);
  }
}
