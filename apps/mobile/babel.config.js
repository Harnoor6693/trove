module.exports = function (api) {
  api.cache(true);
  return {
    presets: ['babel-preset-expo'],
    plugins: [
      [
        'module-resolver',
        {
          root: ['./src'],
          alias: {
            '@': './src',
            '@api': './src/api',
            '@design': './src/design',
            '@hooks': './src/hooks',
            '@stores': './src/stores',
            '@components': './src/components',
            '@lib': './src/lib',
          },
        },
      ],
      'react-native-reanimated/plugin',
    ],
  };
};
