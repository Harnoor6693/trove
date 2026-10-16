import React from 'react';
import { StyleSheet } from 'react-native';
import { render } from '@testing-library/react-native';
import { useAuthStore } from '@stores/authStore';
import { useUiStore } from '@stores/uiStore';
import { Colors, DarkColors } from '@design/tokens';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnToggle } from '@components/ui/MnToggle/MnToggle';
import { MnRadioCard } from '@components/ui/MnRadioCard/MnRadioCard';
import { MoneyDisplay } from '@components/ui/MoneyDisplay/MoneyDisplay';
import { MnDot } from '@components/primitives/MnDot/MnDot';
import { MnBar } from '@components/primitives/MnBar/MnBar';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { MnSpark } from '@components/primitives/MnSpark/MnSpark';

/** WCAG 2.x contrast ratio between two #rrggbb colours. */
function contrast(a: string, b: string): number {
  const lum = (hex: string) => {
    const [r, g, bl] = [1, 3, 5]
      .map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
      .map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
    return 0.2126 * r! + 0.7152 * g! + 0.0722 * bl!;
  };
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
  return (hi! + 0.05) / (lo! + 0.05);
}

const flat = (node: { props: { style?: unknown } }) =>
  StyleSheet.flatten(node.props.style as never) as Record<string, unknown>;

const modes: [string, Record<keyof typeof Colors, string>][] = [
  ['light', Colors],
  ['dark', DarkColors],
];

describe.each(modes)('primitives in %s mode', (mode, T) => {
  beforeEach(() => {
    useUiStore.setState({ theme: mode as 'light' | 'dark' });
    useAuthStore.setState({
      me: { currency: 'CAD', locale: 'en-CA' } as never,
      preferences: { locale: 'en-CA' } as never,
    });
  });

  it('MnBtn primary uses theme sage and paper label', async () => {
    const { getByTestId, getByText } = await render(<MnBtn label="Go" testID="b" />);
    expect(flat(getByTestId('b')).backgroundColor).toBe(T.sage);
    expect(flat(getByText('Go')).color).toBe(T.paper);
  });

  it('MnBtn ghost uses theme line and ink', async () => {
    const { getByTestId, getByText } = await render(
      <MnBtn label="Go" variant="ghost" testID="b" />
    );
    expect(flat(getByTestId('b')).borderColor).toBe(T.line);
    expect(flat(getByText('Go')).color).toBe(T.ink);
  });

  it('MnCard uses theme card', async () => {
    const { getByTestId } = await render(<MnCard testID="c" />);
    expect(flat(getByTestId('c')).backgroundColor).toBe(T.card);
  });

  it('MoneyDisplay uses theme ink, sage and red', async () => {
    const { getByText } = await render(
      <>
        <MoneyDisplay amount="1.00" />
        <MoneyDisplay amount="2.00" colorBySign />
        <MoneyDisplay amount="-3.00" colorBySign />
      </>
    );
    expect(flat(getByText(/1\.00/)).color).toBe(T.ink);
    expect(flat(getByText(/2\.00/)).color).toBe(T.sage);
    expect(flat(getByText(/3\.00/)).color).toBe(T.red);
  });

  it('MnPill uses theme tokens', async () => {
    const { getByText } = await render(<MnPill label="Hi" variant="sage" />);
    expect(flat(getByText('Hi')).color).toBe(T.sage);
    expect(flat(getByText('Hi').parent!).backgroundColor).toBe(T.sageSoft);
  });

  it('MnEyebrow uses theme ink3', async () => {
    const { getByText } = await render(<MnEyebrow>hi</MnEyebrow>);
    expect(flat(getByText('HI')).color).toBe(T.ink3);
  });

  it('MnToggle uses theme tokens', async () => {
    const { getByText, getByTestId } = await render(
      <MnToggle label="Lbl" value onToggle={() => {}} testID="t" />
    );
    expect(flat(getByText('Lbl')).color).toBe(T.ink);
    expect(getByTestId('t-switch').props.thumbTintColor).toBe(T.sage);
  });

  it('MnRadioCard uses theme tokens', async () => {
    const { getByText, getByRole } = await render(
      <MnRadioCard label="Opt" selected onSelect={() => {}} />
    );
    expect(flat(getByText('Opt')).color).toBe(T.ink);
    expect(flat(getByRole('radio')).borderColor).toBe(T.sage);
  });

  it('MnDot and MnSpark default to theme sage', async () => {
    const { getByTestId } = await render(
      <>
        <MnDot testID="d" />
        <MnSpark data={[1, 2, 3]} testID="s" />
      </>
    );
    expect(flat(getByTestId('d')).backgroundColor).toBe(T.sage);
    expect(getByTestId('svg-polyline').props.stroke).toBe(T.sage);
  });

  it('MnBar uses theme track and fill', async () => {
    const { getByTestId } = await render(<MnBar value={95} max={100} testID="bar" />);
    expect(flat(getByTestId('bar')).backgroundColor).toBe(T.line);
    expect(flat(getByTestId('bar-fill')).backgroundColor).toBe(T.red);
  });

  it('money amounts meet WCAG AA (4.5:1) on paper and card', () => {
    for (const fg of [T.ink, T.sage, T.red]) {
      for (const bg of [T.paper, T.card]) {
        expect(contrast(fg, bg)).toBeGreaterThanOrEqual(4.5);
      }
    }
  });
});
