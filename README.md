# 楽器キー (GakkiKeys)

Androidのキーボード（IME）がそのまま楽器になります。🥁ドラム / 🎹鍵盤（ピアノ＋シンセ）/ ⌨文字入力（キーごとにランダム効果音、テンキーはDTMF）。
音源ファイルは使わず、すべてリアルタイム合成（低遅延 AudioTrack, float PCM）。外部ライブラリなし・Javaのみ。

## ビルド
- **GitHub Actions**: pushすると `Build APK` が実行され、Artifacts に `GakkiKeys-apk` が保存されます。`main` にアプリ本体・ビルド設定の変更がある場合は、ビルド成功後に次のパッチ版（例: `v1.0.0` → `v1.0.1`）をGitHub ReleasesへAPK付きで自動公開します。READMEやCI設定だけの変更では新規Releaseを作成しません。
- **バージョン**: 自動公開時に最新のGitタグを参照して `versionName` と `versionCode` をAPKビルドに反映します。公開前にローカルのGradle設定を書き換える必要はありません。
- **APK署名の注意**: 現在のrelease APKはCI環境のデバッグ鍵で署名しています。ビルドごとに鍵が変わりうるため、前版APKへの上書き更新は保証できません。上書き可能な正式配布を行うには永続的な署名鍵を安全に管理する必要があります。
- **ローカル**: Android Studio で開いて Run、または `./gradlew assembleRelease`。

## 使い方
1. アプリ「楽器キー」を開く → ①でキーボード設定から有効化 → ②で切り替え
2. 任意の入力欄をタップ

| モード | 操作 |
|---|---|
| 🥁 ドラム | TR-808風で統一（KICK / SNARE / CLAP / RIM / C.HAT / O.HAT / CYMBAL / COWBELL / TOM L・M・H / BOOM＝ロング808キック）。ハット・シンバルは808と同じ6本の矩形波メタル音源。中心ほど強い。C.HATでO.HATがチョーク |
| 🎹 鍵盤 | ⚙で音色（ピアノ / LEAD / SQUARE / PAD / BASS）と幅（1 / 1.5 / 2 / 3オクターブ、標準1）。ピアノ=手前ほど強い・スライドでグリッサンド・SUSでペダル。シンセ=縦で明るさ・横スライドでポルタメント |
| ☎ 電話機 | 緑の公衆電話風。緑の筐体＋黒パネル、左に液晶（押した番号）・C（全消去）・⌫・スピーカー網、右に 1 2 3 / 4 5 6 / 7 8 9 / ＊ 0 ＃ の銀色丸ボタン（黒リング＋黒の丸文字）。押している間DTMFが鳴りオレンジに光る。「あ」ONで数字を入力欄にも入力 |
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

## ライセンス
- 電話機ボタンの数字フォント: M PLUS Rounded 1c (Copyright 2016 The Rounded M+ Project Authors) — SIL Open Font License 1.1。数字と英字のみにサブセット化して同梱（`licenses/OFL-MPLUSRounded1c.txt`）。
