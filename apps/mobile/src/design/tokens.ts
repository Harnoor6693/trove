export const Colors = {
  paper: '#f6f3ec',
  paperDim: '#eeeadf',
  card: '#fffdf7',
  ink: '#1b1a17',
  ink2: '#4a453d',
  ink3: '#8c8578',
  line: '#e6e0d3',
  sage: '#4a6b4f',
  sageSoft: '#dfe7dc',
  amber: '#a06a1e',
  amberSoft: '#f3e6cc',
  // TODO(theme): ember tokens are exported but consumers reach into Colors.* directly
  // instead of routing through useTheme(); wire the dark-mode swap in a dedicated PR.
  ember: '#c4622d',
  emberSoft: '#f6dccc',
  red: '#9b3a2a',
  redSoft: '#fdecea',
  blue: '#3c5a7a',
  blueSoft: '#e8f4fd',
} as const;

export const DarkColors = {
  paper: '#16161a',
  paperDim: '#1e1e22',
  card: '#242428',
  ink: '#f0f0ec',
  ink2: '#b0b0a8',
  ink3: '#707068',
  line: '#2e2e34',
  sage: '#5ba07a',
  sageSoft: '#1a2e22',
  amber: '#f59e0b',
  amberSoft: '#2c200a',
  ember: '#e07a3a',
  emberSoft: '#2e1d12',
  red: '#e25241',
  redSoft: '#2c1210',
  blue: '#4da6e8',
  blueSoft: '#0d1e2c',
} as const;

export const Radius = { sm: 4, md: 8, lg: 16, xl: 24 } as const;

export const Space = {
  s1: 4,
  s2: 8,
  s3: 12,
  s4: 16,
  s5: 20,
  s6: 24,
  s8: 32,
  s10: 40,
  s12: 48,
  s16: 64,
} as const;

export const FontSize = {
  xs: 11,
  sm: 13,
  md: 16,
  lg: 20,
  xl: 28,
  xxl: 36,
} as const;

export const FontFamily = {
  display: 'Fraunces_300Light',
  displaySemi: 'Fraunces_600SemiBold',
  body: 'Inter_400Regular',
  bodyMedium: 'Inter_500Medium',
  bodySemi: 'Inter_600SemiBold',
  mono: 'JetBrainsMono_400Regular',
} as const;
