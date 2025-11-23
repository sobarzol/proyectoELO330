# Python Chat Client

Cliente de chat gRPC implementado en Python.

## Instalación

```bash
poetry install
```

## Generar código protobuf

```bash
poetry run python -m grpc_tools.protoc -I. --python_out=. --grpc_python_out=. --pyi_out=. proto/chat.proto
```

## Ejecutar

```bash
poetry run python chat_client.py
```
