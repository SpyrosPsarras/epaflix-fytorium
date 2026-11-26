Pod::Spec.new do |s|
  s.name           = 'FytoriumNative'
  s.version        = '1.0.0'
  s.summary        = 'Fytorium native platform code'
  s.license        = 'UNLICENSED'
  s.author         = 'Fytorium'
  s.homepage       = 'https://github.com/SpyrosPsarras/epaflix-fytorium'
  s.platforms      = { :ios => '16.4' }
  s.swift_version  = '5.9'
  s.source         = { git: '' }
  s.dependency 'ExpoModulesCore'
  s.source_files = '**/*.swift'
end
