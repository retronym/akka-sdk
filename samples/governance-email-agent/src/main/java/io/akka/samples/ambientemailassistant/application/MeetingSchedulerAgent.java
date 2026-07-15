package io.akka.samples.ambientemailassistant.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;
import io.akka.samples.ambientemailassistant.domain.MeetingProposal;

@Component(
    id = "meeting-scheduler-agent",
    name = "Meeting Scheduler Agent",
    description = "Proposes a calendar event. Proposing uses no tools, so no outbound action fires.")
public class MeetingSchedulerAgent extends Agent {

  private static final String SYSTEM_MESSAGE =
      """
      You propose a calendar event for an email that requests a meeting. The email content has been
      sanitized, so placeholders like [NAME] or [EMAIL] may appear — keep them as-is. Respond only
      with a proposed title, time, and attendees.
      """
          .stripIndent();

  public Effect<MeetingProposal> propose(String sanitizedEmail) {
    return effects()
        .systemMessage(SYSTEM_MESSAGE)
        .userMessage(sanitizedEmail)
        .responseConformsTo(MeetingProposal.class)
        .thenReply();
  }
}
