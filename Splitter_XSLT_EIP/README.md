Architecture Flow:
```text
 Input Message
      ↓
Read field value
      ↓
Lookup destination from JSON config
      ↓
EHCache lookup
      ↓
Dynamic routing to queue
```
 Input Exchange of messages:
```text
{
  "type": "PAYMENT",
  "orderId": 101,
  "customer": "Sai",
  "amount": 500
}, {
  "type": "SHIPPING",
  "orderId": 102,
  "customer": "Ram",
  "address": "123 I Street",
  "city": "Bentonville",
  "postalCode": "75001"
}, {
  "type": "INVENTORY",
  "productId": "P101",
  "productName": "Laptop",
  "quantity": 10,
  "warehouse": "W1"
},
{
  "type": “Unknown”,
  "productId": "P102”,
  "productName": “Desktop”,
  "quantity": 20,
  "warehouse": "W2”
}
```
