#!/usr/bin/env python3
"""
Kukuvaia owl logo — terminal splash screen prototype.
Phosphor green (#00FF41) on black, Matrix/circuit-board aesthetic.

Run: python3 preview.py
"""

# ANSI True Color escape sequences
BRIGHT  = "\033[1;38;2;0;255;65m"   # #00FF41 bold — eyes, title
GREEN   = "\033[0;38;2;0;204;51m"   # #00CC33 — main structure
DIM     = "\033[0;38;2;0;143;17m"   # #008F11 — depth, shadows
DARK    = "\033[0;38;2;0;68;0m"     # #004400 — faint background
RESET   = "\033[0m"

B = BRIGHT
G = GREEN
D = DIM
K = DARK

owl = f"""
{K}          ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
{K}          ░░{G}▄{K}░░░░░░░░░░░░░░░░░░░░░░░{G}▄{K}░░
{K}          ░{G}▐{K}░{G}▌{K}░░░░░░░░░░░░░░░░░░░░░{G}▐{K}░{G}▌{K}░
{K}         ░{G}▐{K}░░{G}▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀{K}░░{G}▌{K}░
{K}         ░{G}▐{K}░░░░░░░░░░░░░░░░░░░░░░░{G}▌{K}░
{K}         ░{G}▐{K}░{G}┌─────────┐ ┌─────────┐{K}░{G}▌{K}░
{K}         ░{G}▐{K}░{G}│{K}░░{B}╔═════╗{K}░░{G}│ │{K}░░{B}╔═════╗{K}░░{G}│{K}░{G}▌{K}░
{K}         ░{G}▐{K}░{G}│{K}░░{B}║ ◉ ◉ ║{K}░░{G}│ │{K}░░{B}║ ◉ ◉ ║{K}░░{G}│{K}░{G}▌{K}░
{K}         ░{G}▐{K}░{G}│{K}░░{B}╚═════╝{K}░░{G}│ │{K}░░{B}╚═════╝{K}░░{G}│{K}░{G}▌{K}░
{K}         ░{G}▐{K}░{G}└────┬────┘ └────┬────┘{K}░{G}▌{K}░
{K}          {G}▀▄{K}░░░░{G}│{K}░░{G}╱▔▔╲{K}░░{G}│{K}░░░░{G}▄▀{K}░
{K}          ░{G}▐{K}░░░░{G}└──╲▄▄╱──┘{K}░░░░{G}▌{K}░
{K}          ░{G}▐{K}░░{G}╔══════════════╗{K}░░{G}▌{K}░
{K}          ░{G}▐{K}░░{G}║{K}░{G}┌┬──────┬┐{K}░{G}║{K}░░{G}▌{K}░
{K}          ░{G}▐{K}░░{G}║{K}░{G}│├──{D}▓▓{G}──┤│{K}░{G}║{K}░░{G}▌{K}░
{K}          ░{G}▐{K}░░{G}║{K}░{G}││{K}░░{D}░░{K}░░{G}││{K}░{G}║{K}░░{G}▌{K}░
{K}          ░{G}▐{K}░░{G}║{K}░{G}│├──{D}▓▓{G}──┤│{K}░{G}║{K}░░{G}▌{K}░
{K}          ░{G}▐{K}░░{G}║{K}░{G}││{K}░░{D}░░{K}░░{G}││{K}░{G}║{K}░░{G}▌{K}░
{K}          ░{G}▐{K}░░{G}║{K}░{G}└┴──────┴┘{K}░{G}║{K}░░{G}▌{K}░
{K}          ░{G}▐{K}░░{G}╚══════════════╝{K}░░{G}▌{K}░
{K}           {G}▀▄{K}░░░{G}╱▔▀▀▀▀▔╲{K}░░░{G}▄▀{K}░
{K}          ░░░{G}▀▀▄▄▀{K}░░░░{G}▀▄▄▀▀{K}░░░
{K}          ░░░░░░{G}▀▀▀▀▀▀{K}░░░░░░
{RESET}
{B}          K U K U V A I A{RESET}
{D}           wisdom  agent{RESET}

{D}      ─────────────────────────────{RESET}
"""

# --- Variant 2: Compact / minimal ---

