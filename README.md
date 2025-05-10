# File Sharing System

## Overview (Original email message)
We decided to make a file sharing system like Google Drive, offering a privacy-conscious and offline-friendly solution for teams that need secure, local file sharing without relying on external services where multiple clients can connect to a central server using TCP sockets to upload, download, and list shared files in real time. The project will use multi-threading such that each client connection is handled in a separate thread, and file operations will be managed by the server. I will implement thread synchronization to maintain consistency in shared resources, especially when multiple users access the file system or write to the shared log and database at the same time. This system will be built using Java’s socket API to establish a reliable client-server TCP architecture for local file transfer. To allow persistent tracking and accountability, all file actions such as uploads and downloads will be recorded in a relational database using JDBC, which will support auditing and querying past file activity. Users will interact with the system through a simple command-line interface, which allows them to issue commands like upload, download, and list from any connected machine on the network.

## Requirement Mapping
- **Thread Concurrency (with synchronization)**  
  A configurable thread pool accepts incoming sockets; each client is handled by a `ClientHandler` thread. Shared resources (database connection, file index) are protected with `synchronized` blocks in `Database` and `FileManager`.

- **File I/O**  
  `FileManager` reads and writes files with `FileInputStream`/`FileOutputStream`, handles byte streams and SHA-256 checksum verification.

- **Networking (sockets)**  
  The server listens on a TCP port via `ServerSocket`. Clients connect with `Socket` and issue text-based commands (`UPLOAD`, `DOWNLOAD`, `DELETE`, `LIST`, `LOGS`).

- **JDBC**  
  An embedded SQLite database (`file_storage.db`) is accessed via JDBC. Every file operation is recorded in a `logs` table; the `LOGS` command queries recent entries.

*(Graphics was omitted since only three of the five listed topics are required.)*

## Features
- **UPLOAD `<filepath>`**  
  Send a local file to the server. The file is stored on disk, logged in the database, and a notification is broadcast to all connected clients.

- **DOWNLOAD `<filename>`**  
  Retrieve a file from the server to the local client.

- **DELETE `<filename>`**  
  Remove a file from server storage and record the deletion in the log.

- **LIST**  
  Display all files currently stored on the server.

- **LOGS**  
  Show recent upload, download, and delete events from the database.

- **Broadcasting Notifications**  
  When a user logs in or performs any file operation, all connected clients receive a real-time notification so every client’s file listing stays up to date without polling.

## Running Instructions

### 1. Running the System Using the JAR

Ensure the following files and directories exist in the same folder:

* `FileSharingSystem.jar`
* `resources/config.properties`
* `logs/file_storage.db`

#### Step 1: Start the Server

In one terminal, run:

```bash
java -cp FileSharingSystem.jar MainServer
```

#### Step 2: Start Clients

Open one or more additional terminals and run:

```bash
java -cp FileSharingSystem.jar network.Client
```

Each client can upload, download, delete, and list files, and all connected clients will receive broadcast messages when any file operation occurs.

---

### 2. Running the System by Manual Compilation

If you prefer to compile and run the program manually:

#### Step 1: Compile All Source Files

From the root directory containing `src/`, run:

```bash
javac -d out $(find src -name "*.java")
```

This compiles all Java files into the `out/` directory.

#### Step 2: Run the Server

In the `out/` directory, run:

```bash
java MainServer
```

#### Step 3: Run Clients

In separate terminals, also from the `out/` directory, run:

```bash
java network.Client
```

This setup allows you to simulate multiple clients connected to a single server instance.

---

### Configuration File

The `resources/config.properties` file must contain the following settings:

```properties
server.host=localhost
server.port=9000
server.max_threads=10
db.url=jdbc:sqlite:logs/file_storage.db
```

Make sure the database file `logs/file_storage.db` exists prior to running the system to ensure logging functionality works correctly.

## Configuration
**resources/config.properties**:
```properties
server.host=localhost
server.port=9000
server.max_threads=10
db.url=jdbc:sqlite:file_storage.db
````

## Directory Structure
```
File-Sharing-System/
│
├── MainServer.java              # Entry point to start the TCP server
├── utils/
│   └── FileManager.java         # Handles reading, writing, and verifying file content
│
├── database/
│   ├── Database.java            # Manages SQLite connections and query logic
│   └── connectionpool/         # (Optional) Connection pool logic for database access
│
├── network/
│   ├── Client.java              # Client-side interface for sending commands
│   └── SocketUtils.java        # Helper methods for sending/receiving over sockets
│
├── resources/
│   └── config.properties        # Server config: host, port, thread count, DB URL
│
├── logs/
│   └── file_storage.db         # SQLite database storing file operation logs
│
├── Demo/
│   └── (Screenshots)           # Demo screenshots to visualize usage
│
└── README.md                    # Project overview and documentation
```


## Demo
Screenshots in Demo folder for result reference

## Authors
Mohan Lu (ml7612)
Audrey Zhao (rz2536)

## Course Information
**Course**: CSUY 3913 – Java Programming (Spring 2025)
**Requirements met**: Thread concurrency, File I/O, Networking, JDBC
**Due date**: May 10, 2025 23:59:59 EDT
