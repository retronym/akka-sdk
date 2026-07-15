package io.akka.samples.ambientemailassistant.application;

import akka.javasdk.annotations.FunctionTool;
import java.util.UUID;

/**
 * Simulated Calendar write. The {@code approved} flag is inspected by {@link ApprovalToolGuardrail}
 * at the before-tool-call boundary; this body only runs once the guardrail has allowed the call.
 */
public class CalendarTool {

  @FunctionTool(description = "Create the proposed calendar event. Requires an approved flag.")
  public String createEvent(String title, String time, String attendees, boolean approved) {
    return "EVT-" + UUID.randomUUID();
  }
}
