.PHONY: build test run clean docker-up docker-down lint format

# ---- Build ----
build:
	mvn clean package -DskipTests -B

compile:
	mvn clean compile -B

# ---- Test ----
test:
	mvn test -B

test-integration:
	mvn verify -B

coverage:
	mvn test jacoco:report -B
	@echo "Coverage report: target/site/jacoco/index.html"

# ---- Run ----
run:
	mvn spring-boot:run -Dspring-boot.run.profiles=local

run-with-genai:
	mvn spring-boot:run -Dspring-boot.run.profiles=local \
		-Dspring-boot.run.arguments="--app.genai.enabled=true"

# ---- Docker ----
docker-build:
	docker build -t payment-intelligence-engine:latest .

docker-up:
	docker-compose up -d

docker-down:
	docker-compose down -v

docker-logs:
	docker-compose logs -f payment-intelligence-engine

# ---- Kafka ----
kafka-produce-sample:
	@echo '{"transaction_id":"TXN-SAMPLE-001","merchant_id":"MERCH-001","merchant_category_code":"5411","merchant_name":"Test Restaurant","amount":125.50,"currency":"USD","card_type":"VISA","card_last_four":"4242","cardholder_country":"US","merchant_country":"US","channel":"CARD_PRESENT","is_recurring":false,"device_fingerprint":"fp-sample","ip_address":"192.168.1.1","timestamp":"2026-03-14T10:30:00Z","metadata":{}}' | \
	docker exec -i kafka kafka-console-producer \
		--bootstrap-server localhost:9092 \
		--topic payment.transactions

kafka-consume-decisions:
	docker exec kafka kafka-console-consumer \
		--bootstrap-server localhost:9092 \
		--topic payment.risk.decisions \
		--from-beginning

# ---- API ----
api-evaluate:
	curl -s -X POST http://localhost:8080/api/v1/evaluate \
		-H "Content-Type: application/json" \
		-d '{"transaction_id":"TXN-CURL-001","merchant_id":"MERCH-001","merchant_category_code":"5411","merchant_name":"Test","amount":250.00,"currency":"USD","card_type":"VISA","card_last_four":"1234","cardholder_country":"US","merchant_country":"US","channel":"CARD_PRESENT","is_recurring":false,"timestamp":"2026-03-14T10:00:00Z"}' | jq .

api-health:
	curl -s http://localhost:8080/actuator/health | jq .

# ---- Clean ----
clean:
	mvn clean
	docker-compose down -v 2>/dev/null || true
