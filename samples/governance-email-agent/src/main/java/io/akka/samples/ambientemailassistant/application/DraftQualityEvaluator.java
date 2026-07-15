package io.akka.samples.ambientemailassistant.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.evaluation.Evaluation;
import akka.javasdk.evaluation.EvaluationContext;
import akka.javasdk.evaluation.Evaluator;
import akka.javasdk.ledger.InteractionRecord;
import akka.javasdk.ledger.LedgerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E1 — a V1 Evaluator bound to {@link ReplyDraftAgent}. The runtime triggers it for each
 * interaction of that agent; it fetches the interaction from the ledger and records a quality
 * verdict. A real deployment would call an LLM-as-judge here; this heuristic keeps the sample
 * deterministic and offline.
 */
@Component(id = "draft-quality-evaluator")
public class DraftQualityEvaluator extends Evaluator {

  private static final Logger logger = LoggerFactory.getLogger(DraftQualityEvaluator.class);

  private final LedgerClient ledger;

  public DraftQualityEvaluator(LedgerClient ledger) {
    this.ledger = ledger;
  }

  @Override
  public Effect evaluate(EvaluationContext context) {
    InteractionRecord interaction = ledger.getInteraction(context.subject().interactionId());

    if (interaction.failed()) {
      return effects().inconclusive("interaction failed, nothing to evaluate");
    }

    String response = interaction.finalResponseText();
    if (response.isEmpty()) {
      return effects().complete(Evaluation.failed("draft produced no response"));
    }

    double score = Math.min(1.0, response.length() / 200.0);
    var evaluation =
        Evaluation.passed("draft produced a non-empty response")
            .withScore(score)
            .withLabel(score >= 0.5 ? "substantial" : "thin");
    logger.info("Draft quality for interaction {}: score={}", context.subject().interactionId(), score);
    return effects().complete(evaluation);
  }
}
