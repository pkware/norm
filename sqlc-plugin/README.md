# sqlc-plugin

This folder contains the Rust code for a WASM [sqlc plugin](https://docs.sqlc.dev/en/latest/guides/plugins.html).
The plugin is very simple: it receives the sqlc protobuf data, converts it to JSON, and echoes it back.
The plugin is designed for use with our Gradle plugin.

## Building
`cargo build --release --target wasm32-wasip1`

## Deploying
Create a GitHub release and upload the file.
