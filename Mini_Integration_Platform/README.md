# Enterprise Integration System

"A config-driven enterprise message routing platform built on Apache Camel — where adding a new scenario, changing routing rules, or supporting a new message structure requires zero Java code changes."

## Architecture
"This platform is inspired by IBM ACE. Every routing decision is driven by Scenarios.json — no Java changes needed."
```text
Producer
    ↓
GATEWAY.ENTRY.WW.SCENARIO2.1.IN  ← entry queue
    ↓
ScenarioEntryRoute (Route1)
    ↓
CORE.ENTRY.SERVICE.IN            ← internal queue
    ↓
CoreProcessingRoute (Route2)
    ↓
GATEWAY.EXIT.WW.SCENARIO2.{TYPE}.1.OUT  ← dynamic exit queue
                                             (CBR decides)

Side channels:
  COMMON.AUDIT.SERVICE.IN      → MongoDB (audits)
  COMMON.EXCEPTION.SERVICE.IN  → MongoDB (exceptions) + AI + Email
  COMMON.DLQ.SERVICE.IN        → raw failed payload, no consumer
```

* Scenarios.json:
```text
{
  "ScenarioName": "Scenario2",
  "Routes": [
    {
      "RouteName": "Route1",
      "service": { "type": "XSLT" }       ← XML transformation
    },
    {
      "RouteName": "Route2",
      "service": {
        "type": "DYNAMIC_TYPE_AMOUNT",
        "queuePattern": "GATEWAY.EXIT.WW.{SCENARIO}.{TYPE}.1.OUT",
        "rules": [
          { "leftOperand": "$.Messages.ElectronicsDetails.ElectronicsDetail.DeviceName" },
          { "leftOperand": "$.Messages.ElectronicsDetails.ElectronicsDetail.Price",
            "threshold": 1000 }
        ]
      }
    }
  ]
}
```
Happy Path — Single Message
```text
{
  "ApplicationID": "ID-001",
  "Messages": {
    "ElectronicsDetails": {
      "ElectronicsDetail": { "DeviceName": "TV", "Price": 8000 }
    }
  }
}
Route1 ENTRY ✅
Route1 EXIT  ✅
Route2 ENTRY ✅
Route2 EXIT  ✅
```
* "Route1 handles transformation. Route2 has the CBR rules — JSONPath expressions that navigate directly into the message structure. The platform reads these at startup and builds the routes dynamically."
* "The platform reads Scenarios.json, loads everything into EhCache, and automatically creates Camel consumers for every scenario's entry queue. No hardcoded queue names anywhere in Java."
* The message traveled through Route1 and Route2. The CBR evaluated the JSONPath, extracted the parent key ElectronicsDetail as the queue segment, and routed dynamically. Four audit records in MongoDB — one for each leg.
  
Failure Path — Price Below Threshold
```text
{
  "ApplicationID": "ID-002",
  "Messages": {
    "ElectronicsDetails": {
      "ElectronicsDetail": { "DeviceName": "TV", "Price": 800 }
    }
  }
}
[DynamicQueueResolver] DOUBLE '$.Messages.ElectronicsDetails.ElectronicsDetail.Price'
                       = 800.0 LTE 1000 → MATCH
→ throws RuntimeException: Amount 800.0 below minimum threshold 1000.0
[CoreProcessingRoute]  onException fired
[ExceptionJsonBuilder] Built exception JSON — code='ExceptionCode4'
→ wireTap 1 → COMMON.EXCEPTION.SERVICE.IN
→ wireTap 2 → COMMON.DLQ.SERVICE.IN
[ExceptionRoute]       Persisted — id='...' code='ExceptionCode4'
[ExceptionAnalysisAgent] Agent turn 1/6 → checkAuditHistory
[ExceptionAnalysisAgent] Agent turn 2/6 → checkExceptionDetail
[ExceptionAnalysisAgent] Agent produced final answer
[NotificationService]  Email sent → subject='[LOW] Exception Alert — Route2 | Scenario2'
```
* The DOUBLE rule fired. The message was rejected, sent to DLQ, persisted to MongoDB, and the AI agent analysed the full journey — which routes succeeded, which failed, and why. An email was sent automatically.
* "Scenario2 accepts both XML and JSON. XML is automatically transformed to JSON via XSLT before CBR routing. The downstream system always receives the same format."
* "Full observability. Every message is tracked — every route leg, every exception, every AI analysis. All stored in MongoDB."
* If I want to send a completely different message structure, different field names, different nesting, different exit queue. Zero Java changes required, only Scenarios.json need to be changed. This is what config-driven means.
  
```text
Summary:
  ✅ Config-driven routing       — Scenarios.json drives everything
  ✅ Dynamic CBR                 — JSONPath rules, any message structure
  ✅ XML + JSON support          — XSLT transformation on Route1
  ✅ Full audit trail            — MongoDB, 4 records per message
  ✅ AI exception analysis       — Groq LLaMA, agentic tool loop
  ✅ Email notifications         — Gmail SMTP, journey + analysis
  ✅ DLQ                         — raw payload preserved
  ✅ Zero Java for new scenarios — only Scenarios.json changes
```
