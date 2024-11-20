const { getDefaultConfig } = require('expo/metro-config')
const path = require('path')
const config = getDefaultConfig(__dirname)

config.resolver.nodeModulesPaths = [
  // Include the local module path
  path.resolve(__dirname, './node_modules'),
  path.resolve(__dirname, '../node_modules'),
]

config.watchFolders = [path.resolve(__dirname, '../')]

module.exports = config
