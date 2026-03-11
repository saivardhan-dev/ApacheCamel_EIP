Architecture Flow: 
```text
Scenarios.json + ExceptionCodeList.json 
	↓ 
EhCache (loaded at startup) 
	↓ 
Dynamic Source Queues created per scenario 
	↓ 
Route-1: Message received 
		→ Tag with OriginalMessageID + SourcePutTimestamp 
		→ Store routing info in headers 
		→ Send Audit record 
		→ COMMON.AUDIT.SERVICE.IN 
	↓ 
Route-2: Message processed 
		→ Read headers from Route-1 
		→ Execute business logic 
		→ Send Audit record 
		→ COMMON.AUDIT.SERVICE.IN 
	↓ 
(If error at any point) 
		→ Send to COMMON.EXCEPTION.SERVICE.IN
	↓ 
GATEWAY.EXIT.WW.SCENARIO1.1.OUT
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
Target Queue  If any exceptions Occurs: Exception occurs
        ↓
ExceptionProcessor
        ↓
COMMON.EXCEPTION.SERVICE.IN
```
Collections in Mongodb:
```text
Audit DB      → audit_logs collection
Exception DB  → exception_logs collection
```
