/*
  BinVerse ESP32 Command Receiver
  --------------------------------
  Proves the Phone -> ESP32 link only. Receives the structured JSON
  classification from the Android app over HTTP and prints it to the
  Serial Monitor. Motors/mechanism are NOT activated from this sketch —
  per the project's safety requirements, only the Arduino Mega (running
  its own obstacle/distance/bin-capacity/e-stop checks) is allowed to
  make the final physical-actuation decision. This sketch is a
  communication proof, and optionally a simple UART/Serial relay of
  the received command down to that Mega.

  Board: ESP32 Dev Module
  Library required: ArduinoJson (install via Library Manager)

  ---- MODE A: Join an existing Wi-Fi network ----
  Set WIFI_SSID / WIFI_PASSWORD below to your router's network, matching
  the phone's network. The ESP32 will print its assigned IP address to
  Serial on boot — enter that IP in the app's Settings screen.

  ---- MODE B: ESP32 as its own Access Point ----
  Set USE_ACCESS_POINT to true. The ESP32 creates "BINVERSE_ROBOT" and the
  phone connects to it directly. The ESP32's AP IP is always 192.168.4.1,
  which is this app's default ESP32 IP setting.
*/

#include <WiFi.h>
#include <WebServer.h>
#include <ArduinoJson.h>

// ---------------- Network mode ----------------
#define USE_ACCESS_POINT false

// Mode A settings
const char* WIFI_SSID = "YOUR_WIFI_SSID";
const char* WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";

// Mode B settings
const char* AP_SSID = "BINVERSE_ROBOT";
const char* AP_PASSWORD = "binverse123"; // WPA2 requires >= 8 chars

const uint16_t HTTP_PORT = 80;

WebServer server(HTTP_PORT);

// Optional: forward the raw command line to the Arduino Mega over Serial2
// (RX2/TX2). Wire ESP32 TX2 -> Mega RX. Disable if not wired up yet.
#define FORWARD_TO_MEGA false
#if FORWARD_TO_MEGA
  HardwareSerial MegaSerial(2); // UART2: RX=16, TX=17 on most ESP32 dev boards
#endif

void printBanner() {
  Serial.println();
  Serial.println("========================================");
  Serial.println("        BINVERSE ESP32 READY");
  Serial.println("========================================");
  if (USE_ACCESS_POINT) {
    Serial.print("Mode: Access Point  SSID: ");
    Serial.println(AP_SSID);
    Serial.print("IP address: ");
    Serial.println(WiFi.softAPIP());
  } else {
    Serial.print("Mode: Joined Wi-Fi  SSID: ");
    Serial.println(WIFI_SSID);
    Serial.print("IP address: ");
    Serial.println(WiFi.localIP());
  }
  Serial.print("Listening for POST ");
  Serial.print("http://");
  Serial.print(USE_ACCESS_POINT ? WiFi.softAPIP().toString() : WiFi.localIP().toString());
  Serial.println("/command");
  Serial.println("========================================");
}

void handleCommand() {
  if (server.method() != HTTP_POST) {
    server.send(405, "application/json", "{\"status\":\"error\",\"reason\":\"method not allowed\"}");
    return;
  }

  String body = server.arg("plain");

  StaticJsonDocument<256> doc;
  DeserializationError err = deserializeJson(doc, body);

  if (err) {
    Serial.println("BINVERSE COMMAND RECEIVED (MALFORMED)");
    Serial.print("Raw body: ");
    Serial.println(body);
    server.send(400, "application/json", "{\"status\":\"error\",\"reason\":\"invalid json\"}");
    return;
  }

  const char* objectType = doc["object_type"] | "unknown";
  double confidence = doc["confidence"] | 0.0;
  const char* action = doc["action"] | "WAIT";
  long timestamp = doc["timestamp"] | 0;

  Serial.println("BINVERSE COMMAND RECEIVED");
  Serial.print("Object: ");
  Serial.println(objectType);
  Serial.print("Confidence: ");
  Serial.println(confidence, 2);
  Serial.print("Action: ");
  Serial.println(action);
  Serial.print("Timestamp: ");
  Serial.println(timestamp);
  Serial.println("(No motor activation from this sketch — Arduino Mega has final say.)");
  Serial.println("----------------------------------------");

#if FORWARD_TO_MEGA
  // Simple line-based protocol; the Mega parses this and runs its own
  // obstacle/distance/bin-capacity/e-stop checks before doing anything.
  MegaSerial.printf("CMD,%s,%.2f,%s,%ld\n", objectType, confidence, action, timestamp);
#endif

  server.send(200, "application/json", "{\"status\":\"received\"}");
}

void handleNotFound() {
  server.send(404, "application/json", "{\"status\":\"error\",\"reason\":\"not found\"}");
}

void setup() {
  Serial.begin(115200);
  delay(300);

#if FORWARD_TO_MEGA
  MegaSerial.begin(9600, SERIAL_8N1, 16, 17);
#endif

  if (USE_ACCESS_POINT) {
    WiFi.mode(WIFI_AP);
    WiFi.softAP(AP_SSID, AP_PASSWORD);
  } else {
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    Serial.print("Connecting to Wi-Fi");
    int attempts = 0;
    while (WiFi.status() != WL_CONNECTED && attempts < 40) {
      delay(250);
      Serial.print(".");
      attempts++;
    }
    Serial.println();
    if (WiFi.status() != WL_CONNECTED) {
      Serial.println("WARNING: Wi-Fi connection failed. Check credentials and restart.");
    }
  }

  server.on("/command", HTTP_POST, handleCommand);
  server.onNotFound(handleNotFound);
  server.begin();

  printBanner();
}

void loop() {
  server.handleClient();
}
