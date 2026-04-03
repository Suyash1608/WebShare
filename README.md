# 🚀 WebShare – Secure Local File Sharing over HTTPS

WebShare is a **Java-based local file sharing server** that allows seamless and secure file transfer between devices on the same network using a web browser.

It is designed to be a **cross-platform alternative to AirDrop / ShareIt**, with a strong focus on **security, performance, and reliability**.

---

## ✨ Features

### 🔐 Secure Authentication
- Session-based login using access key
- Protection against unauthorized access
- Rate limiting for security

### 🔒 HTTPS Support
- Runs on HTTPS using self-signed certificates
- Certificate download support for:
  - Android
  - iOS
  - Windows

### 📤 Advanced File Upload
- Chunked file upload for large files
- Resume support for interrupted uploads
- Concurrent upload handling

### 📥 Efficient File Download
- Supports resume (Range requests)
- Optimized streaming using FileChannel

### ⚙️ Backend Capabilities
- Temporary storage management
- Upload progress tracking
- SHA-256 checksum for file integrity

### 🛡️ Security Features
- Content Security Policy (CSP)
- XSS protection
- Path traversal prevention
- File validation & sanitization

---

## 🛠️ Tech Stack

- Java
- Undertow (Lightweight web server)
- TLS/SSL (HTTPS)
- REST APIs
- Concurrent Programming

---

## 🧠 How It Works

1. Start the server
2. Access the generated HTTPS URL on another device
3. Enter the access key
4. Upload/download files securely via browser

---

## 📸 Screenshots

_Add screenshots or demo GIF here_

---

## 🚀 Getting Started

### Prerequisites
- Java 8 or higher

### Run the Project

```bash
# Clone the repository
git clone https://github.com/Suyash1608/WebShare.git

# Navigate to project directory
cd WebShare

# Compile and run
# (Add your exact run command here)
