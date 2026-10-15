const { getDefaultConfig } = require('expo/metro-config');

const config = getDefaultConfig(__dirname);

const TEST_FILE_RE = /\.(test|spec)\.[jt]sx?$/;

const upstreamResolveRequest = config.resolver.resolveRequest;

config.resolver.blockList = [...(config.resolver.blockList ?? []), /.*\.(test|spec)\.[jt]sx?$/];

config.resolver.resolveRequest = (context, moduleName, platform) => {
  if (TEST_FILE_RE.test(moduleName)) {
    return { type: 'empty' };
  }
  if (upstreamResolveRequest) {
    return upstreamResolveRequest(context, moduleName, platform);
  }
  return context.resolveRequest(context, moduleName, platform);
};

module.exports = config;
