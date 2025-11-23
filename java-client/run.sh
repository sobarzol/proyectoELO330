#!/bin/bash

# Script para ejecutar el cliente Java con los permisos necesarios

JAR_FILE="target/chat-client-1.0-SNAPSHOT-jar-with-dependencies.jar"

# Verificar que el JAR existe
if [ ! -f "$JAR_FILE" ]; then
    echo "❌ JAR no encontrado: $JAR_FILE"
    echo "Ejecuta primero: mvn clean package"
    exit 1
fi

# Ejecutar con los flags necesarios
java --enable-native-access=ALL-UNNAMED \
     -jar "$JAR_FILE"
