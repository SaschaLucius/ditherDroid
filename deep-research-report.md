# Executive Summary  
Phomemo’s Bluetooth thermal printers (notably the M-series mini and label makers) use essentially standard thermal-printing protocols under the hood, not a secret proprietary scheme. Most M02/M02S/M02 Pro units speak *EPSON ESC/POS* over a serial link (Bluetooth classic SPP or USB), while larger label printers (e.g. PM-241 shipping labels) use the TSPL-II language over USB.  Open-source projects have reverse‑engineered these protocols: for example, **phomemo-tools** (Python, GPL3) sniffed the Android app and found that M02-series models use ESC/POS commands. In practice this means sending bytes like `ESC @` (0x1B 0x40) to init the printer, `ESC a <n>` for justification, then `GS v 0` (0x1D 0x76 0x30) raster-image blocks with width/height, and finally `ESC d <n>` to feed paper. Other community libraries confirm this: e.g. a Python module **phomemo_printer** lets you do `printer.print_text("Hello")` over Bluetooth SPP. By contrast, Phomemo’s WiFi/USB label printers (PM-241 series) use TSC/TSPL commands (text-based commands like `SIZE`, `GAP`, `PRINT`) over a USB-CDC or serial port. No encryption or authentication beyond Bluetooth pairing is used, so all traffic can be captured with standard tools (Linux *btmon*, Android HCI snoop, etc.).

Open-source code is already available in multiple languages. Notable examples include a Python CUPS-driver (**phomemo-tools**) and **phomemo_printer** (Python, GPL2) for classic models, a CLI NodeJS script **cli-phomemo-printer** for M02S, and a community web app **Phomymo/MyPhomemo** (JavaScript, MIT) which supports dozens of Phomemo models via WebBluetooth/WebUSB. There is also **@thermal-label/labelife** (TypeScript, MIT) which abstracts ~95 label/printer models (including many Phomemo) and handles either ESC/POS or TSPL modes. 

For mobile development, the approach will depend on the model and platform. On Android, Bluetooth Classic SPP is straightforward: after pairing, open an RFCOMM socket (UUID 00001101-0000-1000-8000-00805F9B34FB) and write ESC/POS bytes to it. You need `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` permissions (Android 12+) or `BLUETOOTH_ADMIN` (pre-12), and ensure location permission if scanning. On iOS, Classic SPP is not supported without MFi, so only BLE-capable models can work with CoreBluetooth (connecting to a custom GATT service/characteristic). In practice, most Phomemo printers are Classic-only, so iOS development may require using the manufacturer’s SDK/Accessory framework or limiting to USB/Wi-Fi models.  

This report details each transport and protocol (BLE GATT vs SPP vs USB serial), key command examples (text, image, barcode), known GATT services/UUIDs, status queries, and available open-source libraries. We also outline how to reverse-engineer the Android APK or capture Bluetooth traffic, and give a sample implementation plan. Finally, we discuss security/privacy considerations (pairing but no crypto, risk of sniffing) and remaining unknowns (e.g. exact BLE profiles, undocumented commands). The “Phomemo-tools” reverse-engineering is a primary source, as are community projects (Phomymo, labelife). 

## Printer Models & Communication Overview  
Phomemo’s products fall into families: **M02-series mini printers** (M02, M02S, M02 Pro, M02 Mini, M02S Plus, etc.) are 48–53 mm thermal units for stickers/receipts; **M110/M120/M220 label makers** are narrow-label devices (typically 48–80 mm wide); **D-/Q-series smart label makers** are very narrow (12–24 mm) special “rotated” protocol; and **PM-series shipping label printers** (e.g. PM-241, PM-344) are 4″ machines (USB or Wi-Fi with TSPL). Unifying these, they generally print by receiving raster or text commands over a serial-like interface.  

- **Bluetooth Classic (SPP):** Almost all mini/label models use classic Bluetooth SPP. The printer appears in the OS paired-devices list; pairing is done via OS Bluetooth settings or in-app. After pairing, a virtual COM port (e.g. `/dev/rfcomm0` on Linux/Android, or `COMx` on Windows) is available. The official apps (Android/iOS) speak SPP under the hood (or use BLE on newer Wi-Fi models). On Android, you create a `BluetoothSocket` to the SPP service (UUID `00001101-0000-1000-8000-00805F9B34FB`) and read/write bytes. On Windows/Mac/Linux you can also open the paired port.  

