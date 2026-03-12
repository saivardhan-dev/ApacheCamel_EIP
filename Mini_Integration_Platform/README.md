## Integration platforms:

Integration platforms exist because modern enterprises have dozens of systems built on different technologies that all need to talk to each other. Without a central integration layer, you end up with point-to-point spaghetti, exponentially growing connections, no visibility, tight coupling, and no resilience. An integration platform like IBM ACE solves this by acting as a central hub that handles routing, transformation, protocol mediation, guaranteed delivery, security, and monitoring in one place. Every system only needs to know about the integration platform, not about every other system in the enterprise. Our Mini Integration Platform demonstrates these same principles at a smaller scale using Apache Camel, ActiveMQ, EhCache, and MongoDB.

Architecture Flow: 
```text
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│  STARTUP                                                                                    │
│                                                                                             │
│  Scenarios.json ──► scenarioCache (EhCache)       ExceptionCodeList.json ──► exceptionCodeCache   │
│                           │                                                                 │
│                           ▼                                                                 │
│            Camel registers routes at startup                                                │
│            ScenarioEntryRoute (Route-1 × 2)  +  CoreProcessingRoute (shared)              │
│            AuditRoute (consumer)            +  ExceptionRoute (consumer)               │
└─────────────────────────────────────────────────────────────────────────────────────────────┘


Producer
   │
   ▼
GATEWAY.ENTRY.WW.SCENARIO1.1.IN
   │
   ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│  Route-1  (ScenarioEntryRoute)                                                            │
│                                                                                             │
│  ScenarioProcessor                                                                        │
│     stamp OriginalMessageId, SourcePutTimestamp                                             │
│     stamp RoutingSlip_Country, RoutingSlip_Scenario, RoutingSlip_InstanceId                │
│     stamp RouteInfo_RouteName=Route1, RouteSource, RouteTarget                              │
│     stamp LegIndex = 0                                                                      │
│                                                                                             │
│  .to(CORE.ENTRY.SERVICE.IN)  ✅  main job done                                              │
│                                                                                             │
│  wireTap fires ──────────────────────────────────────────────────────────────────────────► │
│  (async — separate thread — main thread released immediately)                               │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
   │                                              │
   │ main thread continues                        │ wireTap thread
   ▼                                             ▼
CORE.ENTRY.SERVICE.IN                           AuditJsonBuilder.build()
                                                   reads RouteInfo headers
                                                   builds Route-1 Audit JSON
                                                        │
                                                        ▼
                                                 COMMON.AUDIT.SERVICE.IN
                                                        │
                                                        ▼
                                                 AuditRoute → AuditPersistenceService
                                                        │
                                                        ▼
                                                 MongoDB "audits" { Route1 leg }


CORE.ENTRY.SERVICE.IN
   │
   ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│  CoreProcessingRoute  (shared — all scenarios, all legs Route-2+)                          │
│                                                                                             │
│  EhCache lookup                                                                           │
│     read RoutingSlip headers → getScenario(country, name, instanceId)                       │
│     LegIndex++ (0→1) → Routes[1] = Route2                                                  │
│     update RouteInfo_RouteName=Route2, RouteSource, RouteTarget                             │
│                                                                                             │
│  MessageValidatorProcessor
│     validate JSON body                                                                      │
│          │                                                                                  │
│          ├── type = ORDER ──────────────────────────────────────────────────────────────►   │
│          │                                                                                  │
│          └── type = ERROR ──► throws RuntimeException                                      │
└──────────────────────────────────────┬──────────────────────────────┬──────────────────────┘
                                       │                              │
              HAPPY PATH ─────────────┘                              └───────── EXCEPTION PATH
                    │                                                              │
                    ▼                                                              ▼
     .toD(GATEWAY.EXIT.WW.SCENARIO1.1.OUT)  ✅              onException handler fires
                    │                                                              │
     wireTap fires ──┘                                       wireTap fires ──────────┘
     (async — separate thread)                               (async — separate thread)
                    │                                                              │
                    ▼                                                              ▼
     AuditJsonBuilder.build()                          ExceptionJsonBuilder.build()
       current leg RouteInfo headers                      reads EXCEPTION_CAUGHT prop
       builds Route-2 Audit JSON                          reads current leg headers
                    │                                      resolves code from EhCache
                    ▼                                                              │
     COMMON.AUDIT.SERVICE.IN                                                       ▼
                    │                                          COMMON.EXCEPTION.SERVICE.IN
                    ▼                                                              │
     AuditRoute → AuditPersistenceService                                         ▼
                    │                                      ExceptionRoute → ExceptionPersistenceService
                    ▼                                                              │
     MongoDB "audits" { Route2 leg }                                              ▼
                                                           MongoDB "exceptions" {
                                                             exceptionCode: ExceptionCode3
                                                             routeName: Route2
                                                             endTimestamp: ""
                                                             stacktrace: ...
                                                           }


┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│  MONGODB RESULT — linked by OriginalMessageId                                               │
│                                                                                             │
│  Happy Path:    audits × 2  (Route1 + Route2)       exceptions × 0                        │
│  Exception Path:audits × 1  (Route1 only)           exceptions × 1  (Route2, Code3)      │
│                                                                                             │
│  Correlate:  db.audits.find({ originalMessageId: "ID:xyz" })                              │
│              db.exceptions.find({ originalMessageId: "ID:xyz" })                          │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```
 
Collections in Mongodb:
```text
Audit DB      → audit_logs collection
Exception DB  → exception_logs collection
```
