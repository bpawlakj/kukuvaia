#!/usr/bin/env python3
"""
Kukuvaia splash screen — hand-crafted terminal owl.
Matches the simplified geometric logo: ears, eyes, beak, striped body.

Run: python3 splash.py
"""

B = "\033[1;38;2;0;255;65m"   # bright green (eyes, title)
G = "\033[0;38;2;0;204;51m"   # green (structure)
D = "\033[0;38;2;0;143;17m"   # dim green (shadows)
R = "\033[0m"                  # reset

owl = f"""
{G}            ▄█▄                 ▄█▄
{G}             ██▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄██
{G}              █▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄█
{G}             █▀                 ▀█
{G}            █  {B}▄██████▄   ▄██████▄{G}  █
{G}            █  {B}██{D}██████{B}██   ██{D}██████{B}██{G}  █
{G}            █  {B}██{D}██████{B}██   ██{D}██████{B}██{G}  █
{G}            █  {B}▀██████▀   ▀██████▀{G}  █
{G}             █▄     {G}▄▀{D}▀▀▀{G}▀▄     {G}▄█
{G}              ▀█▄    {D}▀▀▀{G}    ▄█▀
{G}                ▀▀▀▀▀▀▀▀▀▀▀▀▀

{G}              ▄█████████████▄
{G}              ▀█████████████▀

{G}              ▄█████████████▄
{G}              ▀█████████████▀

{G}              █████████████████
{G}              █████████████████

{B}           K U K U V A I A
{D}            wisdom  agent
{R}"""

print(owl)
