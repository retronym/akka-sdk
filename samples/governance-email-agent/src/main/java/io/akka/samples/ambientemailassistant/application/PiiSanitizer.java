package io.akka.samples.ambientemailassistant.application;

import java.util.regex.Pattern;

/**
 * S1 — strips personally identifiable information from email content before it is placed in an LLM
 * prompt or written to a log outside the workflow boundary. Replacements use domain-consistent
 * tokens so the model still sees the shape of the message.
 */
public final class PiiSanitizer {

  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern PHONE =
      Pattern.compile("(\\+?\\d{1,3}[ .-]?)?(\\(?\\d{3}\\)?[ .-]?)\\d{3}[ .-]?\\d{4}");
  private static final Pattern NAME = Pattern.compile("\\b[A-Z][a-z]+ [A-Z][a-z]+\\b");

  private PiiSanitizer() {}

  public static String sanitize(String text) {
    if (text == null || text.isBlank()) {
      return text;
    }
    String result = EMAIL.matcher(text).replaceAll("[EMAIL]");
    result = PHONE.matcher(result).replaceAll("[PHONE]");
    result = NAME.matcher(result).replaceAll("[NAME]");
    return result;
  }
}
