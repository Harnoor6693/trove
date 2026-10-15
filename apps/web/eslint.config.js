import js from '@eslint/js'
import tseslint from '@typescript-eslint/eslint-plugin'
import reactHooks from 'eslint-plugin-react-hooks'
import { reactRefresh } from 'eslint-plugin-react-refresh'
import globals from 'globals'

/**
 * Lint config for `apps/web`.
 *
 * The money rules below are the enforcement half of readiness gap P2-15 and
 * defect I12. Before this, the app had a `MoneyDisplay` that ignored its own
 * `currency` prop and printed a literal `$`, two hardcoded `en-CA` locales, a
 * hardcoded `currency: 'CAD'` in onboarding, and about a dozen ad-hoc
 * `` `$${n.toLocaleString()}` `` call sites. A CAD household and a USD household
 * were rendered identically and neither was necessarily right.
 *
 * Fixing that by hand is a one-time job; keeping it fixed is a lint rule. Money
 * may only be rendered through `MoneyDisplay` / `useUserFormat`, both of which
 * take the currency from the value itself and the locale from the user.
 */

/** Currency-shaped literals: `$12`, and the currency codes themselves. */
const NO_CURRENCY_LITERALS = [
  {
    selector: "Literal[value=/\\$\\d/]",
    message:
    "No currency-shaped literals. Money is rendered by <MoneyDisplay amount currency /> or useUserFormat().formatMoney — the symbol comes from Intl, not from a string.",
  },
  {
    selector: "TemplateElement[value.raw=/\\$/]",
    message:
    "No '$' in a template literal. Money is rendered by <MoneyDisplay amount currency /> or useUserFormat().formatMoney — the symbol comes from Intl, not from a string.",
  },
  {
    selector: "Literal[value='CAD']",
    message:
    "No hardcoded currency code. A currency travels with its amount from the API (Money.currency); the only place the codes are written down is src/api/types.ts.",
  },
  {
    selector: "Literal[value='USD']",
    message:
    "No hardcoded currency code. A currency travels with its amount from the API (Money.currency); the only place the codes are written down is src/api/types.ts.",
  },
]

/** Browser-default locale formatting: the user's locale is a server-side fact. */
const NO_TO_LOCALE_STRING = [
  {
    selector: "CallExpression[callee.property.name='toLocaleString']",
    message:
    "No bare toLocaleString(). Use useUserFormat() — it formats with the user's own locale and timezone (readiness gap P2-15), not the browser's.",
  },
  {
    selector: "CallExpression[callee.property.name='toLocaleDateString']",
    message:
    "No bare toLocaleDateString(). Use useUserFormat().formatDate — dates render in the user's locale and timezone, not the browser's.",
  },
  {
    selector: "CallExpression[callee.property.name='toLocaleTimeString']",
    message:
    "No bare toLocaleTimeString(). Use useUserFormat().formatDateTime.",
  },
]

const TEST_FILES = ['**/*.test.ts', '**/*.test.tsx', '**/*.spec.ts', '**/*.spec.tsx', 'src/test/**']

export default [
  // src/api/generated is openapi-typescript output ("Do not make direct changes
  // to the file") — linting it would mean policing rules against generated code,
  // and its `CAD`/`USD` enum literals would trip the currency-literal rule below.
  { ignores: ['dist', 'coverage', 'node_modules', 'src/api/generated'] },
  js.configs.recommended,
  ...tseslint.configs['flat/recommended'],
  reactHooks.configs.flat.recommended,
  {
    files: ['**/*.ts', '**/*.tsx'],
    languageOptions: { globals: { ...globals.browser, ...globals.es2020 } },
    plugins: { 'react-refresh': reactRefresh.plugin },
    rules: {
    'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
    'no-console': 'error',
  },
  },
  {
    // Currency literals are banned everywhere in src/ …
    files: ['src/**/*.ts', 'src/**/*.tsx'],
    // … except in the contract types, where the codes are the contract, and in
    // tests, whose whole job is to pin the rendered output for each currency.
    ignores: ['src/api/types.ts', ...TEST_FILES],
    rules: {
      'no-restricted-syntax': ['error', ...NO_CURRENCY_LITERALS],
    },
  },
  {
    // Pages and components additionally may not format dates or numbers with
    // the browser's default locale.
    files: ['src/pages/**/*.ts', 'src/pages/**/*.tsx', 'src/components/**/*.ts', 'src/components/**/*.tsx'],
    ignores: TEST_FILES,
    rules: {
      'no-restricted-syntax': ['error', ...NO_CURRENCY_LITERALS, ...NO_TO_LOCALE_STRING],
    },
  },
]
