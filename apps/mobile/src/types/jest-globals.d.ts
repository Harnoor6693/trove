/**
 * Ambient Jest globals.
 *
 * `@types/jest` is not a dependency of this app, and `@types/detox` (a
 * devDependency for the Detox e2e specs) otherwise wins the global-name race
 * for `describe`/`it`/`expect`, typing every assertion as Detox's
 * `Expect<Promise<void>>` and leaving `jest` usable only as a namespace.
 * `tsconfig.json` now pins `types: ["node"]` so Detox's globals are no longer
 * auto-included; these declarations re-export the real Jest globals from the
 * `@jest/globals` entry point that ships inside Jest itself, so no new
 * dependency is needed. `@testing-library/jest-native/extend-expect` augments
 * `@jest/expect` directly, so its matchers come along for free.
 */
import type {
  afterAll as afterAllFn,
  afterEach as afterEachFn,
  beforeAll as beforeAllFn,
  beforeEach as beforeEachFn,
  describe as describeFn,
  expect as expectFn,
  it as itFn,
  jest as jestObject,
  test as testFn,
} from '@jest/globals';

declare global {
  const jest: typeof jestObject;
  const expect: typeof expectFn;
  const describe: typeof describeFn;
  const it: typeof itFn;
  const test: typeof testFn;
  const beforeAll: typeof beforeAllFn;
  const beforeEach: typeof beforeEachFn;
  const afterAll: typeof afterAllFn;
  const afterEach: typeof afterEachFn;
}

/**
 * `@types/jest`'s `jest.Mock` / `jest.MockedFunction` namespace aliases, which
 * test files annotate mocks with. `@jest/globals` exposes the same types as
 * exports of `jest-mock` rather than as a global namespace, so they are
 * re-declared here against that package.
 */
declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace jest {
    type Mock<
      T = any, // eslint-disable-line @typescript-eslint/no-explicit-any
      Y extends unknown[] = any[], // eslint-disable-line @typescript-eslint/no-explicit-any
    > = import('jest-mock').Mock<(...args: Y) => T>;
    type MockedFunction<T extends (...args: never[]) => unknown> =
      import('jest-mock').MockedFunction<T>;
    type Mocked<T extends object> = import('jest-mock').Mocked<T>;
    type SpyInstance<T extends (...args: never[]) => unknown> =
      import('jest-mock').SpiedFunction<T>;
  }
}
