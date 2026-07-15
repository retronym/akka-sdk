package io.akka.samples.ambientemailassistant.application;

import akka.javasdk.annotations.FunctionTool;
import java.util.UUID;

/**
 * Simulated Gmail write. The {@code approved} flag is inspected by {@link ApprovalToolGuardrail} at
 * the before-tool-call boundary; this method body only runs once the guardrail has allowed the call.
 */
public class GmailTool {

  @FunctionTool(description = "Send the drafted email reply. Requires an approved flag.")
  public String sendReply(String recipient, String subject, String body, boolean approved) {
    return "MSG-" + UUID.randomUUID();
  }
}
