---
Core Domain Model (Entity-Relationship Diagram)
---

```mermaid
erDiagram
    TRANSACTION ||--o{ RISK_DECISION : "produces"
    MERCHANT ||--o{ RISK_DECISION : "belongs to"
    MERCHANT ||--o{ MERCHANT_RISK_PROFILE : "has current"
    
    TRANSACTION {
        string id PK
        string merchantId FK
        string transactionId
        double amount
        string currency
        timestamp createdAt
    }

    RISK_DECISION {
        string id PK
        string merchantId FK
        string transactionId FK
        double genAiScore
        string finalDecision "APPROVE / REVIEW / DECLINE"
        string[] firedRules
        string auditTrailJson
        timestamp evaluatedAt
    }

    MERCHANT {
        string id PK
        string name
        string riskTier
        double chargebackRate
    }

    MERCHANT_RISK_PROFILE {
        string merchantId PK
        string riskTier
        double chargebackRate
        int daysActive
        timestamp lastUpdated
    }