- **Bluetooth LE (GATT):** Few Phomemo models are BLE-only. Some Wi-Fi-capable label printers (e.g. “-WF” models) advertise a BLE GATT service 0xFF00 for provisioning or print commands. For example, labelife notes TSPL models use a BLE service `0xFF00` with write/read characteristics (0xFF01/0xFF02). In practice, WebBluetooth or CoreBluetooth can connect to that GATT service and write data. However, most M02-series units do *not* use BLE: an issue report notes PM-241 (Bluetooth model) is Classic-only, not accessible via WebBluetooth. Reverse-engineering would require scanning for the advertised GATT UUIDs on BLE models (tools: Android “Bluetooth HCI snoop” or Nordic nRF Connect).  

- **USB/Serial:** Many printers (especially PM-241/PM-344 shipping labels) expose a USB port. Linux sees them as e.g. Nuvoton VID=0493,PID=b002 creating `/dev/ttyACM0` or `/dev/usblp0`. Windows installs a CDC or custom driver. For Android with USB-OTG, one could use UsbManager/UsbDeviceConnection to open the serial interface. The connected app would send ESC/POS or TSPL commands over the USB serial. The Phomemo drivers page lists USB support (“PC driver” downloads), implying this path is intended for desktop use.  

The table below summarizes open-source projects found, with language, supported models, transport, license, maturity, and links.  

