import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useWindowWidth } from './useWindowWidth'

describe('useWindowWidth', () => {
  beforeEach(() => {
    vi.spyOn(window, 'innerWidth', 'get').mockReturnValue(1280)
  })
  afterEach(() => vi.restoreAllMocks())

  it('returns current window inner width', () => {
    const { result } = renderHook(() => useWindowWidth())
    expect(result.current).toBe(1280)
  })

  it('updates on resize event', () => {
    const { result } = renderHook(() => useWindowWidth())
    act(() => {
      vi.spyOn(window, 'innerWidth', 'get').mockReturnValue(600)
      window.dispatchEvent(new Event('resize'))
    })
    expect(result.current).toBe(600)
  })
})
