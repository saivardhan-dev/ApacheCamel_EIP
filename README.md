## Apache Camel

Camel = Integration framework implemented in Java designed to simplify the connections between multiple systems or applications or data sources.

Apache Camel is a Java integration framework that implements Enterprise Integration Patterns to route, transform, and process messages between systems.

Core components that process every message:

	Exchange(Message, body, header) → Processor(Modifications) → Route Engine(Acts like a controller)

* Exchange (The Container): The Exchange is the main container that travels through the Camel route. It contains components like Message, Header, Properties, Exception. So the Exchange is the object that moves through every step of the route.
* Message (Actual Data): Inside the Exchange is the Message object. Message contains Body, Headers. Camel uses headers heavily for routing decisions.

		Exchange
		   In(Message)
		      Body: "Order123"
   		      Headers:
                JMSDestination = orders.producer
                Timestamp = 12345678

* Processor (Processing Unit): A Processor is a component that performs some operation on the Exchange. Every Camel step internally becomes a Processor. Examples of processes: from, to, marshal, split, choice, to.
* Route Engine (The Execution Pipeline): The Route Engine executes the entire Camel route. It runs processors in sequence.
	Example route:
  
		from("rest:post:/orders")
		   .marshal().json()
		   .split().jsonpath("$.items[*]")
 		  .to("jms:queue:inventory");
      
	Internally Camel converts this route into a chain of processors.
	Execution pipeline:
  
		Route Engine
		        ↓
		Processor 1 → Entry
		Processor 2 → Marshal
		Processor 3 → Split
		Processor 4 → Send to Queue
            ↓
         Consumer

    
	Each processor receives the same Exchange object.

* Internal Message Flow: When a message arrives, Camel processes it like this:
  
The Exchange moves through each processor step-by-step.

So Camel helps you:
	•	receive messages
	•	route messages
	•	transform data
	•	split or aggregate messages
	•	connect different systems


Camel acts as a message routing engine. Instead of writing complex integration logic manually, you define routes.

	from("rest:post:/orders")
	   .marshal().json()
	   .split().jsonpath("$.items[*]")
 	  .to("jms:queue:inventory");
    
Camel interprets this route and executes it step-by-step.

Camel supports 300+ integration connectors. So Camel can connect almost any system to another system.




