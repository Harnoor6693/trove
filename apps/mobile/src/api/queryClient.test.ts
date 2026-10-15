import { AppState, type AppStateStatus } from 'react-native';

describe('focus manager (B5-06)', () => {
  it('follows AppState: unfocused in the background, focused when active again', () => {
    let handler: ((s: AppStateStatus) => void) | undefined;
    const remove = jest.fn();
    jest.spyOn(AppState, 'addEventListener').mockImplementation(((
      _: string,
      h: (s: AppStateStatus) => void
    ) => {
      handler = h;
      return { remove };
    }) as never);

    let focusManager!: typeof import('@tanstack/react-query').focusManager;
    jest.isolateModules(() => {
      require('./queryClient');
      focusManager = require('@tanstack/react-query').focusManager;
    });
    const unsubscribe = focusManager.subscribe(() => undefined);

    expect(handler).toBeDefined();
    handler?.('background');
    expect(focusManager.isFocused()).toBe(false);
    handler?.('active');
    expect(focusManager.isFocused()).toBe(true);

    unsubscribe();
    expect(remove).toHaveBeenCalled();
  });
});
