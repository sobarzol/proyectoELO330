#!/bin/bash
# Script para generar los archivos Python desde .proto

python -m grpc_tools.protoc -I. --python_out=. --grpc_python_out=. proto/chat.proto

echo "Proto files generated successfully!"
