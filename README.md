# 🚀 WebShare — Secure Local File Sharing over HTTPS

A high-performance **Java-based local file sharing application** that enables secure and seamless file transfer between devices on the same network using a web browser. It works as a cross-platform alternative to AirDrop / ShareIt, focusing on **security, performance, and reliability**.

-----

## 🛠️ Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 17+ |
| Desktop UI | JavaFX 21 |
| Web Server | Undertow (High-performance NIO) |
| Security | TLS/SSL + SHA-256 Checksum |
| Build Tool | Maven |
| Networking | REST APIs & Concurrent Programming |

-----

## 📁 Project Structure

```
WebShare/
├── src/main/java/com/suyash/webshare/
│   ├── controller/      # JavaFX UI logic
│   ├── server/          # Undertow server core
│   ├── handlers/        # API handlers (Upload/Download)
│   ├── security/        # SSL/TLS & Access Key management
│   └── util/            # Hashing & file sanitization
├── cert/                # SSL Certificates
├── .tmp/                # Temporary upload storage
└── README.md
```

-----

## ⚙️ Setup & Run

### Prerequisites

  - Java 17+
  - Maven 3.6+
  - JavaFX SDK 21

### Steps

1.  **Clone the repository**

    ```bash
    git clone https://github.com/Suyash1608/WebShare.git
    cd WebShare
    ```

2.  **Build the project**

    ```bash
    mvn clean package
    ```

3.  **Run the application**

    **Windows:**

    ```bash
    java --module-path "C:\path\to\javafx-sdk-21\lib" --add-modules javafx.controls,javafx.fxml -jar target/WebShare-0.0.1-SNAPSHOT.jar
    ```

-----

## 🔐 Security

This application implements multiple layers of security for local transfers:

1.  **HTTPS Encryption:** Uses TLS/SSL to prevent packet sniffing on shared Wi-Fi networks.
2.  **Access Key:** Session-based authentication requiring a generated key for browser access.
3.  **Integrity:** Automatic **SHA-256 checksum** verification for all transferred files.

-----

## 📡 API Endpoints

### File Operations

| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/files` | Auth | List all shared files |
| POST | `/api/upload` | Auth | Chunked file upload with resume support |
| GET | `/api/download/{id}` | Auth | Download file with Range Request support |
| DELETE | `/api/files/{id}` | Auth | Remove file from server |

-----

## 📥 Download (Windows)

👉 **Direct Download:** [Click Here](https://www.google.com/search?q=https://drive.google.com/uc%3Fexport%3Ddownload%26id%3D1h4XK_uFIvxyrYcq2Pm9n01xnvgs0O_my)

*Note: The standalone version requires no Java installation.*

-----

## ✅ Key Features

  - **Cross-Platform:** Transfer between Android, iOS, Windows, Mac, and Linux via browser.
  - **Chunked Uploads:** Efficiently handles large files by splitting data into chunks.
  - **Resumable Downloads:** Supports HTTP Range requests to resume interrupted transfers.
  - **Zero Cloud Reliance:** Files never leave your local network, ensuring 100% privacy.
  - **Responsive Web UI:** Clean, mobile-friendly interface for the receiving device.

-----

## 👤 Author

**Suyash Gupta** — Java Backend Developer  
[LinkedIn](https://www.google.com/search?q=https://linkedin.com/in/suyash-16d08m/) | [GitHub](https://www.google.com/search?q=https://github.com/Suyash1608)
