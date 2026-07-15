package io.akka.samples.ambientemailassistant.domain;

public record EmailClassification(String category, String urgency, String suggestedAction) {}
