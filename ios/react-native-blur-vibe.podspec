require "json"

package = JSON.parse(File.read(File.join(__dir__, "..", "package.json")))

Pod::Spec.new do |s|
  s.name         = "react-native-blur-vibe"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]
  s.platforms    = { :ios => "14.0" }
  s.source       = { :git => "https://github.com/I-am-Pritam-20/react-native-blur-vibe.git", :tag => "#{s.version}" }

  # Include all Swift and ObjC files in ios/ and ios/Views/
  s.source_files = "ios/**/*.{h,m,mm,swift}"

  s.requires_arc = true

  s.dependency "React-Core"

  install_modules_dependencies(s)
end
