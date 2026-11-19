# 楽器キー (GakkiKeys)

Androidのキーボード（IME）がそのまま楽器になります。🥁ドラム / 🎹鍵盤（ピアノ＋シンセ）/ ⌨文字入力（キーごとにランダム効果音、テンキーはDTMF）。
音源ファイルは使わず、すべてリアルタイム合成（低遅延 AudioTrack, float PCM）。外部ライブラリなし・Javaのみ。

## ビルド
- **GitHub Actions**: このフォルダをリポジトリにpushすると `Build APK` が走り、Artifacts に `GakkiKeys-apk` が出ます（release APK、debug鍵で署名済みなのでそのままインストール可）。
- **ローカル**: Android Studio で開いて Run、または `./gradlew assembleRelease`。

## 使い方
1. アプリ「楽器キー」を開く → ①でキーボード設定から有効化 → ②で切り替え
2. 任意の入力欄をタップ

| モード | 操作 |
|---|---|
| 🥁 ドラム | 4×3パッド（KICK/SNARE/CLAP/RIM, HAT/CRASH/COWBELL, TOM×3/808）。中心ほど強い。C.HATでO.HATがチョーク |
| 🎹 鍵盤 | ⚙で音色（ピアノ / LEAD / SQUARE / PAD / BASS）と幅（1 / 1.5 / 2 / 3オクターブ、標準1）。ピアノ=手前ほど強い・スライドでグリッサンド・SUSでペダル。シンセ=縦で明るさ・横スライドでポルタメント |
| ⌨ 文字入力 | QWERTY。ローマ字かな(あ) / 英字(A)。キーごとにランダムな効果音（18種×音程）。123 = 電話型テンキーでDTMF、#+= = 記号 |

### ⚙ メニュー
- 音色 / 幅 / 鍵盤表示（ドレミ・CDE・なし）
- タイプ音：OFF・小・中・大、🎲で効果音の割り当てをシャッフル（割り当ては保存される）
- DTMF：テンキーのプッシュ音 ON/OFF

### 文字入力モード
- ローマ字はGoogle日本語入力風（`nn`/`n'`/促音`kk`/`xtu`/`dhi`など）。
- 漢字変換辞書は入っていません。入力中は **スペースでひらがな⇔カタカナ**、⏎(確定)で確定。
- パスワード・メール・URL欄では自動で英字、数字・電話欄では自動で記号ページ。

- ◀ ▶ でオクターブ、`あ` で「叩いた音を文字入力」(ドレミ / ドンタン…)、🌐 タップで次のIME・長押しで選択、▼で閉じる
- 音量はメディア音量

## 構成
- `SynthCore.java` – Android非依存のシンセ本体（ボイス32, ドラム12種, 加算合成ピアノ, polyBLEP+SVFフィルターのシンセ, 簡易リバーブ）
- `SoundEngine.java` – AudioTrack（PERFORMANCE_MODE_LOW_LATENCY）で再生するオーディオスレッド
- `InstrumentView.java` – 描画とマルチタッチ
- `GakkiIME.java` – InputMethodService
- `SetupActivity.java` – 有効化ガイド＋試し弾き欄
