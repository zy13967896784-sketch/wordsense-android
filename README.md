# 词感 WordSense · Android 版

把单词放进真实句子里，跟着原声读出来，再用间隔复习把句子和词一起记住。

## 下载安装

从仓库的 `dist/` 取：

- `词感-android.apk`（versionCode 15）
- `安卓安装说明.txt`

同签名覆盖安装会保留学习数据。

- minSdk 21，targetSdk 33
- 系统 TTS 朗读，MediaRecorder 录音
- 优先系统 SpeechRecognizer，失败时尝试系统语音输入
- 每日提醒走 AlarmManager + 系统通知

## 功能

- 句子跟读：原句、慢读、逐词查词、整句翻译、录音回放、四档自评。
- 自然发音：单词优先用有道，整句用百度英文语音；断网/失败自动回退系统 TTS，可在设置关闭。
- 美/英口音：设置里切换 US / UK，单词和整句都跟随。
- 跟读语速：0.70×–1.30× 可调，默认 1.00×。
- 离线音频：四级/六级单词和例句内置美式、英式 Opus 音频，优先本地播放；其他词书仍在线/系统 TTS。
- 自动评分：先播放例句，再开始录音，读完点停止并评分。
- 快刷题型：看词选义、据义择词、拼写、混合模式。
- 盲听选义。
- 快刷 / 盲听答错排到 3 个词后重来，直到答对。
- 跟读、刷词、盲听各自独立的 SRS 进度；词库页顶部切换查看。
- 今日目标圆环、打卡日历、14 天统计、每日提醒、数据备份。
- 词库：四级 4717 / 六级 7214 / 考研 7673 / 雅思 7650 / 托福 7693，去重 8531 词。

## 从源码构建

需要 Android SDK build-tools（aapt2 / d8 / zipalign / apksigner）和 JDK。

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-android.ps1
```

生成 `dist/WordSense-1.2.0-android.apk`。

构建脚本会校验 `resources.arsc` 必须 STORED、4 字节对齐，并做 v1/v2/v3 签名。

## 相关仓库

- Windows 版：<https://github.com/zy13967896784-sketch/wordsense-pc>
- 本仓库：<https://github.com/zy13967896784-sketch/wordsense-android>

## 数据与隐私

学习记录保存在应用私有存储中，不上传；录音只用于本地回放。

词库为自建数据，例句为学习用改写，仅供个人学习使用。

## 签名说明

仓库不提交 `mobile/wordsense.jks`。源码重新构建前，自行生成一个开发签名：

```powershell
keytool -genkeypair -keystore mobile/wordsense.jks -alias wordsense `
  -storepass wordsense -keypass wordsense -dname "CN=WordSense" `
  -keyalg RSA -keysize 2048 -validity 10000
```

`dist/词感-android.apk` 已签名，普通用户可直接安装。
