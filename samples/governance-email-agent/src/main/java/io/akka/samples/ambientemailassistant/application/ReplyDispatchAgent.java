package io.akka.samples.ambientemailassistant.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;

/**
 * Sends an approved reply through the Gmail tool. The tool call is gated by
 * {@link ApprovalToolGuardrail} at the before-tool-call boundary.
 */
@Component(
    id = "reply-dispatch-agent",
    name = "Reply Dispatch Agent",
    description = "Sends an approved email reply via the Gmail tool.")
public class ReplyDispatchAgent extends Agent {

  public record SendCommand(String recipient, String subject, String body, boolean approved) {}

  private static final String SYSTEM_MESSAGE =
      """
      You send an email by calling the sendReply tool exactly once, passing the recipient, subject,
      and body you are given, and the approved flag exactly as provided. Return the tool result.
      """
          .stripIndent();

  public Effect<String> send(SendCommand command) {
    var userMessage =
        """
        Send this reply.
        recipient: %s
        subject: %s
        body: %s
        approved: %s
        """
            .stripIndent()
            .formatted(command.recipient(), command.subject(), command.body(), command.approved());
    return effects()
        .systemMessage(SYSTEM_MESSAGE)
        .tools(new GmailTool())
        .userMessage(userMessage)
        .thenReply();
  }
}
