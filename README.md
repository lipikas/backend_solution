## 📋 Project Overview

I built a RESTful API service that manages client transactions and statements. 

## 🎯 Objective

The backend system performs:
- Client transaction processing (credits and debits)
- Client statement generation
- Business rule validation
- High-performance concurrent operations

## 📁 Project Structure

- **README.md**: This document with set up instructions
- `app.py`: backend service code
- `docker-compose.yml`: Configuration for docker deployments (db, backend server, nginx)
- `Dockerfile`: Docker configurations
- `init.sql`: Database Schema
- `nginx.conf`: nginx configurations file
- `requirements.txt`: libraries to install
- **simulations/**: Contains Gatling performance test scenarios
  - `pom.xml`: Maven configuration for running Gatling tests
  - `src/main/scala/MadeBackendTestSimulation.scala`: Scala-based load testing simulation
  - `target/gatling/index.html`: Testing Report of Testcases passed and failed

## 🚀 Getting Started

### Prerequisites

- Install latest python
- Create python virtual environment
- Install libraies from requirements doc using pip install -r requirements.txt
- Select python interpretor as the one from virtual environement
- Install Java 11 or higher (required for Maven and Gatling)
- Install Maven for running the performance tests
- Install Gatling Community Edition: [Download here](https://www.gatling.io/download-gatling-community-edition)
- Install Docker and start Docker instance.
- Run docker compose up --build to start the backend, nginx and db servers

### Solution

1. In app.py, I utilized the FASTAPI and asyncpg libraries to create the backend service. FASTAPI was used as routing logic for HTTP requests and also for the validation of business rules. The asyncpg library was used as a postgres driver to connect to postgres instance and create tables from DB schema. The backend service accepts GET and POST requests, checks against business rules if http response should be 200, 500s, 422 or 404 depending on user requests. For valid POST requests, the backend service updates the client and transaction tables. To handle concurrent transactions, the code handles requests as transactions and locks the row during POST requests.

2. The DB Schema consists of clients and transactions table. Client table maps all the customers and credit limits. Transactions table preview a history of debit and credit transactions recorded. 

3. Configurations were set up for installing the docker servers (one for database, two for backend servers, and one the load balancer nginx). The configurations were loaded into the DockerFile, docker-compose.yml, and nginx.conf files.

### Running the Performance Tests

1. **Run the simulation**:
   ```bash
   cd simulations
   mvn gatling:test -Dgatling.simulationClass=MadeBackendTestSimulation
   ```

   The simulation will test API at `http://localhost:9999` with various load patterns and business rule validations.

   **Note**: Make sure API is running on `http://localhost:9999` before starting the simulation. The test will fail if it cannot connect to API.

### Understanding Test Results

After running the simulation, you'll see detailed results including:

- **Response times** for each endpoint
- **Success/failure rates** for different scenarios
- **Error messages** for failed requests
- **Throughput metrics** (requests per second)

**Common issues to watch for:**
- **HTTP 500 errors**: Check server logs for exceptions
- **HTTP 422 errors**: Verify business rule validation (balance limits, input validation)
- **HTTP 404 errors**: Ensure client IDs 1-5 are properly initialized
- **Timeout errors**: Check if API can handle the expected load
- **Data consistency errors**: Verify concurrent transaction handling

**Sample successful output:**
```
Simulation made-backend-test.MadeBackendTestSimulation completed in 4 minutes
================================================================================
---- Global Information --------------------------------------------------------
> request count                                         1000 (OK=1000   KO=0     )
> min response time                                       10 (OK=10      KO=-     )
> max response time                                      500 (OK=500     KO=-     )
> mean response time                                     150 (OK=150     KO=-     )
> std deviation                                          100 (OK=100     KO=-     )
> response time 50th percentile                          120 (OK=120     KO=-     )
> response time 75th percentile                          200 (OK=200     KO=-     )
> response time 95th percentile                          400 (OK=400     KO=-     )
> response time 99th percentile                          450 (OK=450     KO=-     )
> mean requests/sec                                     250 (OK=250     KO=-     )
================================================================================
```

## 📚 API Requirements

### Base URL
API should run on `http://localhost:9999`

### Endpoints

#### 1. Create Transaction
**POST** `/clients/{id}/transactions`

Creates a new transaction for a client.

**Request Body:**
```json
{
  "value": 1000,
  "type": "c",
  "description": "description"
}
```

**Parameters:**
- `id` (path): Client ID (integer, 1-5 for valid clients, 6 for testing 404 responses)
- `value` (body): Transaction amount in cents (positive integer)
- `type` (body): Transaction type - "c" for credit, "d" for debit
- `description` (body): Transaction description (string, 1-10 characters, non-empty)

**Response (200 OK):**
```json
{
  "limit": 100000,
  "balance": 1000
}
```

**Response (422 Unprocessable Entity):**
- Invalid transaction data
- Business rule violations

#### 2. Get Statement
**GET** `/clients/{id}/statement`

Retrieves the client's statement with recent transactions.

**Parameters:**
- `id` (path): Client ID (integer, 1-5 for valid clients, 6 for testing 404 responses)

**Response (200 OK):**
```json
{
  "balance": {
    "total": 1000,
    "limit": 100000,
    "date": "2024-01-01T00:00:00Z"
  },
  "latest_transactions": [
    {
      "value": 1000,
      "type": "c",
      "description": "description",
      "executed_at": "2024-01-01T00:00:00Z"
    }
  ]
}
```

**Response Fields:**
- `balance.total`: Current account balance (sum of all transactions)
- `balance.limit`: Client's credit limit
- `balance.date`: Statement generation timestamp
- `latest_transactions`: **Array of up to 10 most recent transactions**, ordered by execution date (most recent first)

**Response (404 Not Found):**
- Client ID doesn't exist

## 🔧 Business Rules

### Client Configuration
- **Client 1**: Limit = $1,000.00 (100,000 cents)
- **Client 2**: Limit = $800.00 (80,000 cents)
- **Client 3**: Limit = $10,000.00 (1,000,000 cents)
- **Client 4**: Limit = $100,000.00 (10,000,000 cents)
- **Client 5**: Limit = $5,000.00 (500,000 cents)
- **Client 6**: **Intentionally empty** - Used for testing 404 responses

**Important**: Client 6 is deliberately not initialized and should return HTTP 404 for all requests. This is part of the simulation test scenarios to verify proper error handling.

### Transaction Rules
1. **Credit transactions**: Always allowed, increase balance
2. **Debit transactions**: Only allowed if resulting balance ≥ -limit
3. **Value validation**: Must be positive integer
4. **Type validation**: Must be exactly "c" or "d"
5. **Description validation**: 1-10 characters, non-empty, not null

### Statement Rules
1. **Latest transactions**: Show up to 10 most recent transactions
2. **Ordering**: Most recent first (descending by execution time)
3. **Balance calculation**: Sum of all transactions (credits positive, debits negative)

## 🧪 Testing Implementation

### Manual Testing
```bash
# Test transaction creation
curl -X POST http://localhost:9999/clients/1/transactions \
  -H "Content-Type: application/json" \
  -d '{"value": 1000, "type": "c", "description": "test"}'

# Test statement retrieval
curl http://localhost:9999/clients/1/statement

# Test invalid transaction (should return 422)
curl -X POST http://localhost:9999/clients/1/transactions \
  -H "Content-Type: application/json" \
  -d '{"value": 1.5, "type": "d", "description": "invalid"}'

# Test non-existent client (should return 404)
curl http://localhost:9999/clients/6/statement
```

### Performance Testing with Gatling Simulation

**Run the simulation test:**
```bash
cd simulations
mvn gatling:test -Dgatling.simulationClass=MadeBackendTestSimulation
```

**What the simulation tests:**
- Concurrent transaction processing
- Business rule consistency validation
- Response times under load
- Data integrity across concurrent operations
- Error handling scenarios

**Important**: Make sure API is running on `http://localhost:9999` before starting the simulation!