import React from 'react';
import { render } from '@testing-library/react-native';
import { MnBar } from './MnBar';

describe('MnBar', () => {
  it('renders with testID', () => {
    const { getByTestId } = render(<MnBar value={50} max={100} testID="bar" />);
    expect(getByTestId('bar')).toBeTruthy();
  });

  it('value=0 renders empty bar', () => {
    const { getByTestId } = render(<MnBar value={0} max={100} testID="bar-fill" />);
    const fill = getByTestId('bar-fill-fill');
    expect(fill.props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ width: '0%' })])
    );
  });

  it('value below 70% uses sage color', () => {
    const { getByTestId } = render(<MnBar value={60} max={100} testID="bar-sage" />);
    const fill = getByTestId('bar-sage-fill');
    expect(JSON.stringify(fill.props.style)).toContain('#4a6b4f');
  });

  it('value 70–90% uses amber color', () => {
    const { getByTestId } = render(<MnBar value={80} max={100} testID="bar-amber" />);
    const fill = getByTestId('bar-amber-fill');
    expect(JSON.stringify(fill.props.style)).toContain('#a06a1e');
  });

  it('value above 90% uses red color', () => {
    const { getByTestId } = render(<MnBar value={95} max={100} testID="bar-red" />);
    const fill = getByTestId('bar-red-fill');
    expect(JSON.stringify(fill.props.style)).toContain('#9b3a2a');
  });

  it('threshold colors apply when no color prop is provided', () => {
    const { getByTestId } = render(<MnBar value={95} max={100} testID="bar-no-color" />);
    const fill = getByTestId('bar-no-color-fill');
    expect(JSON.stringify(fill.props.style)).toContain('#9b3a2a');
  });

  it('valid color override replaces threshold color', () => {
    const { getByTestId } = render(
      <MnBar value={95} max={100} color="#00ff00" testID="bar-override" />
    );
    const fill = getByTestId('bar-override-fill');
    expect(JSON.stringify(fill.props.style)).toContain('#00ff00');
  });

  it('warns in dev and falls back to thresholds when color is empty string', () => {
    const warnSpy = jest.spyOn(console, 'warn').mockImplementation(() => {});
    const { getByTestId } = render(<MnBar value={95} max={100} color="" testID="bar-bad" />);
    const fill = getByTestId('bar-bad-fill');
    // Falls back to red threshold, not the empty-string override.
    expect(JSON.stringify(fill.props.style)).toContain('#9b3a2a');
    expect(warnSpy).toHaveBeenCalledWith(expect.stringMatching(/MnBar.*invalid/));
    warnSpy.mockRestore();
  });
});
