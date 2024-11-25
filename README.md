# React Native Nitro Media Kit

[![npm version](https://img.shields.io/npm/v/react-native-nitro-media-kit.svg?style=flat-square)](https://www.npmjs.com/package/react-native-nitro-media-kit)
[![npm downloads](https://img.shields.io/npm/dm/react-native-nitro-media-kit.svg?style=flat-square)](https://www.npmjs.com/package/react-native-nitro-media-kit)
[![License](https://img.shields.io/npm/l/react-native-nitro-media-kit.svg?style=flat-square)](https://github.com/your-username/react-native-nitro-media-kit/blob/main/LICENSE)

🎥 **React Native Nitro Media Kit** empowers your React Native applications with robust media processing capabilities. Perform tasks like merging videos and converting images into videos with ease, all while leveraging the power of Nitro Modules.

---

## 🚀 Features

- **🎨 Convert Image to Video**: Turn your static images into dynamic videos.  
- **🎬 Merge Videos**: Combine multiple videos seamlessly into one.  
- **⚡ Fast and Efficient**: Optimized for high performance with Nitro Modules.  
- **📱 Cross-Platform**: Fully supports both Android and iOS.  

---

## 🛠️ Prerequisites

Before getting started, ensure you have the following:

- React Native version `0.60+`  
- [`react-native-nitro-modules`](https://www.npmjs.com/package/react-native-nitro-modules)

---

## 📦 Installation

1. Install the package:

   ```bash
   # Using npm
   npm install react-native-nitro-media-kit

   # Using yarn
   yarn add react-native-nitro-media-kit
   ```

2. Link the package:

   ```bash
   npx react-native link react-native-nitro-media-kit
   ```

3. Install pods for iOS:

   ```bash
   cd ios && pod install
   ```

---

## 📘 API Reference

### 🎨 `convertImageToVideo`

Converts an image into a video with a specified duration.  

#### 📋 Parameters:
- `imagePathOrUrl` (string): Local path or URL of the image.  
- `duration` (number): Duration of the video in seconds.  

#### 🛠️ Returns:  
- A promise that resolves with the local path to the created video.  

---

### 🎬 `mergeVideos`

Merges multiple videos into one.  

#### 📋 Parameters:
- `videoPathsOrUrls` (string[]): Array of local paths or URLs of the videos.  

#### 🛠️ Returns:  
- A promise that resolves with the local path to the merged video.  

---

## 📝 Examples

### 🎨 Convert Image to Video

```javascript
import { mediakit } from 'react-native-nitro-media-kit';

const handleConvertImageToVideo = async () => {
  try {
    const video = await mediakit.convertImageToVideo(
      'https://unsplash.com/photos/b9-odQi5oDo/download?ixid=M3wxMjA3fDB8MXxzZWFyY2h8Mnx8dXJsfGVufDB8fHx8MTczMjM0MTM2NXww&force=true&w=1920',
      5
    );
    console.log('Video created at:', video);
  } catch (error) {
    console.error('Error converting image to video:', error);
  }
};
```

---

### 🎬 Merge Videos

```javascript
import { mediakit } from 'react-native-nitro-media-kit';

const handleMergeVideos = async () => {
  try {
    const video = await mediakit.mergeVideos([
      'https://www.sample-videos.com/video321/mp4/720/big_buck_bunny_720p_1mb.mp4',
      'https://www.sample-videos.com/video321/mp4/720/big_buck_bunny_720p_2mb.mp4',
      'https://www.sample-videos.com/video321/mp4/720/big_buck_bunny_720p_2mb.mp4',
    ]);
    console.log('Merged video saved at:', video);
  } catch (error) {
    console.error('Error merging videos:', error);
  }
};
```

---

## ❤️ Support the Project

If you find this package useful, consider sponsoring me to support ongoing development and maintenance. Every contribution, big or small, helps keep this project alive and thriving! 🌟  

[![Sponsor](https://img.shields.io/badge/Sponsor-💖-pink?style=flat-square)](https://github.com/sponsors/your-username)

---

## 🗂️ Project Structure

```
├── android/
│   ├── src/main/java/com/yourpackagename/nitromediakit/
│   └── build.gradle
├── ios/
│   ├── NitroMediaKit.swift
│   └── NitroMediaKit.podspec
├── src/
│   ├── index.ts
│   └── specs/
├── cpp/
├── nitrogen/
├── nitro.json
└── package.json
```

---

## 🤝 Contributing

We ❤️ contributions! If you'd like to improve this package, open an issue or create a pull request. Let's build this together! 🚀  

---

## 📜 License

This project is licensed under the [MIT License](LICENSE).

---

Happy coding! 🚀🎉