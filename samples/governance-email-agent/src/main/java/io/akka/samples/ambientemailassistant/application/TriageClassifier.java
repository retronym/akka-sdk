package io.akka.samples.ambientemailassistant.application;

import akka.javasdk.agent.Classification;
import akka.javasdk.agent.Classifier;
import akka.javasdk.agent.ClassifierContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * V1 Classifier that triages an incoming email. It maps the (already PII-sanitized) email text to a
 * {@link Classification} whose label is the category and whose attributes carry the urgency and the
 * suggested action. A real deployment might back this with an LLM or an external classification API;
 * here it is rule-based so the sample runs deterministically without a model.
 *
 * <p>The classifier is never dispatched by the runtime — the workflow invokes it inline by name
 * through the injected {@code ClassifierClient}.
 */
public class TriageClassifier implements Classifier {

  private final List<String> meetingKeywords;
  private final List<String> urgentKeywords;

  public TriageClassifier(ClassifierContext context) {
    this.meetingKeywords = context.config().getStringList("meeting-keywords");
    this.urgentKeywords = context.config().getStringList("urgent-keywords");
  }

  @Override
  public CompletionStage<Classification> classify(String input) {
    return CompletableFuture.supplyAsync(
        () -> {
          String text = input.toLowerCase();
          boolean meeting = meetingKeywords.stream().anyMatch(text::contains);
          boolean urgent = urgentKeywords.stream().anyMatch(text::contains);

          String category = meeting ? "meeting-request" : "general";
          String suggestedAction = meeting ? "meeting" : "reply";
          String urgency = urgent ? "high" : "normal";
          double score = urgent ? 0.9 : 0.5;

          return new Classification(
              java.util.Optional.of(score),
              java.util.Optional.of(category),
              java.util.Optional.of(score),
              Map.of("urgency", urgency, "suggestedAction", suggestedAction));
        });
  }
}
