#!/usr/bin/env python3
"""
Convert kukuvaia owl PNG to terminal art using half-block characters.
Each character cell = 2 vertical pixels using ▀▄█ characters.
Produces ANSI True Color output matching the Matrix green palette.
"""

import sys
from PIL import Image

# Matrix green palette
BRIGHT = (0, 255, 65)    # #00FF41
MEDIUM = (0, 204, 51)    # #00CC33
DIM    = (0, 143, 17)    # #008F11
DARK   = (0, 68, 0)      # #004400

def rgb_to_ansi_fg(r, g, b):
    return f"\033[38;2;{r};{g};{b}m"

def rgb_to_ansi_bg(r, g, b):
    return f"\033[48;2;{r};{g};{b}m"

RESET = "\033[0m"
BG_BLACK = "\033[48;2;0;0;0m"

def pixel_brightness(pixel):
    """Get brightness from RGBA or RGB pixel."""
    if len(pixel) == 4:
        r, g, b, a = pixel
        if a < 30:
            return 0
        return (r + g + b) / 3 * (a / 255)
    else:
        r, g, b = pixel
        return (r + g + b) / 3

def brightness_to_green(brightness):
    """Map brightness to a green shade. Returns (r, g, b) or None for black."""
    if brightness < 15:
        return None  # black / transparent
    elif brightness < 60:
        return DARK
    elif brightness < 120:
        return DIM
    elif brightness < 200:
        return MEDIUM
    else:
        return BRIGHT

def convert(image_path, width=50):
    """Convert image to terminal half-block art."""
    img = Image.open(image_path).convert("RGBA")

    # Scale to target width, height adjusted for 2:1 char aspect
    aspect = img.height / img.width
    char_height = int(width * aspect)
    # Make pixel height even (we process 2 rows at a time)
    pixel_height = char_height * 2
    img = img.resize((width, pixel_height), Image.LANCZOS)

    lines = []
    for y in range(0, pixel_height, 2):
        line = ""
        for x in range(width):
            top_pixel = img.getpixel((x, y))
            bot_pixel = img.getpixel((x, y + 1)) if y + 1 < pixel_height else (0, 0, 0, 0)

            top_b = pixel_brightness(top_pixel)
            bot_b = pixel_brightness(bot_pixel)

            top_color = brightness_to_green(top_b)
            bot_color = brightness_to_green(bot_b)

            if top_color is None and bot_color is None:
                line += " "
            elif top_color is not None and bot_color is None:
                # Only top pixel lit -> use ▀ with fg=top
                line += rgb_to_ansi_fg(*top_color) + "▀" + RESET
            elif top_color is None and bot_color is not None:
                # Only bottom pixel lit -> use ▄ with fg=bot
                line += rgb_to_ansi_fg(*bot_color) + "▄" + RESET
            elif top_color == bot_color:
                # Same color -> full block
                line += rgb_to_ansi_fg(*top_color) + "█" + RESET
            else:
                # Different colors -> ▀ with fg=top, bg=bot
                line += rgb_to_ansi_fg(*top_color) + rgb_to_ansi_bg(*bot_color) + "▀" + RESET
        lines.append(line)

    return lines


def main():
    image_path = sys.argv[1] if len(sys.argv) > 1 else "../../logo.png"
    width = int(sys.argv[2]) if len(sys.argv) > 2 else 50

    lines = convert(image_path, width)

    print()
    for line in lines:
        print(f"  {line}")
    print()

    # Title
    B = "\033[1;38;2;0;255;65m"
    D = "\033[0;38;2;0;143;17m"
    R = RESET

    # Center title roughly
    pad = " " * max(0, (width // 2) - 8)
    print(f"  {pad}{B}K U K U V A I A{R}")
    print(f"  {pad} {D}wisdom  agent{R}")
    print()
    print(f"  {pad}{D}{'─' * 17}{R}")
    print()

    # Also output as raw string for embedding in Go/Python
    if "--export" in sys.argv:
        print("\n# === Raw art (no color) for reference ===\n")
        raw_lines = convert_raw(image_path, width)
        for line in raw_lines:
            print(f"  {line}")


def convert_raw(image_path, width=50):
    """ASCII-only version using density characters."""
    img = Image.open(image_path).convert("RGBA")
    aspect = img.height / img.width
    char_height = int(width * aspect * 0.5)  # chars are ~2:1
    img = img.resize((width, char_height), Image.LANCZOS)

    density = " .:-=+*#%@█"
    lines = []
    for y in range(char_height):
        line = ""
        for x in range(width):
            pixel = img.getpixel((x, y))
            b = pixel_brightness(pixel)
            idx = int(b / 256 * (len(density) - 1))
            line += density[idx]
        lines.append(line)
    return lines


if __name__ == "__main__":
    main()
