const { getDefaultConfig } = require('expo/metro-config');

// The bundler inlines this, so release bundles drop debug-only branches such as the synthetic push tokens.
process.env.EXPO_PUBLIC_FYTORIUM_VARIANT = process.env.FYTORIUM_VARIANT === 'release' ? 'release' : 'debug';

module.exports = getDefaultConfig(__dirname);
