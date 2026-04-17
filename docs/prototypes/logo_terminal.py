#!/usr/bin/env python3
"""Render logo2.png as terminal art using half-block characters and ANSI true color."""

from PIL import Image
import numpy as np
import sys
import os

def render_logo(path, columns=50):
    img = Image.open(path).convert("RGBA")
    arr = np.array(img)
    h, w = arr.shape[:2]

    scale = w / columns
    new_w = columns
    new_h = int(h / scale)
    if new_h % 2 != 0:
        new_h += 1

    img_resized = img.resize((new_w, new_h), Image.LANCZOS)
    arr2 = np.array(img_resized)

    GREEN = "\033[38;2;0;255;65m"
    RESET = "\033[0m"

    def is_green(pixel):
        r, g, b, a = int(pixel[0]), int(pixel[1]), int(pixel[2]), int(pixel[3])
        return a > 128 and g > 128

    lines = []
    for y in range(0, new_h, 2):
        line = ""
        for x in range(new_w):
            top = is_green(arr2[y, x])
            bot = is_green(arr2[y + 1, x]) if y + 1 < new_h else False

            if top and bot:
                line += f"{GREEN}\u2588{RESET}"
            elif top and not bot:
                line += f"{GREEN}\u2580{RESET}"
            elif not top and bot:
                line += f"{GREEN}\u2584{RESET}"
            else:
                line += " "
        lines.append(line)

    print("\n".join(lines))


if __name__ == "__main__":
    script_dir = os.path.dirname(os.path.abspath(__file__))
    logo_path = os.path.join(script_dir, "logo2.png")

    cols = int(sys.argv[1]) if len(sys.argv) > 1 else 50
    render_logo(logo_path, cols)
