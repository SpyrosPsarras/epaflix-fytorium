const expo = require('eslint-config-expo/flat');

module.exports = [...expo, { ignores: ['android/*', 'ios/*', 'dist/*', '.cache/*'] }];