owl_compact = f"""
{K}        ░░░░░░░░░░░░░░░░░░░░░░░░░
{K}       ░░{G}▄{K}░░░░░░░░░░░░░░░░░░░░░{G}▄{K}░░
{K}      ░{G}╱{K}░{G}╲━━━━━━━━━━━━━━━━━━━╱{K}░{G}╲{K}░
{K}      {G}╱  ┌──────┐     ┌──────┐  ╲{K}
{K}     {G}│   │{K}░{B}╔══╗{K}░{G}│     │{K}░{B}╔══╗{K}░{G}│   │
{K}     {G}│   │{K}░{B}║◉◉║{K}░{G}│     │{K}░{B}║◉◉║{K}░{G}│   │
{K}     {G}│   │{K}░{B}╚══╝{K}░{G}│     │{K}░{B}╚══╝{K}░{G}│   │
{K}      {G}╲  └──┬───┘     └───┬──┘  ╱{K}
{K}       {G}╲    └───┐ {D}▽▽{G} ┌───┘    ╱
{K}        {G}╲       └─{D}──{G}─┘       ╱
{K}        {G}│    ╔═══════════╗    │
{K}        {G}│    ║ {D}┌┬────┬┐{G} ║    │
{K}        {G}│    ║ {D}│├─{K}▓▓{D}─┤│{G} ║    │
{K}        {G}│    ║ {D}││    ││{G} ║    │
{K}        {G}│    ║ {D}│├─{K}▓▓{D}─┤│{G} ║    │
{K}        {G}│    ║ {D}└┴────┴┘{G} ║    │
{K}        {G}│    ╚═══════════╝    │
{K}         {G}╲     ╱▔▀▀▔╲     ╱
{K}          {G}╲───╱      ╲───╱
{RESET}
{B}         K U K U V A I A{RESET}
{D}          wisdom  agent{RESET}

{D}     ─────────────────────────────{RESET}
"""

# --- Variant 3: Braille high-res ---

owl_braille = f"""
{K}          ⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀
{D}            ⣠⠤⠤⣀           ⣀⠤⠤⣄
{G}           ⣼    ⠉⠒⠒⠒⠒⠒⠒⠒⠒⠒⠉    ⣧
{G}          ⡇  {B}⣴⣶⣶⣦{G}         {B}⣴⣶⣶⣦{G}  ⡇
{G}          ⡇  {B}⣿⣿⣿⣿{G}         {B}⣿⣿⣿⣿{G}  ⡇
{G}          ⡇  {B}⠛⠛⠛⠛{G}         {B}⠛⠛⠛⠛{G}  ⡇
{G}          ⣇    ⠑⢄     ⡠⠊    ⣸
{G}           ⠘⢦    ⠑⠢{D}⣀⣀{G}⠔⠊    ⡴⠃
{G}            ⠘⢦  ⡏⠉⠉⠉⠉⠉⠉⢹  ⡴⠃
{G}             ⠈⢦⡇ {D}⡇⡇  ⡇⡇{G} ⢸⡴⠁
{G}               ⡇ {D}⡇⣧⣤⣤⣧⡇{G} ⢸
{G}               ⡇ {D}⡇⡇  ⡇⡇{G} ⢸
{G}               ⡇ {D}⠃⠇  ⠸⠃{G} ⢸
{G}              ⣇⡇⠉⠉⠉⠉⠉⠉⠉⠉⢸⣸
{D}               ⠑⠢⣀    ⣀⠔⠊
{D}                  ⠈⠉⠉⠁
{RESET}
{B}          K U K U V A I A{RESET}
{D}           wisdom  agent{RESET}

{D}      ─────────────────────────────{RESET}
"""


if __name__ == "__main__":
    import sys

    variant = sys.argv[1] if len(sys.argv) > 1 else "all"

    if variant in ("1", "full", "all"):
        print(f"\n{D}  ━━━ Variant 1: Full / detailed ━━━{RESET}")
        print(owl)

    if variant in ("2", "compact", "all"):
        print(f"\n{D}  ━━━ Variant 2: Compact / angular ━━━{RESET}")
        print(owl_compact)

    if variant in ("3", "braille", "all"):
        print(f"\n{D}  ━━━ Variant 3: Braille / high-res ━━━{RESET}")
        print(owl_braille)
