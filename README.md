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

 Internal Message Flow: When a message arrives, Camel processes it like this:

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



## Integration Pattern  Flow:
<img width="977" height="655" alt="pes-and Fiters" src="https://github.com/user-attachments/assets/02d92a4c-6afd-49c1-b923-14ad61bd3c45" />

1. Overall Idea of the Integration Pattern Language:
   
The central concept is:

 Application A → Messaging System → Application B

Instead of applications communicating directly, we use an integration layer built on messaging patterns.
The process typically follows:

	Application A
   	  	  ↓
	Messaging Endpoint
	      ↓
	Message Channel
	      ↓
	Router
	      ↓
	Translator
	      ↓
	Message Channel
	      ↓
	Messaging Endpoint
	      ↓
	Application B

Each part follows a specific integration pattern.

3. Message Construction: This section defines how messages are created and structured before they are sent.

4. Messaging Endpoints: Endpoints are entry and exit points where applications interact with the messaging system.

5. Messaging Channels: Channels are paths through which messages travel.

6. Message Routing: Routing determines where messages should go. 

7. Message Transformation: Transformation patterns modify message data.

8. System Management Patterns: These patterns help monitor and control the integration system.

9. Monitoring: Monitoring tools track the health of the integration system. Monitoring helps ensure the integration system runs reliably.


Scenario:  complete enterprise integration flow using Apache Camel 

Full Message Flow:
Order Service → Entry(REST endpoint) → Content-Based Router (CBR) → Transformation(JSON → XML) → Splitter → Exit(JMS Queue) → Payment Service

An Order Service sends orders to the integration system. 
***
	Example order:
	{
	  "orderId": 101,
	  "type": "international",
	  "items": [
	    {"product": "Laptop"},
	    {"product": "Mouse"}
	  ]
	}
***

The integration system should:

	1.	Entry – receive the order
	2.	CBR – check if it is a specific field(domestic or international) and route to the respective queue
	3.	Transformation – convert JSON → XML
	4.	Splitter – process each item separately
	5.	Exit – send each item to a queue for processing


Step 1 — Entry (Receiving the Message): -Camel receives the message from an endpoint.
Example: REST API

	from("rest:post:/orders")
	
Flow:

	Order Service → Camel Entry Endpoint
Camel now has the message inside its routing engine.

Step 2 — Content-Based Router (CBR):
-Camel decides where the message should go based on its content.

	.choice()
	    .when(jsonpath("$.type == 'international'"))
	        .to("direct:international")
	    .otherwise()
	        .to("direct:domestic")
	.end();
Flow:

			 Entry
 		       ↓ 
			  CBR
 	     ↙       	  ↘
	Domestic   International

Camel uses JSONPath to inspect the message.

Step 3 — Transformation:
Now we convert the message format.
Example: JSON → XML

	from("direct:international")
  	  .marshal().jacksonxml();
Flow:

	JSON Order
	    ↓
	Camel Transformation
	    ↓
	XML Order
Example transformed message:

	<Order>
	   <orderId>101</orderId>
	</Order>
This step implements the Message Translator pattern.

Step 4 — Splitter:
The order contains multiple items.
We split them into individual messages.

	.split().jsonpath("$.items[*]")
Flow:

	  Order
	   ↓
	 Splitter
     ↙      ↘
 	Item1   Item2
Now each item becomes an independent message.
Example:
Item Message 1 → Laptop
Item Message 2 → Mouse
Each message continues through the route.

Step 5 — Exit (Send to Destination):
Finally Camel sends each item to a destination system.
Example: JMS queue

	.to("jms:queue:inventoryProcessing");
Flow:

    	  Splitter
		   ↓
    	 JMS Queue
		   ↓
  	 Inventory Service
This is the exit point of the integration flow.



