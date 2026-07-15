package io.akka.samples.ambientemailassistant.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;
import io.akka.samples.ambientemailassistant.domain.ReplyDraft;

@Component(
    id = "reply-draft-agent",
    name = "Reply Draft Agent",
    description = "Drafts an email reply. Drafting uses no tools, so no outbound action can fire.")
public class ReplyDraftAgent extends Agent {

  private static final String SYSTEM_MESSAGE =
      """
      You draft a concise, professional reply to an incoming email. The email content has been
      sanitized, so placeholders like [NAME] or [EMAIL] may appear — keep them as-is. Respond only
      with the drafted subject and body.
      """
          .stripIndent();

  public Effect<ReplyDraft> draft(String sanitizedEmail) {
    return effects()
        .systemMessage(SYSTEM_MESSAGE)
        .userMessage(sanitizedEmail)
        .responseConformsTo(ReplyDraft.class)
        .thenReply();
  }
}
