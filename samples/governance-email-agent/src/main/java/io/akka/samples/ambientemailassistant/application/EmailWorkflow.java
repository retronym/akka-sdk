package io.akka.samples.ambientemailassistant.application;

import akka.javasdk.agent.ClassifierClient;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import io.akka.samples.ambientemailassistant.domain.EmailClassification;
import io.akka.samples.ambientemailassistant.domain.MeetingProposal;
import io.akka.samples.ambientemailassistant.domain.ReplyDraft;
import java.time.Duration;

/**
 * Orchestrates the AI-heavy part of a thread: triage (a V1 Classifier) then draft an action (an
 * Agent). It ends at ACTION_DRAFTED. Approval and the guarded outbound action are driven separately
 * through the API — there is no suspend/resume gate here; the {@link ApprovalToolGuardrail} is what
 * stops an unapproved action from firing.
 */
@Component(id = "email-workflow")
public class EmailWorkflow extends Workflow<EmailWorkflow.State> {

  public record State(
      String threadId, String sender, String subject, String body, String suggestedAction) {
    State withSuggestedAction(String action) {
      return new State(threadId, sender, subject, body, action);
    }
  }

  private final ClassifierClient classifierClient;
  private final ComponentClient componentClient;

  public EmailWorkflow(ClassifierClient classifierClient, ComponentClient componentClient) {
    this.classifierClient = classifierClient;
    this.componentClient = componentClient;
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder()
        .stepTimeout(EmailWorkflow::triageStep, Duration.ofSeconds(60))
        .stepTimeout(EmailWorkflow::draftActionStep, Duration.ofSeconds(60))
        .build();
  }

  public Effect<String> start(State init) {
    return effects()
        .updateState(init)
        .transitionTo(EmailWorkflow::triageStep)
        .thenReply(init.threadId());
  }

  @StepName("triage")
  private StepEffect triageStep() {
    var sanitized =
        PiiSanitizer.sanitize(currentState().subject() + "\n" + currentState().body());

    var classification = classifierClient.classify("triage-classifier", sanitized);
    var category = classification.label().orElse("general");
    var urgency = classification.attributes().getOrDefault("urgency", "normal");
    var suggestedAction = classification.attributes().getOrDefault("suggestedAction", "reply");

    componentClient
        .forEventSourcedEntity(currentState().threadId())
        .method(EmailThreadEntity::recordTriage)
        .invoke(new EmailClassification(category, urgency, suggestedAction));

    return stepEffects()
        .updateState(currentState().withSuggestedAction(suggestedAction))
        .thenTransitionTo(EmailWorkflow::draftActionStep);
  }

  @StepName("draft-action")
  private StepEffect draftActionStep() {
    var threadId = currentState().threadId();
    var sanitized =
        PiiSanitizer.sanitize(currentState().subject() + "\n" + currentState().body());

    if (currentState().suggestedAction().equals("meeting")) {
      MeetingProposal proposal =
          componentClient
              .forAgent()
              .inSession(threadId)
              .method(MeetingSchedulerAgent::propose)
              .invoke(sanitized);
      componentClient
          .forEventSourcedEntity(threadId)
          .method(EmailThreadEntity::recordMeetingDraft)
          .invoke(proposal);
    } else {
      ReplyDraft draft =
          componentClient
              .forAgent()
              .inSession(threadId)
              .method(ReplyDraftAgent::draft)
              .invoke(sanitized);
      componentClient
          .forEventSourcedEntity(threadId)
          .method(EmailThreadEntity::recordReplyDraft)
          .invoke(draft);
    }

    // Ends here. The thread is now ACTION_DRAFTED and awaits approval through the API.
    return stepEffects().thenEnd();
  }
}
