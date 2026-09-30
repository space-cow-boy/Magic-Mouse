"""
MagicMouse Windows Receiver
============================
Main entry point for the Windows-side UDP server.

Usage:
    python receiver.py [--port 5555] [--alpha 0.25] [--verbose]

What it does:
  1. Opens a UDP socket on the given port
  2. Prints the local IP address (so you can type it into the Android app)
  3. Waits for HELLO from Android → responds with ACK + screen resolution
  4. Processes incoming motion/click/scroll packets at ~100 Hz
  5. Moves the Windows cursor via pynput

Architecture:
  - Single-threaded UDP receive loop (no threading overhead)
  - Non-blocking socket with a 1s timeout (for clean Ctrl+C shutdown)
  - All processing is in-line — no queues needed at this packet rate

Performance notes:
  - At 100 Hz, each loop iteration takes ~10ms
  - Python overhead per iteration: < 0.1ms (measured on i5 @ 3GHz)
  - The bottleneck is network latency, not Python speed
"""

import argparse
import socket
import sys
import time

import protocol as P
from mouse_controller import MouseController
from smoother import MotionSmoother

# ─────────────────────────────────────────────────────────────────────────────
#  Helper: Get local IP for display
# ─────────────────────────────────────────────────────────────────────────────

def get_local_ip() -> str:
    """Return the most likely LAN IP address of this machine."""
    try:
        # Connect to a public address (doesn't actually send data)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"


def get_screen_resolution() -> tuple[int, int]:
    """Get Windows primary monitor resolution via ctypes."""
    try:
        import ctypes
        user32 = ctypes.windll.user32
        return user32.GetSystemMetrics(0), user32.GetSystemMetrics(1)
    except Exception:
        return 1920, 1080   # Safe fallback


# ─────────────────────────────────────────────────────────────────────────────
#  Main receiver loop
# ─────────────────────────────────────────────────────────────────────────────

def run(port: int, alpha: float, verbose: bool):
    local_ip = get_local_ip()
    screen_w, screen_h = get_screen_resolution()
    ack_packet = P.build_ack_packet(screen_w, screen_h)

    mouse    = MouseController()
    smoother = MotionSmoother(alpha=alpha)

    # Stats
    packets_received  = 0
    packets_motion    = 0
    last_stats_time   = time.monotonic()
    connected_addr    = None
    last_seq          = -1

    print("=" * 52)
    print("  🖱️  MagicMouse Windows Receiver")
    print("=" * 52)
    print(f"  IP Address : {local_ip}")
    print(f"  Port       : {port}")
    print(f"  Resolution : {screen_w}×{screen_h}")
    print(f"  Smoothing  : alpha={alpha}")
    print()
    print("  Enter this IP in the Android app, then press Connect.")
    print("  Press Ctrl+C to stop.")
    print("=" * 52)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 65536)
    sock.bind(("0.0.0.0", port))
    sock.settimeout(1.0)   # 1s timeout so Ctrl+C is responsive

    try:
        while True:
            # ── Receive ───────────────────────────────────────────────────────
            try:
                data, addr = sock.recvfrom(P.PACKET_SIZE + 16)
            except socket.timeout:
                # Print stats every ~5 seconds when connected
                if connected_addr and time.monotonic() - last_stats_time > 5:
                    print(f"  ↗ {packets_received} pkts received, "
                          f"{packets_motion} motion | "
                          f"connected: {connected_addr[0]}")
                    last_stats_time = time.monotonic()
                continue

            pkt = P.parse_packet(data)
            if pkt is None:
                continue

            packets_received += 1

            # ── Handshake ─────────────────────────────────────────────────────
            if pkt.packet_type == P.PTYPE_HELLO:
                connected_addr = addr
                smoother.reset()
                sock.sendto(ack_packet, addr)
                print(f"\n  ✅ Connected: {addr[0]}:{addr[1]}")
                last_seq = -1
                continue

            if pkt.packet_type == P.PTYPE_BYE:
                mouse.release_all()
                smoother.reset()
                print(f"\n  ❌ Disconnected: {addr[0]}")
                connected_addr = None
                continue

            if pkt.packet_type == P.PTYPE_PING:
                sock.sendto(ack_packet, addr)
                continue

            # ── Guard: only accept packets from connected device ──────────────
            if connected_addr is None or addr != connected_addr:
                continue

            # ── Sequence number check (drop out-of-order) ─────────────────────
            # Note: seq wraps around at 255 → 0
            seq = pkt.seq
            if last_seq >= 0:
                expected = (last_seq + 1) & 0xFF
                if seq != expected:
                    # Packets may arrive out of order or be lost — just accept it
                    # For mouse control, out-of-order packets are fine (drop old ones)
                    if verbose:
                        print(f"  ⚠ Seq gap: expected {expected}, got {seq}")
            last_seq = seq

            # ── Dispatch by packet type ───────────────────────────────────────
            ptype = pkt.packet_type

            if ptype in (P.PTYPE_MOTION, P.PTYPE_COMBO):
                # Apply smoothing, then move
                dx, dy = smoother.smooth(pkt.dx, pkt.dy)
                mouse.move(dx, dy)
                packets_motion += 1

                if ptype == P.PTYPE_COMBO and pkt.click_type != P.CLICK_NONE:
                    mouse.handle_click(pkt.click_type, pkt.button_state)
                if ptype == P.PTYPE_COMBO and (pkt.scroll_dy != 0 or pkt.scroll_dx != 0):
                    mouse.scroll(pkt.scroll_dy, pkt.scroll_dx)

                if verbose and (packets_received % 50 == 0):
                    print(f"  motion dx={pkt.dx:+.1f} dy={pkt.dy:+.1f} "
                          f"→ px ({dx:+d}, {dy:+d})")

            elif ptype == P.PTYPE_CLICK:
                mouse.handle_click(pkt.click_type, pkt.button_state)
                if verbose:
                    btn = "LEFT" if pkt.left_button else ("RIGHT" if pkt.right_button else "MID")
                    ctype = {1:"click",2:"dbl-click",3:"press",4:"release"}.get(pkt.click_type,"?")
                    print(f"  {btn} {ctype}")

            elif ptype == P.PTYPE_SCROLL:
                mouse.scroll(pkt.scroll_dy, pkt.scroll_dx)
                if verbose:
                    print(f"  scroll dy={pkt.scroll_dy:+.2f} dx={pkt.scroll_dx:+.2f}")

    except KeyboardInterrupt:
        print("\n\n  Shutting down...")
        mouse.release_all()
    finally:
        sock.close()
        print("  Socket closed. Goodbye!")


# ─────────────────────────────────────────────────────────────────────────────
#  Entry point
# ─────────────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="MagicMouse Windows Receiver — turns your Android phone into a wireless mouse."
    )
    parser.add_argument(
        "--port", type=int, default=P.DEFAULT_PORT,
        help=f"UDP port to listen on (default: {P.DEFAULT_PORT})"
    )
    parser.add_argument(
        "--alpha", type=float, default=0.10,
        help="EMA smoothing factor 0.0–0.99 (default: 0.10). Higher = smoother but laggier."
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true",
        help="Print every received packet (noisy but useful for debugging)"
    )
    args = parser.parse_args()

    run(port=args.port, alpha=args.alpha, verbose=args.verbose)
