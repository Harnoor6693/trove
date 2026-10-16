/**
 * The money/date rules below are the mobile half of the lint restriction §7
 * step 20/21 asks both clients to carry.
 *
 * Amounts arrive from `trove-core` as decimal strings and must be rendered in
 * the *user's* currency and locale, read from `/v1/auth/me` and
 * `/v1/me/preferences` (§4.2, §5.4). Three habits break that, and all three
 * were live in this codebase: `toLocaleString()` (device locale, not the
 * user's), a bare `'$'` (wrong for USD shown to a CAD user and vice versa),
 * and a hardcoded `'CAD'` (this app defaulted `MoneyDisplay` to it). Use
 * `useUserFormat()` — `src/lib/useUserFormat.ts` — which is the formatting core
 * (exempted, with the narrow overrides listed below).
 */
const moneyFormattingRules = [
  {
    selector: "MemberExpression[property.name='toLocaleString']",
    message:
      'toLocaleString() formats in the device locale. Use useUserFormat() so amounts and dates follow the user’s own currency, locale and timezone.',
  },
  {
    selector: "MemberExpression[property.name='toLocaleDateString']",
    message:
      'toLocaleDateString() ignores the user’s timezone preference. Use useUserFormat().date().',
  },
  {
    selector: 'Literal[value=/\\$/]',
    message:
      'No currency symbol literals — the symbol depends on the user’s currency and locale. Use useUserFormat().money() or <MoneyDisplay/>.',
  },
  {
    selector: "Literal[value='CAD']",
    message:
      'No hardcoded currency codes — read the currency from the user’s profile (GET /v1/auth/me). CAD and USD are both first-class.',
  },
  {
    selector: "Literal[value='USD']",
    message:
      'No hardcoded currency codes — read the currency from the user’s profile (GET /v1/auth/me). CAD and USD are both first-class.',
  },
];

module.exports = {
  root: true,
  // `src/api/generated/schema.d.ts` is written by `npm run generate` from
  // `openapi/trove-core.yaml` (openapi-typescript) and checked in unedited —
  // linting generated code serves nothing and `check:generated` is what
  // keeps it honest against the spec.
  ignorePatterns: ['src/api/generated/**'],
  extends: [
    'eslint:recommended',
    'plugin:@typescript-eslint/recommended',
    'plugin:react/recommended',
    'plugin:react-hooks/recommended',
  ],
  parser: '@typescript-eslint/parser',
  plugins: ['@typescript-eslint', 'react', 'react-hooks'],
  settings: { react: { version: 'detect' } },
  rules: {
    'react/react-in-jsx-scope': 'off',
    '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
    '@typescript-eslint/no-require-imports': 'off',
    // Release builds keep console output in logcat/os_log. Everything goes
    // through `logError`, which redacts first (readiness gap P4-20).
    'no-console': 'error',
    'no-restricted-syntax': ['error', ...moneyFormattingRules],
  },
  overrides: [
    {
      // The formatting core itself, the type/constant declarations the currency
      // codes come from, and the one screen where a currency literal is the
      // point: registration is where the user *picks* CAD or USD, before any
      // profile exists to read one from.
      files: ['src/lib/useUserFormat.ts', 'src/api/types.ts', 'app/(auth)/setup.tsx'],
      rules: { 'no-restricted-syntax': 'off' },
    },
    {
      // Tests assert on formatted output, so they must be able to write the
      // expected strings and the currency codes they are testing.
      files: ['**/*.test.ts', '**/*.test.tsx'],
      rules: { 'no-restricted-syntax': 'off' },
    },
  ],
};
