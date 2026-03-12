Integration platforms exist because modern enterprises have dozens of systems built on different technologies that all need to talk to each other. Without a central integration layer, you end up with point-to-point spaghetti — exponentially growing connections, no visibility, tight coupling, and no resilience. An integration platform like IBM ACE solves this by acting as a central hub that handles routing, transformation, protocol mediation, guaranteed delivery, security, and monitoring in one place. Every system only needs to know about the integration platform — not about every other system in the enterprise. Our Mini Integration Platform demonstrates these same principles at a smaller scale using Apache Camel, ActiveMQ, EhCache, and MongoDB.

Architecture Flow: 
```text
Producer
   │
   ▼
GATEWAY.ENTRY.WW.SCENARIO1.1.IN
   │
   ▼
Route-1 (ScenarioEntryRoute)
   │  ScenarioProcessor → stamp headers
   │  .to(CORE.ENTRY.SERVICE.IN) ✅
   │
   ├──► wireTap(audit) ──► COMMON.AUDIT.SERVICE.IN ──► MongoDB "audits" { Route1 }
   │         async
   ▼
CORE.ENTRY.SERVICE.IN
   │
   ▼
CoreProcessingRoute
   │  EhCache lookup → LegIndex++ → update RouteInfo
   │  MessageValidatorProcessor
   │       │
   │  type=ORDER ──► .toD(GATEWAY.EXIT) ✅
   │                      │
   │                      ├──► wireTap(audit) ──► MongoDB "audits" { Route2 }
   │                      │         async
   │
   └──type=ERROR ──► onException
                         │
                         └──► wireTap(exception) ──► COMMON.EXCEPTION.SERVICE.IN
                                   async                    │
                                                            ▼
                                                   MongoDB "exceptions" { ExceptionCode3 }
```
 Flow:
```text
Scenario Source Queue
        ↓
ScenarioEntryRoute
        ↓
  Audit Event
        ↓
CORE.ENTRY.SERVICE.IN
        ↓
CoreProcessingRoute
        ↓
  Audit Event
        ↓
  Target Queue
        ↓
ExceptionProcessor,  If any exceptions Occurs: Exception Audit
        ↓
COMMON.EXCEPTION.SERVICE.IN
```
Collections in Mongodb:
```text
Audit DB      → audit_logs collection
Exception DB  → exception_logs collection
```
