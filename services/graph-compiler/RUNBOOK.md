# Graph Compiler Service — Operations Runbook

## Service Overview

The Graph Compiler Service consumes design submissions from Kafka, validates and compiles them into executable DAGs, and publishes simulation tasks back to Kafka.

## Health Checks

### Liveness Probe
```
GET /actuator/health/liveness
```
Should return `{"status":"UP"}`. If this fails, the pod is not running.

### Readiness Probe
```
GET /actuator/health/readiness
```
Should return `{"status":"UP"}`. If this fails, the pod is not ready to accept traffic (e.g., Kafka is down).

## Common Failure Scenarios

### 1. Kafka Broker Unavailable

**Symptoms:**
- Readiness probe returns DOWN
- Consumer lag metric stops updating
- Logs show `Connection to node -1 could not be established`

**Diagnosis:**
```bash
# Check Kafka broker status
kubectl get pods -n kafka

# Check service logs
kubectl logs -f deployment/graph-compiler
```

**Resolution:**
1. Wait for Kafka to recover (automatic)
2. If Kafka is permanently down, scale down the service to prevent crash looping:
   ```bash
   kubectl scale deployment graph-compiler --replicas=0
   ```
3. After Kafka recovers, scale back up:
   ```bash
   kubectl scale deployment graph-compiler --replicas=2
   ```

### 2. Consumer Lag Spike

**Symptoms:**
- `kafka.consumer.lag.total` metric > 1000
- Users report slow compilation

**Diagnosis:**
```bash
# Check consumer lag
kubectl exec -it deployment/graph-compiler -- \
  curl localhost:8081/actuator/metrics/kafka.consumer.lag.total

# Check CPU usage
kubectl top pod -l app=graph-compiler
```

**Resolution:**
1. If CPU is saturated, scale horizontally:
   ```bash
   kubectl scale deployment graph-compiler --replicas=4
   ```
2. If a specific topology is causing slow processing, check logs for large topologies
3. Consider increasing `graph-compiler.kafka.consumer.max-poll-records`

### 3. Dead Letter Queue Accumulation

**Symptoms:**
- `kafka.dlq.depth` metric > 0
- Users report missing compilation results

**Diagnosis:**
```bash
# Check DLQ depth
kubectl exec -it deployment/graph-compiler -- \
  curl localhost:8081/actuator/metrics/kafka.dlq.depth
```

**Resolution:**
1. Review DLQ messages to understand failure reason:
   ```bash
   kubectl exec -it deployment/graph-compiler -- \
     curl -X POST localhost:8081/api/v1/admin/replay-dlq \
     -H "Content-Type: application/json" \
     -d @dlq-message.json
   ```
2. If messages are permanently invalid, purge the DLQ topic
3. If messages are transient failures, replay after fixing the root cause

### 4. Pod OOM (Out of Memory)

**Symptoms:**
- Pod restarts frequently
- Logs show `java.lang.OutOfMemoryError`

**Diagnosis:**
```bash
# Check pod restart count
kubectl get pods -l app=graph-compiler

# Check memory usage
kubectl top pod -l app=graph-compiler
```

**Resolution:**
1. Increase memory limits in K8s deployment
2. Check for memory leaks (e.g., unbounded caches)
3. Reduce `graph-compiler.validation.max-nodes` if needed

### 5. High Compilation Failure Rate

**Symptoms:**
- `compilation.failed` metric increasing
- Users report validation errors

**Diagnosis:**
```bash
# Check failure rate
kubectl exec -it deployment/graph-compiler -- \
  curl localhost:8081/actuator/metrics/compilation.failed
```

**Resolution:**
1. Review logs for common validation errors
2. Check if a new node type was added without updating `NodeTypeValidator`
3. Check if Kafka messages are malformed

## Monitoring

### Key Metrics

| Metric | Description | Alert Threshold |
|--------|-------------|-----------------|
| `compilation.duration` | Compilation time | p99 > 5s |
| `compilation.failed` | Failed compilations | rate > 5% |
| `compilation.publish.failed` | Kafka publish failures | > 0 |
| `kafka.consumer.lag.total` | Consumer lag | > 1000 |
| `kafka.dlq.depth` | DLQ message count | > 0 |
| `jvm.memory.used` | JVM memory usage | > 80% |

### Dashboards

- **Grafana**: Link to service dashboard
- **Prometheus**: `http://prometheus:9090/graph?g0.expr=rate(compilation_failed_total[5m])`

## Rollback Procedure

1. Check rollout history:
   ```bash
   kubectl rollout history deployment/graph-compiler
   ```

2. Rollback to previous version:
   ```bash
   kubectl rollout undo deployment/graph-compiler
   ```

3. Verify rollback:
   ```bash
   kubectl rollout status deployment/graph-compiler
   ```

## Contact

- **On-call**: [PagerDuty link]
- **Slack**: #graph-compiler-alerts
