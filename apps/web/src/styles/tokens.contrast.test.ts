import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const css = readFileSync(resolve(__dirname, 'tokens.css'), 'utf8')

function token(name: string): string {
  const m = new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})`).exec(css)
  if (!m) throw new Error(`token --${name} not found`)
  return m[1]
}

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function ratio(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

// Foreground / background pairs the app actually uses for text.
const PAIRS: [string, string][] = [
  ['ink', 'paper'], ['ink', 'card'],
  ['ink-2', 'paper'], ['ink-2', 'card'],
  ['ink-3', 'paper'], ['ink-3', 'card'], ['ink-3', 'paper-dim'],
  ['sage', 'paper'], ['sage', 'card'], ['sage', 'sage-soft'],
  ['amber', 'paper'], ['amber', 'card'], ['amber', 'amber-soft'],
  ['red', 'paper'], ['red', 'card'], ['red', 'red-soft'],
  ['blue', 'paper'], ['blue', 'card'],
]

describe('design tokens: WCAG AA text contrast', () => {
  it.each(PAIRS)('--%s on --%s is at least 4.5:1', (fg, bg) => {
    expect(ratio(token(fg), token(bg))).toBeGreaterThanOrEqual(4.5)
  })
})