| Project/Library                     | Language | Models (examples)        | Transport              | License | Maturity/Notes                                     | Link                                                                       |
|-------------------------------------|----------|--------------------------|------------------------|---------|----------------------------------------------------|----------------------------------------------------------------------------|
| **phomemo-tools** (vivier)          | Python   | M02, M02 Pro, M02S, M110, M120, M220, T02 | BT SPP, USB (CDC)      | GPLv3  | Well-tested (≥440★); reverse-engineered ESC/POS | [GitHub](https://github.com/vivier/phomemo-tools) |
| **phomemo_printer** (hkeward)       | Python   | M02, M02 Pro (tested)    | BT SPP                 | GPLv2   | Basic (41★); prints images/text as raster image    | [GitHub](https://github.com/hkeward/phomemo_printer)         |
| **phomemo_m02s** (theacodes)        | Python   | M02S (tested)            | BT SPP                 | MIT     | Focused on M02S; image printing via serial         | [GitHub](https://github.com/theacodes/phomemo_m02s)                        |
| **cli-phomemo-printer** (vrk)       | Node.js  | M02S (tested)            | BT SPP                 | MIT     | Command-line image printing (22★)                  | [GitHub](https://github.com/vrk/cli-phomemo-printer)                       |
| **Phomymo / MyPhomemo** (community) | JavaScript (Web) | ~M02, M02S, M02X, M03, M04S, M110, M200, M220, D30, PM-241, etc. | BT SPP (via WebBluetooth/WebSerial), USB | MIT     | Web label designer with broad model support | [GitHub](https://github.com/transcriptionstream/phomymo)                    |
| **@thermal-label/labelife**        | TypeScript/JS | ~95 label printers (Phomemo, Munbyn, etc.) | BT SPP, BLE GATT, TCP, USB | MIT     | Actively developed, Node/Browser SDK       | [GitHub](https://github.com/thermal-label/labelife)                         |
| **niimblue** (multimote/niimblue)   | Node.js  | Generic BLE/USB         | BT SPP/BLE             | MIT     | Under development; some community interest         | [Issue](https://github.com/MultiMote/niimblue/issues/67)                    |

## Bluetooth (Discovery & Pairing)  
**Android (Classic):** Use the `BluetoothAdapter` to scan or fetch bonded devices by name. Pair the device (if not already) either via system settings or `BluetoothDevice.createBond()`. Then open an RFCOMM socket:  
```java
BluetoothDevice dev = bluetoothAdapter.getRemoteDevice("XX:XX:XX:XX:XX:XX");
bluetoothAdapter.cancelDiscovery();
BluetoothSocket sock = dev.createRfcommSocketToServiceRecord(
    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
sock.connect();
OutputStream out = sock.getOutputStream();
// Send ESC/POS commands by out.write(...)
```  
Be sure to request Bluetooth permissions (`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` on Android 12+) and add `<uses-permission>` for Bluetooth and optionally `ACCESS_FINE_LOCATION` (needed by some older APIs for discovery).  

**Android (BLE):** If the printer supports BLE, use `BluetoothLeScanner` or `BluetoothGatt` APIs. Scan for advertised name or service. According to labelife, TSPL-based printers use a custom service **0xFF00**. Use `BluetoothDevice.connectGatt()`, then discover the service 0xFF00; find the TX characteristic (e.g. 0xFF01) and write bytes to it. (Sniffing with Wireshark/HCI log can reveal exact UUIDs.)  

**iOS (Classic):** iOS does *not* allow arbitrary Classic Bluetooth SPP without MFi certification. Thus, non-MFi printers cannot be used via iPhone. If the model offers BLE, you could use CoreBluetooth: initialize `CBCentralManager`, scan for peripherals advertising known service (e.g. 0xFF00), then `connectPeripheral` and use `peripheral.writeValue:data:forCharacteristic:type:` on the found characteristic. However, many Phomemo apps imply use of BLE only for device discovery; printing often still goes over the classic channel, limiting iOS compatibility.  

**USB (Android):** On Android 10+, use the USB Host API. After getting `UsbManager` permission to the device (VID/PID from phomemo-tools: 0x0493:0xb002), open a `UsbDeviceConnection` and claim the CDC ACM interface. Then write ESC/POS data to the IN endpoint. This is similar to using `/dev/ttyACM0` on Linux. On PCs, Phomemo provides drivers or uses generic USB-COM drivers.  

**Pairing Flows:** In all cases, the app should guide the user to turn on the printer (hold power ~3s until LED), then pair via OS. For BLE, use in-app scanning (like Web Bluetooth’s chooser). For classic, the user may need to pair in system settings first. Unlike Wi-Fi, no network or passwords are needed beyond Bluetooth.  

## Transport & Protocol Details  
### ESC/POS (M02, M02 Pro, M02S, M03/T02, M110/M120/M220, etc.)  
Community work shows Phomemo’s mini and label printers speak ESC/POS.  For example, **phomemo-tools** found via packet sniffing that the M02 uses EPSON ESC/POS commands. Key commands include: `ESC @ (0x1B 0x40)` to initialize; `ESC a <n>` (0x1B 0x61) to set justification (0=left,1=center,2=right); and `ESC d <n>` to feed n lines. Text can be sent as raw bytes (often encoded in UTF-8 or code page 437) to the socket after initializing.  

Images are sent as bitmaps with the `GS v 0` command (0x1D 0x76 0x30).  The sequence is: `GS v 0 m xL xH yL yH [data]`, where **m**=0 for normal mode (also modes 1–3 for double/quad size), and `xL+xH*256` is the width in bytes (e.g. 48 bytes for 384px) and `yL+yH*256` is height in pixels.  Phomemo’s tools example shows `GS v 0 0 0x30 0x00 0x30 0x00` for a 48-byte-wide, 255-line block (255 is the max block; multi-block til finish). After the image data, `ESC d 0x02` feeds 2 lines. For example, sending a PNG through their helper resulted in the driver sending: 
```
ESC @                          ; initialize
ESC a 1                        ; center alignment
GS v 0 0 30 00 30 00 [image-data]  ; print 384×255px block
ESC d 2                        ; feed 2 lines
```  
The raster commands (GS v) allow printing images and barcodes (1D/2D codes use ESC/POS GS k or GS ( k sequences similarly).  ESC/POS also has status commands (e.g. `DLE EOT`) on many printers, but community docs for Phomemo show no special proprietary status bytes except the flow control above.  

**Label-size (DPI):** Phomemo mini printers use 203.2 DPI (80 dots/cm). For example, an M02 prints 384 dots ≈ 48 mm per line. The M02 Pro (300 DPI) uses 626 dots ≈ 53 mm.  The web app MyPhomemo auto-detects DPI/model from the BT name and sets the correct width (203 standard, 300 for M02 Pro).  

**D-series “rotated” printers:** The very narrow label printers (D30, D35, Q30, etc.) use a 90°-rotated protocol. The printer rotates output internally, so apps must rotate the image 90° before sending. The details differ per model and are handled in Phomymo/MyPhomemo (it “switches to D-series protocol and rotates image” automatically). In essence, these still accept ESC/POS-like raster commands but expect the height width swapped (and use special default label sizes).  

### TSPL-II (Shipping Label Printers)  
Phomemo’s 4″ label printers (PM-241/-344) use the **TSPL-II** language (by TSC). This is a text-based, command-and-response protocol (sent over USB or TCP). For example, one would send lines like:  
```
SIZE 102 mm, 152 mm  
GAP 3 mm, 0  
SPEED 4  
DENSITY 8  
DIRECTION 0  
SET TEAR ON  
CLS  
BARCODE 50,100,"128",100,1,0,2,2,"12345678"  
TEXT 50,200,"TSS24.BF2",0,1,1,"Hello World"  
BITMAP 0,0,"GRAPHIC.PNG",1  
PRINT 1  
```  
This draws graphics/barcodes/text on a 102×152 mm label. (These examples come from standard TSPL docs, as Phomemo’s own docs are limited.) Labelife’s hardware notes confirm TSPL support: e.g. PM-241 is TSPL, USB only. Wi-Fi models expose TSPL over TCP port 9100. In BLE mode (provisioning) they advertise 0xFF00 for binary TSPL transfer.  

Because TSPL is line-oriented and ASCII, one prints text by sending the appropriate command (no raster needed unless using BITMAP). The printer will ACK each command (surrounding with `~` frames on older models) or simply execute on receipt. In practice, using an existing TSPL library (many generic label SDKs) is easiest. The labelife library supports TSPL by framing “SSS<key>:<value>” status queries and bitmap modes as needed.  

## Sample Commands and Data Formats  

- **Initialize:** `ESC @` = `0x1B 0x40`. This resets the printer state.  
- **Alignment:** `ESC a <n>` = `0x1B 0x61 0x<00/01/02>` (0=left,1=center,2=right). Example: center text: `\x1B\x61\x01`.  
- **Feed Lines:** `ESC d <n>` = `0x1B 0x64 0x<n>`. For example `\x1B\x64\x02` advances 2 lines.  
- **Raster Image:** `GS v 0 m xL xH yL yH [bitmap]`. `GS`=0x1D, `v`=0x76, `0`=0x30. `m`=mode. Then 16-bit width and height. For example, print a 384×480 image (48 bytes×480 lines): send `1D 76 30 00 30 00 E0 01` followed by 48*480/8=2880 bytes of image data (each bit=dot). MyPhomemo generates 1-bit PNGs and writes these bytes with this header.  
- **Barcodes/QR:** ESC/POS commands (`GS k` etc) work as usual. For instance `GS k 4 7 8 0x31 ...` for Code128. Many projects simply render barcodes to an image and send as raster if ESC/POS support is uncertain.  
- **Status Query:** Standard ESC/POS status commands (DLE EOT etc.) can be sent, but Phomemo-tools suggests none beyond printing feedback. For example, `DLE EOT 1` requests paper status. However, label printers usually lack easy status feedback in their apps. Captured traffic shows only printing commands and some self-test.
- **Custom Vendor Ops:** Labelife notes an optional vendor “LZO compression” command: `0x1F 0x11 0x35 0x01` could tell the printer the next raster block is LZO-compressed. This is rarely needed unless optimizing for speed; Phomemo-tools did not mention using it, so images are usually sent uncompressed.  

**Byte-level example (ESC/POS print “Hi” and feed):**  
``` 
# (Python example over a Bluetooth socket)
sock.send(b"\x1B\x40")            # ESC @: initialize
sock.send("Hi, Phomemo!\n".encode())  # Print text (in UTF-8 or printer codepage)
sock.send(b"\x1B\x61\x01")         # ESC a 1: center align
sock.send(b"\x1B\x64\x02")         # ESC d 2: feed 2 lines
```
This will print “Hi, Phomemo!” centered, then advance paper 2 lines.  

**Raster example (Python):** Convert an image to 1-bit and send:  
```python
# Assume `data` is a bytearray of the 1-bit image, width=48 bytes, height=100 px
sock.send(b"\x1B\x40")            # init
sock.send(b"\x1D\x76\x30\x00")    # GS v 0, mode=0
sock.send(b"\x30\x00")            # xL=48 bytes (0x30 0x00)
sock.send(bytes([height & 0xFF, (height>>8)&0xFF]))  # yL,yH
sock.send(data)                   # image data (48 * 100 bits)
sock.send(b"\x1B\x64\x02")        # feed 2 lines
```  
(Phomemo-tools confirms each line is 48 bytes at 203 dpi.)  

### GATT UUIDs and BLE Characteristics  
There are no official public GATT specs for Phomemo BLE. Community sources suggest:  
- **TSPL models:** use service **0xFF00** (TI’s “Serial” service). Characteristic **0xFF01** (write) and **0xFF02** (notify/read) are typical. Labelife mentions `tspl-l3` and `tspl-m3` models accept TSPL over BLE GATT FF00.  
- **ESC/POS BLE:** Phomemo’s mini printers are usually Classic-BT only, but if any had BLE, one would expect a similar custom UART service (Nordic’s 0xFFE0/FFE1 or TI 0xFF00). The BLE sniffer approach: connect to whatever service the app uses for writing. (One could decompile the Android app for advertised UUIDs, or capture with `btmon`.)  
- **Flow:** For BLE, after connecting, one typically writes raw ESC/POS bytes to the TX characteristic. Labelife notes BLE transfers are *unencrypted*, so a simple `subscribe` on 0xFF02 would show any status data.  

## Existing Open-Source Implementations  

- **Python (Linux/Android):** *phomemo-tools* (GPL3) implements a CUPS filter for printing images via ESC/POS. It provides a `phomemo-filter.py` that converts PNGs to ESC/POS and writes to `/dev/rfcomm0` or `/dev/usb/lp0`. Usage example:  
  ```bash
  rfcomm bind /dev/rfcomm0 XX:XX:XX:XX:XX:XX 1   # bind BT SPP to /dev/rfcomm0  
  ./phomemo-filter.py mypic.png > /dev/rfcomm0  
  ```  
  It also can act as a CUPS backend. It discovered the ESC/POS patterns above.

- **Python (Bluetooth):** *phomemo_printer* (GPLv2) provides `Printer(bluetooth_address, channel)` to print text/images. Sample usage:  
  ```python
  from phomemo_printer.ESCPOS_printer import Printer
  printer = Printer(bluetooth_address="XX:XX:XX:XX:XX:XX", channel=6)
  printer.print_text("Hello, world\n")
  printer.close()
  ```  
  It works by rendering text to an image and sending it as one big bitmap (so text is printed as graphics).

- **Node.js:** *cli-phomemo-printer* (vrk, MIT) is a simple CLI that sends an image file to the printer over SPP (tested on M02S). It has arguments `-a` (address), `-c` (channel), `-i` (image). Example:  
  ```
  $ cli-phomemo-printer -a 00:11:22:33:44:55 -c 6 -i label.png
  ```

- **Web (JavaScript):** *Phomymo/MyPhomemo* (MIT) is a browser app (Chrome-only, using WebBluetooth/WebUSB) that supports many Phomemo models. It auto-detects the BT name to pick the protocol (M02 vs M03 vs D-series vs TSPL). For M02-series it sends ESC/POS (via WebBluetooth GATT writes on BLE or Web Serial on SPP), for D-series it rotates images, and for PM-241 it switches to USB+TSPL mode. This is a high-quality, actively maintained project (94★) – one can inspect its source under `src/web/` to see exact GATT UUIDs and command generation.  

- **Node/TypeScript SDK:** *@thermal-label/labelife* (MIT) is a TypeScript library (Node/browser) that abstracts printing for dozens of label printers (Phomemo, Munbyn, etc.). It splits devices into two “engines”: **tspl-c1/l3/m3** and **escpos-a/y0/p1**. The ESC/POS engine will automatically do `ESC @`→`GS v 0`, etc., and supports optional LZO compression. Example (Node):  
  ```ts
  import { LabelifeDiscovery, MEDIA } from '@thermal-label/labelife-node';
  const discovery = new LabelifeDiscovery();
  const printer = await discovery.openPrinter({
    serialPath: '/dev/rfcomm0',
    deviceKey: 'D521_PRO'
  });
  await printer.print(myImageBuffer, MEDIA.DIE_CUT_50X30);
  await printer.close();
  ```  
  . It also handles BLE and TCP by selecting the right transport. For BLE printers (like `-WF` models) it can do `await requestPrinterBluetooth('PM_344_WF')` in browser and send the print job.

## Reverse Engineering Methods  
To reverse-engineer unknown printers or protocols, one can:  
- **Android APK decompilation:** Download the official Phomemo app (or Labelife) APK and use tools like JADX or `apktool` to inspect its code. Look for strings like UUIDs or service names. For example, search for “service” or “FF00” in the decompiled code. ProGuard obfuscation may make this hard but often print routines are obvious (e.g. calls to `write()` with byte arrays).  
- **Bluetooth Sniffing:** On Android, enable “Bluetooth HCI snoop log” in developer settings; it saves raw BT packets, which you can open in Wireshark with the Bluetooth plugin. On Linux, use `btmon` during printing. Capture the connect/notify handles and look for sequences starting 0x1B or 0x1D (indicating ESC/POS) or ASCII commands (“SIZE” for TSPL). This is how **phomemo-tools** recovered the protocol. Sniffing a BLE-capable unit will show its advertised services/UUIDs. (Labelife notes SPP/BLE are unencrypted, so this is straightforward.)  
- **USB Capture:** On Linux, `dmesg` shows `/dev/ttyACM0` for USB. Use a serial monitor (e.g. `sudo cat /dev/ttyACM0`) to capture raw TSPL or ESC/POS data when printing a label. On Windows, use a USB sniffer tool (e.g. Wireshark with USBPcap). The Phomemo-tools author did this to decode the M110/M120 USB commands.  

## Implementation Plan (Android/iOS)  

**Android:** Use Java/Kotlin. 1) In `AndroidManifest.xml`, request `BLUETOOTH`, `BLUETOOTH_ADMIN` (or `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` on Android 12+). If scanning, request `ACCESS_FINE_LOCATION` (for old APIs). 2) At runtime, discover the printer (either require the user to pair first, or use `startDiscovery()` and listen for the known device name or address). 3) Connect to the printer: if Classic, create an `BluetoothSocket` to SPP. If BLE, use `BluetoothGatt`. 4) Once connected, write commands as byte arrays. For ESC/POS, writing to the socket’s OutputStream suffices. 5) Handle errors (IOException if device disconnects) and close gracefully.  

For images/barcodes, either render to an image and convert to raster (as phomemo-tools does), or use an existing ESC/POS library (e.g. [EscPosPrinter library](https://github.com/DantSu/ESCPOS-ThermalPrinter-Android) with caution). There is no need for pairing PIN/password with Phomemo – usually just “OK” unless adapter needs it.  

**iOS:** Limited by OS Bluetooth rules. If the printer is BLE-enabled (advertises GATT services), use CoreBluetooth: scan for `CBPeripheral`, connect, then use `-[CBPeripheral writeValue:forCharacteristic:type:]` to send the ESC/POS or TSPL bytes. You must include the Services and Characteristics in your `Info.plist` (`UIRequiredDeviceCapabilities` or static “bluetooth-le” usage description). If the printer is Classic-only, you cannot connect from iOS (unless it supports ExternalAccessory with MFi). The official approach on iOS is to use the “Phomemo” app. For a custom iOS app, focus on BLE-capable models or USB-C / Wi-Fi printers that have an iOS API.  

**USB (Android):** If targeting USB (e.g. PM-241), use `UsbManager` to find the device VID/PID (via `usbManager.getDeviceList()`), request permission with a `PendingIntent`, then open a `UsbDeviceConnection`. Claim the interface (likely a CDC ACM interface), find the bulk-in/out endpoints, and use `connection.bulkTransfer()` to send data. This replicates a serial link. On iOS there is no USB host for general devices, so skip.  

**Labelife & Other Libraries:** For faster development, consider integrating or porting labelife’s encoder logic (written in TS) into Android (e.g. via J2V8 or a native rewrite) to handle image formatting and command framing automatically. On iOS, some thermal printer SDKs exist but Phomemo’s are proprietary.  

## Security & Privacy Considerations  
Phomemo printers use *no cryptographic security*. Pairing is nominally required, but once paired, the connection is unencrypted (no BLE encryption, no PIN exchange beyond Bluetooth pairing). All print data can be captured by a nearby snooping device. Sensitive data (text/images) printed could be intercepted, so avoid sending personal/private info if an attacker might be listening. 

Ensure your app respects user privacy: it will request Bluetooth permissions and possibly location (for scanning). Explain in-app why this is needed (e.g. to connect to the printer). Because many label printers advertise Bluetooth names, sometimes location permission is needed even to just list bonded devices on newer Android. There is no user data in the printer itself beyond printed content.  

There is no known authentication mechanism in the printers, so any device that pairs can print or query status. Protect against accidental prints by confirming “Are you printing to the correct device?” in the UI. Optionally, the app could implement a simple challenge-response by expecting a known printer response (like the serial number sequence in) and verifying it, but this is not in the protocol per se.

## Gaps, Unknowns & Next Steps  
- **BLE Support Details:** It remains unclear which Phomemo models have BLE. Community sources imply only new Wi-Fi models (PM-344-WF etc.) use BLE 0xFF00. If BLE printing is critical (e.g. for iOS), you may need to purchase a BLE-enabled model and sniff it.  
- **Complete Command Reference:** Official manuals are scarce. The ESC/POS subset is known from phomemo-tools, but not exhaustively documented. TSPL commands are standard, but details like `SET PEEL ON/OFF` etc. should be tested. We lacked explicit error/status codes; if needed, try standard ESC/POS DLE/POLL commands or TSPL `~HS` status frames to check paper-end or battery.  
- **Android/iOS SDKs:** Phomemo does not publish an SDK (outside their app). The Labelife app on phones suggests non-public APIs. Using community libraries (or this research) is the best approach. Contribute any findings (e.g. discovered GATT UUIDs) back to open source if possible.  

**Recommended Next Steps:** Test with actual hardware. Build a small demo: pair an M02-series unit to an Android device, connect via BluetoothSocket, and send `"\x1B\x40Hello\x0A\x1B\x64\x03"` – see if it prints “Hello”. If yes, proceed to implement image printing by encoding a sample PNG to ESC/POS. For USB, plug in a PM-241 to a laptop and try sending TSPL commands via a terminal (e.g. using `echo -e "SIZE 102 mm,152 mm\r\n...` > /dev/ttyACM0`). Capturing this traffic will further validate the protocol assumptions.  

**Diagram (Communication Flow):** Below is a flowchart summarizing connection paths. (Box “App” represents your code; “Printer” is any Phomemo device.)  
```mermaid
flowchart LR
    A[App (Android/iOS/Desktop)] 
    B[Phomemo Printer] 
    A -- Bluetooth SPP --> B
    A -- BLE (GATT 0xFF00) --> B
    A -- USB Serial (CDC) --> B

    subgraph Commands
    AText("Text/Image → ESC/POS or TSPL bytes")
    end
    AText --- A

    subgraph Layers
    AApps[App (UI, SDK, Permissions)]
    BDrv[Printer firmware]
    end
    A -->|Send bytes| B
```

  
**References:** We have cited the primary community resources above. The phomemo-tools README is the most detailed protocol source.  The labelife docs provide high-level insight into TSPL vs ESC/POS.  The projects’ homepages/READMEs were used to verify model support and usage examples. All links are provided inline.