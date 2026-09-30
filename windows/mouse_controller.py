"""
Windows mouse controller using pynput.

Handles all mouse operations dispatched by the MagicMouse receiver:
  - Relative cursor movement (motion packets)
  - Left/right/middle click (single and double)
  - Mouse button press/release (for drag-and-drop)
  - Vertical and horizontal scrolling

Why pynput?
  - Pure Python, no C extensions needed for basic use
  - Wraps Win32 SendInput on Windows (the right API for synthetic input)
  - Simple, well-documented API
  - Works with all modern Windows applications

Install: pip install pynput
"""
from pynput.mouse import Button, Controller

from protocol import (
    BTN_LEFT, BTN_RIGHT, BTN_MIDDLE,
    CLICK_NONE, CLICK_SINGLE, CLICK_DOUBLE, CLICK_PRESS, CLICK_RELEASE,
)


class MouseController:
    """
    Wraps pynput.mouse.Controller to handle all MagicMouse commands.
    Thread-safe: pynput's Controller is safe to call from any thread.
    """

    def __init__(self):
        self._mouse = Controller()
        self._left_pressed  = False
        self._right_pressed = False

    # ── Motion ────────────────────────────────────────────────────────────────

    def move(self, dx: int, dy: int) -> None:
        """Move cursor by (dx, dy) pixels relative to current position."""
        if dx != 0 or dy != 0:
            self._mouse.move(dx, dy)

    # ── Clicks ────────────────────────────────────────────────────────────────

    def _button_for(self, button_state: int) -> Button:
        """Resolve which pynput Button to use from button_state flags."""
        if button_state & BTN_RIGHT:
            return Button.right
        if button_state & BTN_MIDDLE:
            return Button.middle
        return Button.left  # default

    def handle_click(self, click_type: int, button_state: int) -> None:
        """Dispatch the appropriate click event."""
        if click_type == CLICK_NONE:
            return

        btn = self._button_for(button_state)

        if click_type == CLICK_SINGLE:
            self._mouse.click(btn, count=1)

        elif click_type == CLICK_DOUBLE:
            self._mouse.click(btn, count=2)

        elif click_type == CLICK_PRESS:
            self._mouse.press(btn)
            if btn == Button.left:
                self._left_pressed = True
            elif btn == Button.right:
                self._right_pressed = True

        elif click_type == CLICK_RELEASE:
            self._mouse.release(btn)
            if btn == Button.left:
                self._left_pressed = False
            elif btn == Button.right:
                self._right_pressed = False

    # ── Scroll ────────────────────────────────────────────────────────────────

    def scroll(self, scroll_dy: float, scroll_dx: float = 0.0) -> None:
        """
        Scroll the mouse wheel.

        pynput.scroll(dx, dy):
          dx = horizontal scroll (positive = right)
          dy = vertical scroll   (positive = up)

        The Android protocol uses positive scroll_dy = DOWN (like CSS),
        so we invert: pynput_dy = -scroll_dy
        """
        px = int(scroll_dx)
        py = int(-scroll_dy)
        if px != 0 or py != 0:
            self._mouse.scroll(px, py)

    # ── Cleanup ───────────────────────────────────────────────────────────────

    def release_all(self) -> None:
        """Release any held buttons — called on disconnect to avoid stuck buttons."""
        if self._left_pressed:
            try:
                self._mouse.release(Button.left)
            except Exception:
                pass
            self._left_pressed = False
        if self._right_pressed:
            try:
                self._mouse.release(Button.right)
            except Exception:
                pass
            self._right_pressed = False
