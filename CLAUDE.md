# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Prerequisites

JDK 21 is required. To build locally, you need a commercial build token from https://account.akka.io/token placed in `~/.sbt/1.0/akka-commercial.sbt`:
```
ThisBuild / resolvers += "lightbend-akka".at("your token resolver here")
```

## Build Commands

```bash
# Publish SDK to local Maven (~/.m2) and Ivy (~/.ivy2/local)
sbt publishM2 publishLocal

# Run all tests
sbt test

# Run a single test class (in any module)
sbt "project akka-javasdk-tests" "testOnly *SomeTestClass*"
sbt "project akka-javasdk" "testOnly *SomeUnitTest*"

# Format all code (Scala + Java)
sbt formatAll
# or separately:
sbt scalafmtAll scalafmtSbt   # Scala
sbt javafmtAll                 # Java

# Organize Scala imports (required by CI)
sbt -Dbuild.scalafix scalafixEnable 'scalafixAll OrganizeImports' scalafmtAll

# Add/check license headers
sbt headerCreateAll
sbt headerCheckAll

# Build docs (requires Docker)
make build
```

CI checks in order: javafmt → headers → scalafmt → scalafix OrganizeImports → additionalValidation → sbt test.

To update samples to use a locally built SDK version: `updateSamplesVersions.sh samples/*`

## Architecture

### Module Structure

This is a multi-module sbt project. The SDK itself is split into:

- **`akka-javasdk`** — main SDK module. Public API is pure Java under `akka/javasdk/`; internal implementation is Scala under `akka/javasdk/impl/`. This is the primary module.
- **`akka-javasdk-testkit`** — testing utilities for SDK users, depends on `akka-javasdk`.
- **`akka-javasdk-tests`** — integration tests that test a running service via the testkit. Deliberately uses root package outside `akka.*` to prevent accidental access to SDK internals.
- **`akka-javasdk-annotation-processor`** — Java annotation processor (`ComponentAnnotationProcessor`) that runs at user compile time to discover and register components.
- **`akka-javasdk-validations`** — shared validation library used by both the annotation processor (compile time) and the runtime (via reflection).
- **`akka-javasdk-enforcer`** — Maven enforcer plugin (pure Java, targets Java 11).
- **`akka-javasdk-parent`** — Maven POM parent for user projects.
- **`samples/`** — sample Maven projects (compiled in sbt via `SamplesCompilationProject`).

### SDK–Runtime Relationship

The Akka SDK is a layer over a closed-source **Akka Runtime** (`io.akka:akka-runtime-dev`). The SPI between SDK and runtime is `io.akka:akka-sdk-spi`. Versions of the runtime, Akka, and Scala must be kept aligned (see `project/Dependencies.scala`). `akka-actor-typed` is `Provided` — it comes from the runtime at deploy time. `AkkaDevRuntime` is used only in tests and dev mode.

### Component Discovery Flow

1. **Compile time**: `ComponentAnnotationProcessor` scans for classes annotated with `@Component`, `@HttpEndpoint`, `@GrpcEndpoint`, `@McpEndpoint`, or `@Setup`. It writes a descriptor to `META-INF/akka-javasdk-components_<groupId>_<artifactId>.conf`.

2. **Runtime**: `ComponentLocator` reads those conf files from the classpath and returns lists of component class names keyed by type (e.g., `http-endpoint`, `event-sourced-entity`). The keys in `ComponentLocator` and `ComponentAnnotationProcessor` must stay in sync.

3. **Wiring**: `SdkRunner` (implements the SPI `Runner`) instantiates components via reflection, builds `ComponentDescriptor` objects (method invoker maps) using per-type `*DescriptorFactory` classes (e.g., `EntityDescriptorFactory`, `HttpEndpointDescriptorFactory`), and registers them with the runtime.

### Component Types

Public API base classes/interfaces for user components:

| Component | Package |
|-----------|---------|
| `EventSourcedEntity` | `akka.javasdk.eventsourcedentity` |
| `KeyValueEntity` | `akka.javasdk.keyvalueentity` |
| `View` | `akka.javasdk.view` |
| `Consumer` | `akka.javasdk.consumer` |
| `TimedAction` | `akka.javasdk.timedaction` |
| `Workflow` | `akka.javasdk.workflow` |
| `Agent` | `akka.javasdk.agent` |
| `AutonomousAgent` | `akka.javasdk.agent.autonomous` |
| `AbstractHttpEndpoint` | `akka.javasdk.http` |
| `AbstractGrpcEndpoint` | `akka.javasdk.grpc` |
| `AbstractMcpEndpoint` | `akka.javasdk.mcp` |

Each has a corresponding `*DescriptorFactory` in `akka.javasdk.impl` that handles routing and serialization.

### AI Agent Architecture

`Agent` wraps LangChain4j for LLM interactions. `AutonomousAgent` adds task management via `TaskEntity` (event-sourced). Session memory is stored in `SessionMemoryEntity`. Guardrails (`TextGuardrail`, `SimilarityGuard`) can intercept model input/output. `FunctionTools` converts Java methods annotated with `@FunctionTool` into LLM tool definitions via JSON schema generation (`JsonSchema.scala`).

### Tests Module Notes

- Test components are manually registered in `akka-javasdk-tests/src/test/resources/META-INF/akka-javasdk-components.conf` (not via annotation processor).
- Tests run with `fork := true` and `parallelExecution := false`.

### Dependency Sync Requirement

`samples/ask-akka-agent/pom.xml` has an explicit `langchain4j.version` property that must stay in sync with `Dependencies.Langchain4jVersion`. The `additionalValidation` sbt task enforces this.
