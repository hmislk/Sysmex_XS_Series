# Sysmex XS Series Middleware

A Java-based middleware application that interfaces **Sysmex XS-series hematology analyzers** (e.g., XS-500i, XS-1000i) with a Laboratory Information System (LIS). It acts as a communication bridge, receiving patient results from the analyzer and forwarding them to the LIS, as well as pulling test orders from the LIS and sending them to the analyzer.

## Communication Protocol

This middleware uses the **ASTM E1381/E1394** protocol (also known as **LIS2-A2** or **CLSI LIS02**) for communication with the Sysmex analyzer — **not HL7 2.x**.

### Why ASTM, not HL7?

The Sysmex XS series communicates using the ASTM standard, which is the predominant protocol for point-to-point analyzer-to-middleware communication in clinical laboratories. Key characteristics of the protocol as implemented:

| Feature | Detail |
|---|---|
| **Transport layer** | Raw TCP/IP socket connection |
| **Framing** | ASTM low-level protocol: `ENQ` → `ACK` → `STX...ETX` → `ACK` → `EOT` |
| **Record types** | `H` (Header), `P` (Patient), `O` (Order), `R` (Result), `Q` (Query), `L` (Termination) |
| **Delimiters** | Field: `\|`  Component: `^`  Repeat: `\`  Escape: `&` |
| **Checksum** | Modulo-256 hex checksum appended after ETX |
| **Data encoding** | ASCII text |

### Message Flow

**Receiving results from the analyzer (analyzer → middleware → LIS):**

```
Analyzer          Middleware            LIS (HMIS)
   |--- ENQ ---------->|                    |
   |<-- ACK -----------|                    |
   |--- STX H|... ETX->|                    |
   |<-- ACK -----------|                    |
   |--- STX P|... ETX->|                    |
   |<-- ACK -----------|                    |
   |--- STX O|... ETX->|                    |
   |<-- ACK -----------|                    |
   |--- STX R|... ETX->|  (one per test)   |
   |<-- ACK -----------|                    |
   |--- STX L|... ETX->|                    |
   |<-- ACK -----------|--- POST /test_results -->|
   |--- EOT ---------->|                    |
```

**Querying test orders (analyzer → middleware → LIS → middleware → analyzer):**

```
Analyzer          Middleware            LIS (HMIS)
   |--- ENQ ---------->|                    |
   |<-- ACK -----------|                    |
   |--- STX Q|... ETX->|                    |
   |<-- ACK -----------|                    |
   |--- EOT ---------->|                    |
   |                    |--- POST /test_orders -->|
   |                    |<-- orders (JSON) -------|
   |<-- ENQ -----------|                    |
   |--- ACK ---------->|                    |
   |<-- STX H|... ETX -|                    |
   |--- ACK ---------->|                    |
   |<-- STX P|... ETX -|                    |
   |--- ACK ---------->|                    |
   |<-- STX O|... ETX -|                    |
   |--- ACK ---------->|                    |
   |<-- STX L|... ETX -|                    |
   |--- ACK ---------->|                    |
   |<-- EOT -----------|                    |
```

## Architecture

```
┌─────────────────────┐     ASTM/TCP      ┌──────────────────────┐    REST/JSON     ┌──────────┐
│  Sysmex XS Analyzer │ ←───────────────→  │  Middleware (this)    │ ←──────────────→ │   HMIS   │
│  (Hematology)       │    Port from       │                      │   HTTP POST      │  (LIS)   │
│                     │    config.json     │  Sysmex_XS_Server    │                  │          │
└─────────────────────┘                    │  LISCommunicator     │                  └──────────┘
                                           │  SettingsLoader       │
                                           └──────────────────────┘
```

### Key Components

| Class | Responsibility |
|---|---|
| `Sysmex_XS_Series` | Application entry point. Loads settings and starts the TCP server. |
| `Sysmex_XS_Series_Server` | ASTM protocol handler. Manages the low-level ENQ/ACK/STX/ETX/EOT handshake, parses incoming ASTM records (H, P, O, R, Q, L), and constructs outgoing ASTM messages for order queries. |
| `LISCommunicator` | HTTP client for the LIS. Sends results (`POST /test_results`) and pulls orders (`POST /test_orders_for_sample_requests`) using JSON via the HMIS REST API. |
| `SettingsLoader` | Reads `config.json` for analyzer connection settings (port, analyzer name) and LIS connection settings (server URL, credentials). |
| `AnalyzerCommunicator` | Simplified/alternative analyzer server handler (basic ENQ/ACK/EOT). |

## Shared Library

This middleware depends on [`lims-middleware-libraries`](https://github.com/hmislk/lims-middleware-libraries), a shared Java library (via JitPack) that provides common data classes:

- `DataBundle` — container for patient, order, query, and result records
- `PatientRecord`, `OrderRecord`, `ResultsRecord`, `QueryRecord` — ASTM record models
- `MiddlewareSettings`, `AnalyzerDetails`, `LimsSettings` — configuration models

## Prerequisites

- **Java 11** or higher
- **Maven 3.6+**
- A running [HMIS](https://github.com/hmislk/hmis) instance with LIS REST API endpoints available

## Configuration

Create a `config.json` file at the configured path with the following structure:

```json
{
  "analyzerDetails": {
    "analyzerName": "Sysmex_XS_Series",
    "analyzerPort": 9100
  },
  "limsSettings": {
    "limsServerBaseUrl": "http://localhost:8080/hmis/api/lims",
    "username": "your_username",
    "password": "your_password"
  }
}
```

Update the config file path in `SettingsLoader.java` to match your deployment environment.

## Build & Run

```bash
# Build the fat JAR (includes all dependencies)
mvn clean package

# Run
java -jar target/Sysmex_XS_Series-1.0.jar
```

The middleware will start listening for TCP connections from the Sysmex analyzer on the port specified in `config.json`.

## Supported Analyzers

- Sysmex XS-500i
- Sysmex XS-1000i
- Other Sysmex XS-series models that use ASTM E1381/E1394

## Part of the CareCode Middleware Ecosystem

This is one of several analyzer-specific middleware modules in the [HMIS](https://github.com/hmislk/hmis) ecosystem. Each middleware handles the protocol specifics of a particular analyzer family while communicating with the LIS through a common REST API.

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
