package io.akka.samples.ambientemailassistant.application;

import akka.javasdk.agent.Decision;
import akka.javasdk.agent.ToolGuardrail;
import java.util.regex.Pattern;

/**
 * G1 — a before-tool-call guardrail on the action agents. It denies the outbound Gmail/Calendar
 * write unless the call is marked approved.
 *
 * <p>A guardrail is stateless: its only injectable is {@link akka.javasdk.agent.GuardrailContext},
 * so it cannot read the thread entity. The approval decision therefore reaches the guardrail as the
 * {@code approved} argument the action agent's tool is called with. The thread's {@code APPROVED}
 * status remains the source of truth: the caller passes {@code approved = thread.isApproved()}, and
 * this guardrail is the enforcement point that stops the tool from running when that is not true.
 */
public class ApprovalToolGuardrail implements ToolGuardrail {

  private static final Pattern APPROVED_TRUE =
      Pattern.compile("\"approved\"\\s*:\\s*true", Pattern.CASE_INSENSITIVE);

  @Override
  public Decision decide(CallContext ctx) {
    if (!isOutboundWrite(ctx.toolName())) {
      return new Decision.Allow();
    }
    if (APPROVED_TRUE.matcher(ctx.arguments()).find()) {
      return new Decision.Allow("thread approved");
    }
    return new Decision.Deny(
        "outbound action '" + ctx.toolName() + "' blocked: thread is not approved");
  }

  private static boolean isOutboundWrite(String toolName) {
    return toolName.endsWith("sendReply") || toolName.endsWith("createEvent");
  }
}
