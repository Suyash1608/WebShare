# 🚀 WebShare -- Secure Local File Sharing over HTTPS

WebShare is a **Java-based local file sharing application** that enables
secure and seamless file transfer between devices on the same network
using a web browser.

It works as a **cross-platform alternative to AirDrop / ShareIt**,
focusing on **security, performance, and reliability**.

---

## 📥 Download Application (Windows)

👉 **Direct Download:**  
https://drive.google.com/uc?export=download&id=1h4XK_uFIvxyrYcq2Pm9n01xnvgs0O_my  

⚠️ **Note:**
- Windows only  
- No Java installation required  

---

## ▶️ How to Use (For Users)

1. Download the application  
2. Extract the ZIP file  
3. Run the `.exe` file  

💻 Once the app starts:
- Click **Start Server**  
- Copy the generated HTTPS URL  

📱 On another device (same Wi-Fi):
- Open the URL in a browser  
- Enter the access key  

📂 Upload and download files easily  

---

## ✨ Features

- 🔐 Session-based authentication with access key  
- 🔒 HTTPS support using self-signed certificates  
- 📤 Chunked file upload with resume support  
- 📥 Efficient file downloads with range requests  
- ⚙️ Temporary storage & upload management  
- 🔍 SHA-256 checksum for file integrity  
- 🛡️ Security features (CSP, XSS protection, sanitization)  

---

## 🛠️ Tech Stack

- Java  
- JavaFX (Desktop UI)  
- Undertow  
- TLS/SSL  
- REST APIs  
- Concurrent Programming  

---

## 🧠 How It Works

1. Start the server from the desktop app  
2. Open the generated HTTPS URL on another device  
3. Enter the access key  
4. Upload/download files via browser  

---

## 👨‍💻 Run from Source (For Developers)

### 📋 Prerequisites

- Java 17 or higher  
- Maven installed  
- JavaFX SDK (version 21)

👉 Download JavaFX SDK: https://gluonhq.com/products/javafx/  
👉 Extract it (example: C:\javafx-sdk-21.0.10)

---

### ▶️ Steps

1. Clone the repository  
git clone https://github.com/Suyash1608/WebShare.git  
cd WebShare  

2. Build the project  
mvn clean package  

3. Run the application  

👉 Windows:  
java --module-path "C:\javafx-sdk-21.0.10\lib" --add-modules javafx.controls,javafx.fxml -jar target/WebShare-0.0.1-SNAPSHOT.jar  

👉 Mac/Linux:  
java --module-path /path/to/javafx-sdk-21.0.10/lib --add-modules javafx.controls,javafx.fxml -jar target/WebShare-0.0.1-SNAPSHOT.jar  

---

## ⚠️ Notes

- Both devices must be on the same Wi-Fi network  
- Allow firewall access if prompted  
- Browser may show HTTPS warning (self-signed certificate)  

---

## 📂 Project Structure

WebShare/  
│── src/        # Source code  
│── .tmp/       # Temporary upload storage  
│── cert/       # Certificates  
│── README.md  

---

## 📸 Screenshots / Demo

_Add screenshots or screen recordings here_

---

## 💡 Learnings

- Low-level HTTP handling  
- Secure backend design  
- File transfer optimization  
- Handling large files efficiently  
- Concurrency in real-world systems  

---

## 🚧 Future Improvements

- Better UI/UX  
- QR code for quick connection  
- Drag & drop upload  
- Mobile responsiveness  
- Performance optimizations  

---

## 🤝 Contributing

Feel free to fork and contribute to this project.

---

## 👨‍💻 Author

**Suyash Gupta**

---

## ⭐ Support

If you like this project, consider giving it a ⭐ on GitHub!
