



## PiperReader

An offline voice book reader for Android, powered by [Piper TTS](https://github.com/OHF-voice/piper1-gpl) and [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx).

<details>
<summary>UI</summary>
<video src="https://github.com/user-attachments/assets/cdc9cbed-4d14-4cb8-a385-ce1b2a29fb80" width="100%" controls></video>
</details>

#### Why Manual Model Installation?
This app does not feature an in-app model downloader. It was specifically built with flexibility in mind—allowing developers, researchers, and enthusiasts to run their own **custom-trained Piper models** and use **custom phoneme dictionaries** (via `espeak-ng-data`) to fine-tune pronunciation rules.

#### Default Models
https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models

Get the pre-built APK directly on [TG](https://t.me/HAassist/1693)

---
The `sherpa-onnx` library consumes up to 600 MB on the longest sentences, but usually less. Almost all SoCs will synthesize voice faster than real time.

<img width="480" alt="image" src="https://github.com/user-attachments/assets/129f3a70-1e0c-4a68-a1bf-6ddd189de34c" />
