# Dashboard Screenshots

To capture screenshots for the README:

1. Start the full stack: `cd docker && docker compose up -d`
2. Seed data: `bash scripts/seed-data.sh`
3. Open Grafana at http://localhost:3000
4. Navigate to each dashboard and take a screenshot:
   - **System Overview**: http://localhost:3000/d/system-overview
   - **Order Flow**: http://localhost:3000/d/order-flow
   - **Trace Explorer**: http://localhost:3000/d/trace-explorer
   - **Kafka Consumer Lag**: http://localhost:3000/d/kafka-consumer
5. Save screenshots to this directory as:
   - `dashboard-system-overview.png`
   - `dashboard-order-flow.png`
   - `dashboard-trace-explorer.png`
   - `dashboard-kafka-consumer.png`
6. Update the README to reference these images
