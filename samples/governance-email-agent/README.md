# Ambient Email Assistant

An agent reads an incoming email, triages it, and drafts a reply or proposes a meeting. An operator
approves or dismisses the draft through the API; on approval the assistant executes the outbound
action (send reply or create calendar event). The sample is built to demonstrate the **v1 governance
constructs** of the Akka SDK working together.

## Governance features demonstrated

| Control | Akka v1 construct | Where |
|---|---|---|
| Triage classification | **`Classifier`** — invoked inline by the workflow through the injected `ClassifierClient`, by name; never dispatched by the runtime | [`TriageClassifier`](src/main/java/io/akka/samples/ambientemailassistant/application/TriageClassifier.java) |
| Approval gate on outbound actions | **`ToolGuardrail`** — a before-tool-call guardrail that denies the Gmail/Calendar write unless the call is approved | [`ApprovalToolGuardrail`](src/main/java/io/akka/samples/ambientemailassistant/application/ApprovalToolGuardrail.java) |
| Draft quality tracking | **`Evaluator`** + **`LedgerClient`** — the runtime triggers it per interaction of the reply agent; it reads the interaction from the ledger and records a verdict | [`DraftQualityEvaluator`](src/main/java/io/akka/samples/ambientemailassistant/application/DraftQualityEvaluator.java) |
| PII protection | Sanitizer utility — strips emails, phone numbers, and names before content reaches an LLM prompt | [`PiiSanitizer`](src/main/java/io/akka/samples/ambientemailassistant/application/PiiSanitizer.java) |

See the SDK docs: [Classifiers](https://doc.akka.io/sdk/agents/classifiers.html),
[Guardrails](https://doc.akka.io/sdk/agents/guardrails.html),
[Evaluators](https://doc.akka.io/sdk/evaluators.html).

## How approval works (no human-in-the-loop suspend)

The SDK's workflow suspend/resume is deliberately **not** used here. Instead:

1. `EmailWorkflow` orchestrates the AI-heavy part only — triage (classifier) then draft (agent) —
   and ends at `ACTION_DRAFTED`.
2. `POST /api/threads/{id}/approve` records approval on the entity and then drives the outbound
   action. The action runs through an agent whose write tool is guarded by `ApprovalToolGuardrail`.

A guardrail is **stateless** — its only injectable is `GuardrailContext`, so it cannot read the
thread entity. The approval decision therefore reaches it as the `approved` argument the tool is
called with; the caller passes `approved = thread.isApproved()`, so the **entity status is the
source of truth** and the guardrail is the **enforcement point**. An attempt to run the outbound
tool for a thread that is not approved is denied (`Decision.Deny`) and the tool never executes —
see the `guardrailBlocksUnapprovedSend` test.

## Components

- **`TriageClassifier`** — v1 `Classifier`, rule-based (keyword rules from config), so the sample
  triages deterministically without a model.
- **`ReplyDraftAgent`** / **`MeetingSchedulerAgent`** — draft a reply / propose a meeting (no tools,
  so no outbound action can fire while drafting).
- **`ReplyDispatchAgent`** / **`MeetingBookingAgent`** — perform the approved outbound action through
  the guarded `GmailTool` / `CalendarTool` (simulated: returns `MSG-…` / `EVT-…`).
- **`EmailWorkflow`** — triage → draft, ending at `ACTION_DRAFTED`.
- **`EmailThreadEntity`** — event-sourced thread state and lifecycle.
- **`ThreadsView`** — CQRS read model of all threads (single query; filter by status client-side).
- **`EmailEndpoint`** — REST surface under `/api`.

## API

```
POST /api/email-threads                 -> { threadId }
POST /api/threads/{threadId}/approve     -> 200 | 404
POST /api/threads/{threadId}/dismiss     -> 200 | 404
GET  /api/threads                        -> { threads: [ EmailThread, ... ] }
GET  /api/threads/{threadId}             -> EmailThread | 404
```

`ThreadStatus`: `RECEIVED → TRIAGED → ACTION_DRAFTED → APPROVED → (REPLY_SENT | MEETING_SCHEDULED)`,
or `ACTION_DRAFTED → DISMISSED`.

## Running

Triage is rule-based and needs no model. The reply/meeting agents call an LLM, so set an API key for
the configured provider (defaults to Anthropic in `application.conf`):

```shell
export ANTHROPIC_API_KEY=...
mvn compile exec:java
```

The service listens on `http://localhost:9774`. Example:

```shell
curl -i localhost:9774/api/email-threads \
  -H 'Content-Type: application/json' \
  -d '{"sender":"alice@example.com","subject":"quick sync","body":"Can we schedule a call?"}'
```

The integration test drives the full set of journeys with a mocked model and needs no key:

```shell
mvn verify
```

> `pom.xml` pins Akka `3.6.0`. The v1 governance APIs this sample uses land in a later release, so
> until then build against a local SDK snapshot that includes them — bump the parent version by hand
> or with `updateSdkVersions.sh` (as in dev/CI). Do not commit the snapshot version.

## License

This sample is released under the same license as the Akka SDK.
