"""
Motion smoother for MagicMouse receiver.

Applies exponential moving average (EMA) to cursor deltas received from UDP
to absorb any jitter caused by:
  - Network timing variations (jitter in arrival times)
  - Sub-pixel sensor noise that slips through the Android dead zone
  - Any quantization artifacts from float serialization

Why EMA?
  - Simple: just one multiply + one add per axis
  - Very low latency: responds immediately to fast movements
  - Tunable: single parameter (alpha) controls smoothness vs. responsiveness
  - Zero lag at high alpha values

alpha = 0.0 → no smoothing (raw values passed through)
alpha = 0.3 → mild smoothing (recommended for mouse)
alpha = 0.7 → heavy smoothing (like a touchpad with high friction)
alpha = 1.0 → cursor never moves (infinite smoothing)
"""


class MotionSmoother:
    """
    Exponential Moving Average smoother for (dx, dy) cursor deltas.

    EMA formula:
        smoothed = alpha * prev_smoothed + (1 - alpha) * new_value

    We use a small position accumulator to handle sub-pixel deltas:
    At 100 Hz with a sensitivity of 15 px/deg and a 0.1° movement,
    the raw delta is 1.5 px — we need to accumulate fractional pixels
    before dispatching to SendInput, which operates in whole pixels.
    """

    def __init__(self, alpha: float = 0.25):
        """
        :param alpha: Smoothing factor. 0.0 = passthrough. 0.3 = gentle EMA.
        """
        self._alpha = alpha
        self._smoothed_dx = 0.0
        self._smoothed_dy = 0.0

        # Sub-pixel accumulator — accumulate fractional pixels, dispatch whole ones
        self._accum_x = 0.0
        self._accum_y = 0.0

    @property
    def alpha(self) -> float:
        return self._alpha

    @alpha.setter
    def alpha(self, value: float):
        self._alpha = max(0.0, min(value, 0.99))

    def smooth(self, raw_dx: float, raw_dy: float) -> tuple[int, int]:
        """
        Apply smoothing and return (integer_dx, integer_dy) to pass to SendInput.

        Returns (0, 0) if the accumulated value hasn't reached 1 pixel yet.
        """
        # EMA
        self._smoothed_dx = self._alpha * self._smoothed_dx + (1.0 - self._alpha) * raw_dx
        self._smoothed_dy = self._alpha * self._smoothed_dy + (1.0 - self._alpha) * raw_dy

        # Accumulate sub-pixel remainder
        self._accum_x += self._smoothed_dx
        self._accum_y += self._smoothed_dy

        # Extract whole pixels
        int_dx = int(self._accum_x)
        int_dy = int(self._accum_y)

        # Keep only fractional remainder
        self._accum_x -= int_dx
        self._accum_y -= int_dy

        return int_dx, int_dy

    def reset(self):
        self._smoothed_dx = 0.0
        self._smoothed_dy = 0.0
        self._accum_x = 0.0
        self._accum_y = 0.0
