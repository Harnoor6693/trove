import React from 'react';
import { render } from '@testing-library/react-native';
import { CoachBubble } from './CoachBubble';

describe('CoachBubble', () => {
  it('renders user message content', () => {
    const { getByText } = render(<CoachBubble role="user" content="Hello coach" />);
    expect(getByText('Hello coach')).toBeTruthy();
  });

  it('user bubble is right-aligned', () => {
    const { getByTestId } = render(<CoachBubble role="user" content="Hi" testID="user-wrapper" />);
    expect(JSON.stringify(getByTestId('user-wrapper').props.style)).toContain('flex-end');
  });

  it('assistant bubble is left-aligned', () => {
    const { getByTestId } = render(
      <CoachBubble role="assistant" content="How can I help?" testID="asst-wrapper" />
    );
    expect(JSON.stringify(getByTestId('asst-wrapper').props.style)).toContain('flex-start');
  });

  it('assistant bubble uses Fraunces display font', () => {
    const { getByText } = render(<CoachBubble role="assistant" content="Test" />);
    expect(JSON.stringify(getByText('Test').props.style)).toContain('Fraunces_300Light');
  });
});

describe('CoachBubble while a stream is writing into it', () => {
  it('shows a caret that is hidden from assistive technology', () => {
    const { getByTestId } = render(<CoachBubble role="assistant" content="Half an" streaming />);
    // `includeHiddenElements` is required precisely BECAUSE the caret is
    // hidden from assistive technology, which is the point of this test.
    const caret = getByTestId('coach-bubble-streaming', { includeHiddenElements: true });
    // Decorative: a screen reader must not read a block character between
    // every re-render of a partially written answer.
    expect(caret.props.accessibilityElementsHidden).toBe(true);
    expect(caret.props.importantForAccessibility).toBe('no');
  });

  it('has no caret once the answer is finished', () => {
    const { queryByTestId } = render(<CoachBubble role="assistant" content="Done." />);
    expect(queryByTestId('coach-bubble-streaming', { includeHiddenElements: true })).toBeNull();
  });

  it('is NOT a live region — the screen announces the state instead', () => {
    // A live region here would restate the whole answer on every token that
    // lands, which is what makes a streamed transcript unusable with a screen
    // reader. The screen carries one small polite region for the state.
    const { getByLabelText } = render(<CoachBubble role="assistant" content="Groceries." />);
    const bubble = getByLabelText('The coach answered: Groceries.');
    expect(bubble.props.accessibilityLiveRegion).toBe('none');
  });

  it("labels a question as the member's own", () => {
    const { getByLabelText } = render(<CoachBubble role="user" content="Why?" />);
    expect(getByLabelText('You asked: Why?')).toBeTruthy();
  });

  it('renders the answer as plain text and nothing else', () => {
    // The contract's rule: the answer is rendered and never interpreted. A
    // markup-looking answer stays literal text.
    const answer = '<b>not markup</b> https://example.test';
    const { getByText } = render(<CoachBubble role="assistant" content={answer} />);
    expect(getByText(answer)).toBeTruthy();
  });
});
