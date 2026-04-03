# 🚀 WebShare -- Secure Local File Sharing over HTTPS

WebShare is a **Java-based local file sharing server** that enables
secure and seamless file transfer between devices on the same network
using a web browser.

It works as a **cross-platform alternative to AirDrop / ShareIt**,
focusing on **security, performance, and reliability**.

------------------------------------------------------------------------

## ✨ Features

-   🔐 Session-based authentication with access key\
-   🔒 HTTPS support using self-signed certificates\
-   📤 Chunked file upload with resume support\
-   📥 Efficient file downloads with range requests\
-   ⚙️ Temporary storage & upload management\
-   🔍 SHA-256 checksum for file integrity\
-   🛡️ Security features (CSP, XSS protection, sanitization)

------------------------------------------------------------------------

## 🛠️ Tech Stack

-   Java\
-   Undertow\
-   TLS/SSL\
-   REST APIs\
-   Concurrent Programming

------------------------------------------------------------------------

## 🧠 How It Works

1.  Start the server\
2.  Open the generated HTTPS URL on another device\
3.  Enter the access key\
4.  Upload/download files via browser

------------------------------------------------------------------------

## 📂 Project Structure

WebShare/ │── src/ \# Source code │── .tmp/ \# Temporary upload storage
│── cert/ \# Certificates │── README.md

------------------------------------------------------------------------

## 🚀 Getting Started

### 📋 Prerequisites

-   Java 8 or higher

### ▶️ Run the Project

git clone https://github.com/Suyash1608/WebShare.git cd WebShare javac
\*.java java Main

------------------------------------------------------------------------

## 📸 Screenshots / Demo

Add screenshots or screen recording here for better visibility.

------------------------------------------------------------------------

## 💡 Learnings

-   Low-level HTTP handling\
-   Secure backend design\
-   File transfer optimization\
-   Handling large files efficiently\
-   Concurrency in real-world systems

------------------------------------------------------------------------

## 🚧 Future Improvements

-   Better UI/UX\
-   QR code for quick connection\
-   Improved mobile responsiveness\
-   Drag & drop upload\
-   Performance optimizations

------------------------------------------------------------------------

## 🤝 Contributing

Feel free to fork and contribute to this project.

------------------------------------------------------------------------

## 📌 Note

🚧 This project is actively being improved with new features and
optimizations.

------------------------------------------------------------------------

## 👨‍💻 Author

Suyash Gupta

------------------------------------------------------------------------

## ⭐ Support

If you like this project, consider giving it a ⭐
