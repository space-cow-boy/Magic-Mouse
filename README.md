# MagicMouse 🖱️

> Turn your Android phone into a wireless Magic Mouse for Windows — using gyroscope, accelerometer, and touchscreen.

---

## How It Works

```
📱 Android Phone
  ├── Gyroscope → Complementary Filter → Cursor delta (dx, dy)
  ├── Accelerometer → Drift correction
  └── Touchscreen → Gesture Detector → Click / Scroll / Drag
          ↓  UDP packets (~100 Hz, ~10ms latency)  ↓
🖥️ Windows PC
  └── receiver.py → pynput → Windows Cursor
```

---

## Project Structure

```
MagicMouse/
├── android/          ← Android Studio project (Kotlin + Jetpack Compose)
│   └── app/src/main/java/com/magicmouse/android/
│       ├── sensor/
│       │   ├── SensorReader.kt      Raw gyro + accel via SensorManager
│       │   ├── SensorFusion.kt      Complementary filter (α=0.98)
│       │   └── MotionProcessor.kt   Dead zone + acceleration curve
│       ├── touch/
│       │   └── GestureDetector.kt   Tap, double-tap, drag, scroll state machine
│       ├── network/
│       │   ├── Protocol.kt          24-byte binary UDP packet format
│       │   └── UdpClient.kt         UDP sender
│       ├── controller/
│       │   └── MouseController.kt   Orchestrator
│       ├── MainViewModel.kt
│       ├── MainActivity.kt
│       └── ui/
│           ├── MainScreen.kt        Compose UI
│           └── theme/Theme.kt
│
└── windows/          ← Python receiver (run on your Windows PC)
    ├── receiver.py          Main UDP server + dispatch loop
    ├── protocol.py          Packet parser (mirrors Kotlin Protocol.kt)
    ├── mouse_controller.py  pynput mouse operations
    ├── smoother.py          EMA motion smoother + sub-pixel accumulator
    └── requirements.txt
```

---

## Quick Start

### Step 1 — Windows Receiver

**Prerequisites:** Python 3.12+

```powershell
cd windows
pip install -r requirements.txt
python receiver.py
```

You'll see:
```
====================================================
  🖱️  MagicMouse Windows Receiver
====================================================
  IP Address : 192.168.1.42        ← type this in the app
  Port       : 5555
  Resolution : 1920×1080
  Smoothing  : alpha=0.25
====================================================
```

Optional flags:
```powershell
python receiver.py --port 5555 --alpha 0.2 --verbose
```

| Flag | Default | Description |
|------|---------|-------------|
| `--port` | 5555 | UDP port |
| `--alpha` | 0.25 | EMA smoothing (0=raw, 0.5=smooth) |
| `--verbose` | off | Print every packet |

---

### Step 2 — Android App

1. Open `android/` in **Android Studio** (Electric Eel or newer)
2. Build and install on your phone
3. Make sure your phone and PC are on the **same Wi-Fi network**
4. Enter the IP address shown by the Windows receiver
5. Tap **Connect**
6. Point your phone, move it around, and tap the touch pad!

---

## Gestures

| Gesture | Action |
|---------|--------|
| Move/rotate phone | Move cursor |
| 1-finger tap | Left click |
| 1-finger double-tap | Double click |
| 1-finger hold + move | Click and drag |
| 2-finger tap | Right click |
| 2-finger swipe up/down | Vertical scroll |
| 2-finger swipe left/right | Horizontal scroll |
| Recenter button | Reset orientation reference |

---

## Sensor Pipeline

```
Gyroscope (100 Hz)  ──┐
                       ├─► Complementary Filter (α=0.98)
Accelerometer (50 Hz) ─┘        │
                                 ▼
                        Fused Orientation (pitch, yaw)
                                 │
                                 ▼
                        Frame-to-Frame Delta
                                 │
                                 ▼
                        Dead Zone (0.3°, +2× during touch)
                                 │
                                 ▼
                        Acceleration Curve
                        (non-linear: slow=precise, fast=big jump)
                                 │
                                 ▼
                        Cursor Δx, Δy (pixels)
                                 │
                            UDP (24 bytes)
                                 │
                                 ▼
                        EMA Smoother (α=0.25)
                        + Sub-pixel accumulator
                                 │
                                 ▼
                        pynput SendInput → Windows Cursor
```

---

## Tuning Parameters

### Android (`MotionProcessor.kt`)

| Parameter | Default | Effect |
|-----------|---------|--------|
| `deadZoneDegrees` | 0.3° | Lower = more responsive but jittery |
| `baseSensitivity` | 15 | Pixels per degree (slow movement) |
| `accelerationThreshold` | 2.0°/frame | Speed where acceleration kicks in |
| `accelerationFactor` | 2.5 | How much fast moves are amplified |
| `touchDeadZoneMultiplier` | 2.0 | Dead zone increase during touch |

### Windows (`receiver.py --alpha`)

| Alpha | Feel |
|-------|------|
| 0.0 | Raw — maximum responsiveness, may jitter |
| 0.2 | Recommended — smooth with minimal lag |
| 0.4 | Buttery smooth — noticeable lag on fast moves |

---

## Network Protocol

All packets are **24-byte binary, little-endian UDP datagrams**.

```
Offset  Type  Field
[0]     u8    packet_type  (0x01=motion, 0x02=click, 0x03=scroll, ...)
[1]     u8    sequence_num (0–255, wrapping)
[2-5]   f32   dx
[6-9]   f32   dy
[10]    u8    button_state (bit0=left, bit1=right, bit2=middle)
[11]    u8    click_type   (0=none,1=single,2=double,3=press,4=release)
[12-15] f32   scroll_dy
[16-19] f32   scroll_dx
[20-23] u32   timestamp_ms
```

---

## MVP Development Phases

- [x] **Phase 1** — Sensor test: live gyro/accel/orientation display
- [x] **Phase 2** — Communication: UDP client + Windows receiver
- [x] **Phase 3** — Cursor movement: motion → cursor delta pipeline
- [x] **Phase 4** — Stabilization: complementary filter, dead zone, sensitivity, recenter
- [x] **Phase 5** — Touch controls: tap, double-tap, drag, scroll gestures
- [ ] **Phase 6** — Polish: latency optimization, battery, UI refinements

---

## Troubleshooting

**Cursor is jittery**
→ Increase `--alpha` on the receiver (e.g., `--alpha 0.35`)
→ Increase `deadZoneDegrees` in MotionProcessor

**Cursor drifts when phone is stationary**
→ Press **Recenter** on the app
→ The complementary filter will gradually self-correct over ~30 seconds

**Can't connect**
→ Ensure phone and PC are on the same Wi-Fi network (not guest network)
→ Check Windows Firewall: allow Python on UDP port 5555
→ Try pinging the PC IP from the phone's browser

**Clicks are registering accidentally**
→ Increase `TAP_MAX_MS` threshold in GestureDetector (currently 200ms)
→ Increase `MOVE_THRESHOLD_PX` (currently 12px)

---

## Future Features

- [ ] Auto device discovery (mDNS/UDP broadcast)
- [ ] Three-finger gestures
- [ ] Presentation mode (forward/back slide navigation)
- [ ] Media controls mode
- [ ] Haptic feedback on click
- [ ] Multiple sensitivity profiles
- [ ] System tray icon for Windows receiver
- [ ] Gaming controller mode
