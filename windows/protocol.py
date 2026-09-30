"""
MagicMouse UDP Protocol Parser (Python)

Mirrors the Kotlin Protocol.kt on the Android side.
Both ends must stay in sync with this byte layout.

Packet layout (24 bytes, little-endian):
  [0]     u8   packet_type
  [1]     u8   sequence_num
  [2-5]   f32  dx
  [6-9]   f32  dy
  [10]    u8   button_state
  [11]    u8   click_type
  [12-15] f32  scroll_dy
  [16-19] f32  scroll_dx
  [20-23] u32  timestamp_ms
"""
import struct

PACKET_SIZE  = 24
DEFAULT_PORT = 5555
PACKET_FMT   = "<BBffBBffI"   # little-endian, matches layout above

# ── Packet types ──────────────────────────────────────────────────────────────
PTYPE_MOTION  = 0x01
PTYPE_CLICK   = 0x02
PTYPE_SCROLL  = 0x03
PTYPE_COMBO   = 0x04
PTYPE_HELLO   = 0x10
PTYPE_ACK     = 0x11
PTYPE_BYE     = 0x12
PTYPE_PING    = 0x13

# ── Button flags ──────────────────────────────────────────────────────────────
BTN_LEFT   = 0b001
BTN_RIGHT  = 0b010
BTN_MIDDLE = 0b100

# ── Click types ───────────────────────────────────────────────────────────────
CLICK_NONE         = 0
CLICK_SINGLE       = 1
CLICK_DOUBLE       = 2
CLICK_PRESS        = 3
CLICK_RELEASE      = 4


class MagicMousePacket:
    """Parsed MagicMouse packet."""
    __slots__ = (
        "packet_type", "seq", "dx", "dy",
        "button_state", "click_type",
        "scroll_dy", "scroll_dx", "timestamp_ms"
    )

    def __init__(self, packet_type, seq, dx, dy,
                 button_state, click_type,
                 scroll_dy, scroll_dx, timestamp_ms):
        self.packet_type  = packet_type
        self.seq          = seq
        self.dx           = dx
        self.dy           = dy
        self.button_state = button_state
        self.click_type   = click_type
        self.scroll_dy    = scroll_dy
        self.scroll_dx    = scroll_dx
        self.timestamp_ms = timestamp_ms

    @property
    def left_button(self)   -> bool: return bool(self.button_state & BTN_LEFT)
    @property
    def right_button(self)  -> bool: return bool(self.button_state & BTN_RIGHT)
    @property
    def middle_button(self) -> bool: return bool(self.button_state & BTN_MIDDLE)

    def __repr__(self):
        return (
            f"Packet(type=0x{self.packet_type:02x} seq={self.seq} "
            f"dx={self.dx:.2f} dy={self.dy:.2f} "
            f"click={self.click_type} scroll={self.scroll_dy:.2f})"
        )


def parse_packet(data: bytes) -> MagicMousePacket | None:
    """Parse raw UDP bytes into a MagicMousePacket. Returns None on error."""
    if len(data) < PACKET_SIZE:
        return None
    try:
        fields = struct.unpack_from(PACKET_FMT, data, 0)
        return MagicMousePacket(*fields)
    except struct.error:
        return None


def build_ack_packet(screen_width: int, screen_height: int) -> bytes:
    """Build an ACK packet sent back to Android on handshake."""
    # ACK: type=0x11, seq=0, dx=width, dy=height (reusing those fields for resolution)
    return struct.pack(PACKET_FMT,
                       PTYPE_ACK, 0,
                       float(screen_width), float(screen_height),
                       0, 0, 0.0, 0.0, 0)
