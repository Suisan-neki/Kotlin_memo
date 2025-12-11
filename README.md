# Kotlin 給料タイマー API (Ktor)

Kotlin + Ktor で実装した「給料タイマー用バックエンド API」です。インメモリで動作し、時給設定・勤務セッション開始/終了・日別/月別集計を提供します。Render/Railway などの PaaS での稼働を想定し、`PORT` 環境変数に対応しています。

## 要件まとめ
- 言語: Kotlin (JVM)
- フレームワーク: Ktor
- ビルド: Gradle (Kotlin DSL)
- JSON: kotlinx.serialization
- 日時計算: kotlinx-datetime
- ログ: Logback
- データ: インメモリ（アプリ起動中のみ保持）

## ローカル実行

1) 依存関係取得と起動

```
./gradlew run
```

2) `PORT` を指定して起動（PaaS と同様の挙動）

```
PORT=8080 ./gradlew run
```

起動後のヘルスチェック:

```
curl http://localhost:8080/health
# => {"status":"ok"}
```

## Web UI（ブラウザ）

サーバー起動後、以下にアクセスしてください。

- URL: `http://localhost:8080/`

提供機能:
- 時給の取得・設定（設定は即時反映）
- タイマー開始 / 停止（進行中は開始ボタンが無効）
- 現在の稼ぎのリアルタイム表示（1秒ごとに更新）
- 今日の稼ぎ / 今月の稼ぎの表示（停止時に再取得）

実装ファイル:
- `src/main/resources/static/index.html`
- `src/main/resources/static/style.css`
- `src/main/resources/static/script.js`

Ktor 側のルーティングで `/` は `/index.html` にリダイレクトされ、`/` 配下で `static` ディレクトリのリソースが配信されます。

## API 一覧

共通: `Content-Type: application/json; charset=utf-8`

- GET `/health`
  - `{ "status": "ok" }`

- GET `/wage`
  - 現在の時給（未設定時は 404 `{ "error": "Wage is not set" }`）
- POST `/wage`
  - Body: `{ "hourlyWage": 1500 }`
  - バリデーション: `hourlyWage <= 0` は 400 `{ "error": "hourlyWage must be positive" }`

- POST `/sessions/start`
  - Body 任意: `{ "hourlyWageOverride": 1800 }`
  - 進行中セッションがあれば 400 `{ "error": "session already started" }`
  - 時給未設定かつ override なしなら 400 `{ "error": "hourly wage is not set" }`
  - 正常時: `201 Created` で `WorkSession`

- POST `/sessions/{id}/stop`
  - 見つからない: 404 `{ "error": "Session not found" }`
  - 既に停止: 400 `{ "error": "Session already stopped" }`
  - 正常時: `WorkSession` を返す（`earnedAmount` 確定）

- GET `/sessions`
  - クエリ: `date=YYYY-MM-DD` または `month=YYYY-MM`（任意）
  - 指定が無ければ全件

- GET `/sessions/current`
  - 進行中が無ければ 404 `{ "error": "No active session" }`
  - あれば `elapsedSeconds` と `currentEarnedAmount` を返す

- GET `/summary/daily`
  - クエリ: `date=YYYY-MM-DD`（任意・未指定ならサーバー日付の今日）
  - 確定済み（終了済み）セッションのみ合算

- GET `/summary/monthly?year=YYYY&month=MM`
  - 指定月の確定済み合計と日別内訳を返す

## 計算仕様
- 所得金額は「時給 × 経過秒数/3600」を切り捨て（floor）で算出。
- タイムスタンプは ISO8601（UTC, `Instant.toString()`）
- セッション開始時の時給を固定で保持（途中での時給変更の影響を受けない）

## コード構成
- `src/main/kotlin/com/example/Application.kt` … エントリポイント、シリアライズ設定、ルーティング
- `src/main/kotlin/com/example/models.kt` … シリアライズ対象のデータモデル/DTO
- `src/main/kotlin/com/example/repository.kt` … インメモリリポジトリ（スレッドセーフ制御、集計処理）
- `src/main/resources/logback.xml` … ログ設定（標準出力）

## curl 例

時給設定:
```
curl -X POST http://localhost:8080/wage \
  -H "Content-Type: application/json" \
  -d '{"hourlyWage": 1500}'
```

タイマー開始:
```
curl -X POST http://localhost:8080/sessions/start
```

現在セッション:
```
curl http://localhost:8080/sessions/current
```

今日の合計:
```
curl "http://localhost:8080/summary/daily"
```

月別合計:
```
curl "http://localhost:8080/summary/monthly?year=2025&month=01"
```
