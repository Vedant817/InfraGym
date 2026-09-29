# Graph Compiler Service

## Overview

The Graph Compiler Service is a Spring Boot microservice that converts React Flow topology diagrams (visual infrastructure designs) into executable DAGs for simulation. It consumes design submissions from Kafka, validates and compiles them, then publishes simulation tasks back to Kafka.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     GRAPH COMPILER SERVICE                       │
│                                                                  │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐       │
│  │   schema/    │───▶│ validation/  │───▶│  compiler/   │       │
│  │              │    │              │    │              │       │
│  │ ReactFlow    │    │ Schema       │    │ GraphBuilder │       │
│  │ Topology     │    │ Validator    │    │ Topological  │       │
│  │              │    │              │    │ Sorter       │       │
│  │ Design       │    │ NodeType     │    │ DagCompiler  │       │
│  │ Submission   │    │ Validator    │    │              │       │
│  │ Event        │    │              │    │ Dependency   │       │
│  │              │    │ Edge         │    │ Resolver     │       │
│  │ Simulation   │    │ Validator    │    │              │       │
│  │ TaskEvent    │    │              │    │ GraphMetrics │       │
│  │              │    │ Cycle        │    │ Calculator   │       │
│  │              │    │ Detector     │    │              │       │
│  │              │    │              │    │              │       │
│  │              │    │ Orphan       │    │              │       │
│  │              │    │ Detector     │    │              │       │
│  │              │    │              │    │              │       │
│  │              │    │ Semantic     │    │              │       │
│  │              │    │ Validator    │    │              │       │
│  └──────────────┘    └──────────────┘    └──────────────┘       │
│         │                    │                    │              │
│         ▼                    ▼                    ▼              │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐       │
│  │ enrichment/  │    │   event/     │    │ controller/  │       │
│  │              │    │              │    │              │       │
│  │ Simulation   │    │ Design       │    │ Compilation  │       │
│  │ Parameter    │    │ Submission   │    │ Controller   │       │
│  │ Registry     │    │ Consumer     │    │              │       │
│  │              │    │              │    │ DeadLetter   │       │
│  │ Node         │    │ Compilation  │    │ Controller   │       │
│  │ Enricher     │    │ Service      │    │              │       │
│  │              │    │              │    │ Submission   │       │
│  │ Graph        │    │ Simulation   │    │ Status       │       │
│  │ Metadata     │    │ Task         │    │ Controller   │       │
│  │ Generator    │    │ Producer     │    │              │       │
│  │              │    │              │    │ Global       │       │
│  │ Graph        │    │              │    │ Exception    │       │
│  │ Enrichment   │    │              │    │ Handler      │       │
│  │ Pipeline     │    │              │    │              │       │
│  └──────────────┘    └──────────────┘    └──────────────┘       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## Data Flow

```
Kafka (design.submissions)
    │
    ▼
DesignSubmissionConsumer
    │
    ▼
CompilationService
    │
    ├──▶ TopologyValidator
    │       ├──▶ SchemaValidator
    │       ├──▶ NodeTypeValidator
    │       ├──▶ EdgeValidator
    │       ├──▶ CycleDetector
    │       ├──▶ OrphanNodeDetector
    │       └──▶ SemanticValidator
    │
    ├──▶ GraphCompiler
    │       ├──▶ GraphBuilder
    │       ├──▶ TopologicalSorter
    │       └──▶ GraphMetricsCalculator
    │
    ├──▶ DagCompiler
    │       └──▶ GraphEnrichmentPipeline
    │               ├──▶ NodeEnricher
    │               └──▶ GraphMetadataGenerator
    │
    ▼
SimulationTaskProducer
    │
    ▼
Kafka (simulation.tasks)
```

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/compile` | Compile a topology synchronously |
| GET | `/api/v1/submissions/{id}` | Get submission status |
| POST | `/api/v1/submissions/{id}/status` | Update submission status |
| POST | `/api/v1/admin/replay-dlq` | Replay a dead letter message |
| GET | `/api/v1/admin/dlq-info` | Get DLQ information |

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `graph-compiler.kafka.topics.design-submissions` | `design.submissions` | Input topic |
| `graph-compiler.kafka.topics.simulation-tasks` | `simulation.tasks` | Output topic |
| `graph-compiler.kafka.topics.dead-letter` | `graph-compiler.dlt` | Dead letter topic |
| `graph-compiler.validation.max-nodes` | `500` | Maximum nodes per topology |
| `graph-compiler.validation.max-edges` | `2000` | Maximum edges per topology |
| `graph-compiler.rate-limit.requests-per-minute` | `10` | Rate limit per user |

## Local Development

```bash
# Start Kafka (via Docker)
docker run -d --name kafka -p 9092:9092 confluentinc/cp-kafka:latest

# Run the service
cd services/graph-compiler
./mvnw spring-boot:run

# Run tests
./mvnw test
```

## Testing

- **Unit tests**: 15+ test classes covering all components
- **Integration tests**: Embedded Kafka tests for end-to-end flow
- **Test coverage**: ~80% of production code

## Monitoring

- **Metrics**: Micrometer + Prometheus
- **Tracing**: Micrometer Tracing + Zipkin
- **Health**: Spring Boot Actuator with liveness/readiness groups
- **Logs**: Structured logging with MDC trace IDs
