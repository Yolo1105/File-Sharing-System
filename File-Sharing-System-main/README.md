# File-Sharing-System
# Java File Sharing System

A robust client-server application for securely sharing files across a network.

The Java File Sharing System provides a reliable solution for organizations needing to transfer files securely across networks. Built with Java's multi-threading capabilities, this system enables simultaneous file uploads and downloads while maintaining data integrity through advanced checksum verification. The dual interface options accommodate both technical and non-technical users, making file sharing accessible to everyone in your organization. With comprehensive logging and real-time activity broadcasts, administrators can easily monitor system usage and troubleshoot any issues that arise. This system is designed to be easily deployable in various network environments with minimal configuration requirements.

## Overview

This project implements a multi-threaded file sharing system with both command-line and GUI clients. The system allows multiple clients to connect to a central server for uploading, downloading, and listing files in a shared repository.

## Features

- **Multi-client support**: Multiple clients can connect and interact with the server simultaneously
- **File integrity verification**: SHA-256 checksums to validate file transfers
- **Activity logging**: Comprehensive server and client-side logging
- **Concurrent file operations**: Thread-safe file management with lock mechanisms
- **Real-time notifications**: Broadcasting of file activities to all connected clients
- **Database integration**: SQLite database for tracking file operations
- **User-friendly interfaces**: Both command-line and GUI client options

## Components

### Server-side

- **MainServer**: Entry point for the server application
- **ClientHandler**: Processes individual client connections and requests
- **FileManager**: Handles file operations (upload, download, listing)
- **Broadcaster**: Distributes messages to all connected clients
- **DBLogger**: Records file activities in a SQLite database
- **ConnectionPool**: Manages database connections efficiently
- **Logger**: System-wide logging functionality

### Client-side

- **Client**: Command-line interface client
- **ClientGUI**: Graphical user interface client

## System Architecture

The system follows a client-server architecture with the following data flow:

1. Clients connect to the server and identify themselves
2. Clients can request file operations (upload, download, list)
3. Server processes requests and responds accordingly
4. File transfers include size and checksum verification
5. All operations are logged in both text files and a database

## Setup and Configuration

### Prerequisites

- Java 11 or higher
- SQLite (bundled)

### Configuration

The system uses a `config.properties` file with the following properties:

```
server.port=12345
server.max_threads=10
files.directory=server_files/
db.url=jdbc:sqlite:file_logs.db
server.host=localhost
```

If the configuration file is not found, default values will be used.

### Directory Structure

The system creates the following directories:

- `server_files/`: Server's storage location for shared files
- `client_files/`: Local directory for command-line client files to upload
- `downloads/`: Default location for downloaded files

## Running the Application

### Execution Order

For proper system operation, start components in the following order:

1. First, start the server:
   ```
   java MainServer
   ```

2. Then, start one or more clients using either:
   ```
   java Client
   ```
   or
   ```
   java ClientGUI
   ```

The server must be running before any clients attempt to connect. Multiple clients can connect to the server simultaneously.

## Client Commands

### Command-line Client

- `UPLOAD <filename>`: Upload a file to the server
- `DOWNLOAD <filename>`: Download a file from the server
- `LIST`: List all files available on the server
- `EXIT`: Disconnect from the server

### GUI Client

- Use the "Upload" button to select and upload files
- Enter a filename and click "Download" to retrieve files
- Click "List Files" to see available files
- Click "View Logs" to see recent file activities

## Security Features

- File integrity is verified using SHA-256 checksums
- Separate streams for commands and binary data
- Connection pooling for database operations

## Logging

The system provides comprehensive logging at different levels:

- INFO: Normal operation information
- WARNING: Non-critical issues
- ERROR: Operation failures
- FATAL: Critical system failures

Logs are written to both the console and `server.log` file.

## Database Implementation

### Database Engine

The system uses SQLite as its database engine, which offers several advantages:
- Self-contained, serverless database
- Zero configuration required
- Cross-platform compatibility
- Lightweight resource consumption

### Connection Pooling

The `ConnectionPool` class manages database connections efficiently using:
- A pool of pre-established connections
- Semaphore-based concurrency control
- Connection reuse to minimize overhead
- Automatic cleanup on system shutdown

### Schema

The SQLite database is automatically initialized with a single table for tracking file operations:

```sql
CREATE TABLE logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    client TEXT NOT NULL,        -- Client identifier
    action TEXT NOT NULL,        -- Operation type (UPLOAD/DOWNLOAD)
    filename TEXT NOT NULL,      -- Name of the file
    timestamp TEXT NOT NULL      -- ISO-formatted timestamp
)
```

### Database Access

Database operations are performed through the `DBLogger` class, which:
- Creates the schema if it doesn't exist
- Handles connection acquisition and release
- Logs all file operations with timestamps
- Prevents SQL injection through prepared statements

### Viewing Logs

Clients can view recent file operations through:
- The GUI client's "View Logs" button
- Direct SQLite database access using any SQLite browser
- The server's console output and log files

## Extensibility

The system is designed for easy extension with:

- Singleton pattern for key components
- Separation of concerns between modules
- Thread pooling for scalability
- Configuration externalization

## Error Handling

The system implements robust error handling with:

- Checksum verification for file integrity
- Connection management with graceful failures
- Comprehensive exception logging
- User-friendly error messages

## License

[Include your license information here]

## Contributors

[Include contributor information here]