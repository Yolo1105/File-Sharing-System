# FileSharingSystem

## Overview

FileSharingSystem is a Java-based, privacy-conscious, offline-friendly file-sharing application that allows multiple clients on a local network to connect to a central server over TCP, upload, download, and list files in real time. All operations are persisted in an embedded relational database for auditing and accountability.

This project satisfies the course requirement to include **at least three** of the following topics:

- Thread concurrency with synchronization  
- File I/O  
- Networking (sockets)  
- JDBC  
- Graphics (optional)

---

## Features

- **Concurrent client handling**  
  Each client connection is served in its own thread; shared resources (file store, logs, database) are protected via synchronization.

- **File operations**  
  - **Upload**: send a file from client → server  
  - **Download**: retrieve a file from server → client  
  - **List**: view all files currently available on the server

- **Persistent logging and metadata**  
  All file events (upload/download) are recorded in an embedded SQLite database via JDBC.

- **Command-line interface**  
  Clients interact through a simple text-based prompt.

---

## System Requirements

- Java 11 (or higher)  
- SQLite JDBC driver (`sqlite-jdbc-<version>.jar`)  
- (Optional) Java Swing if GUI client is enabled

---

## Project Structure

