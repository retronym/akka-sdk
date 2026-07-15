package io.akka.samples.ambientemailassistant.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;

/**
 * Creates an approved calendar event through the Calendar tool. The tool call is gated by
 * {@link ApprovalToolGuardrail} at the before-tool-call boundary.
 */
@Component(
    id = "meeting-booking-agent",
    name = "Meeting Booking Agent",
    description = "Creates an approved calendar event via the Calendar tool.")
public class MeetingBookingAgent extends Agent {

  public record ScheduleCommand(String title, String time, String attendees, boolean approved) {}

  private static final String SYSTEM_MESSAGE =
      """
      You create a calendar event by calling the createEvent tool exactly once, passing the title,
      time, and attendees you are given, and the approved flag exactly as provided. Return the tool
      result.
      """
          .stripIndent();

  public Effect<String> schedule(ScheduleCommand command) {
    var userMessage =
        """
        Create this event.
        title: %s
        time: %s
        attendees: %s
        approved: %s
        """
            .stripIndent()
            .formatted(command.title(), command.time(), command.attendees(), command.approved());
    return effects()
        .systemMessage(SYSTEM_MESSAGE)
        .tools(new CalendarTool())
        .userMessage(userMessage)
        .thenReply();
  }
}
