# 16 — the seal's grain may be invisible on a poor screen

Status: open
Severity: low
Source: task 2.12, reviewing the captured seal-screen.png

## Problem

`Seal.Mass` (#1A1D22) and `Seal.Grain` (#23272E) differ by roughly 5% luminance.
That is deliberate — the design language asks for material, not for a pattern —
but on a cheap LCD, at low brightness, or in direct sunlight the grain plausibly
disappears entirely, leaving a flat dark rectangle.

The grain is not decoration. It is what makes "sealed" legible without relying
on colour, which matters because the only colour in the app is spent on the act
of opening. If the grain vanishes, the locked state is carried by the word
"sealed" alone.

## Why it is not fixed now

Fixing it by guessing at a contrast ratio replaces one unverified value with
another. The honest test is a real device at low brightness, and the emulator
cannot answer it.

## Resolution

Look at the screen on physical hardware at minimum brightness. If the grain is
gone, raise `Seal.Grain` until it survives, and record the floor as a comment
next to the value so the next person does not quietly lower it again.
